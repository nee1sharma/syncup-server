package api

import (
	"errors"
	"net/http"
	"regexp"
	"strings"
	"time"

	"syncup-server/internal/model"
	"syncup-server/internal/store"
)

var shaPattern = regexp.MustCompile(`^[a-fA-F0-9]{64}$`)
var supportedMedia = map[string]bool{"IMAGE": true, "VIDEO": true, "AUDIO": true, "DOCUMENT": true, "OTHER": true}

type createBackupRequest struct {
	DeviceID       string `json:"deviceId"`
	DeviceName     string `json:"deviceName"`
	IdempotencyKey string `json:"idempotencyKey"`
}

type backupRunResponse struct {
	RunID       string     `json:"runId"`
	DeviceID    string     `json:"deviceId"`
	State       string     `json:"state"`
	StartedAt   time.Time  `json:"startedAt"`
	CompletedAt *time.Time `json:"completedAt"`
	FileCount   int64      `json:"fileCount"`
	ByteCount   int64      `json:"byteCount"`
}

type manifestRequest struct {
	DeviceID   string               `json:"deviceId"`
	DeviceName string               `json:"deviceName"`
	Files      []model.ManifestFile `json:"files"`
}

type runActionRequest struct {
	DeviceID   string `json:"deviceId"`
	DeviceName string `json:"deviceName"`
}

func (s *Server) createBackup(w http.ResponseWriter, r *http.Request) {
	var request createBackupRequest
	if err := decodeJSON(w, r, &request, 1024*1024); err != nil {
		writeError(w, err)
		return
	}
	name, apiErr := validDeviceName(request.DeviceName)
	if apiErr != nil {
		writeError(w, apiErr)
		return
	}
	if !model.IsUUID(request.DeviceID) || strings.TrimSpace(request.IdempotencyKey) == "" || len(request.IdempotencyKey) > 160 {
		writeError(w, problem(http.StatusBadRequest, "VALIDATION_FAILED", "deviceId and idempotencyKey must be valid"))
		return
	}
	run, err := s.store.CreateOrGetRun(r.Context(), strings.ToLower(request.DeviceID), name,
		request.IdempotencyKey, time.Now().UTC())
	if err != nil {
		s.internalError(w, "create backup", err)
		return
	}
	w.Header().Set("Location", "/api/v1/backups/"+run.RunID)
	writeJSON(w, http.StatusCreated, runResponse(run))
}

func (s *Server) submitManifest(w http.ResponseWriter, r *http.Request) {
	runID := strings.ToLower(r.PathValue("runID"))
	if !model.IsUUID(runID) {
		writeError(w, problem(http.StatusBadRequest, "INVALID_REQUEST", "runId is invalid"))
		return
	}
	var request manifestRequest
	if err := decodeJSON(w, r, &request, s.cfg.ManifestMaxBodyBytes); err != nil {
		writeError(w, err)
		return
	}
	name, apiErr := validDeviceName(request.DeviceName)
	if apiErr != nil {
		writeError(w, apiErr)
		return
	}
	request.DeviceID = strings.ToLower(request.DeviceID)
	if !model.IsUUID(request.DeviceID) || len(request.Files) == 0 {
		writeError(w, problem(http.StatusBadRequest, "VALIDATION_FAILED", "deviceId and at least one manifest file are required"))
		return
	}
	if len(request.Files) > s.cfg.ManifestMaxFiles {
		writeError(w, problem(http.StatusRequestEntityTooLarge, "MANIFEST_BATCH_TOO_LARGE", "Manifest batch exceeds the configured file limit"))
		return
	}
	keys := make(map[string]struct{}, len(request.Files))
	for index := range request.Files {
		file := &request.Files[index]
		if validation := s.validateManifestFile(file); validation != nil {
			writeError(w, validation)
			return
		}
		if _, exists := keys[file.ClientFileKey]; exists {
			writeError(w, problem(http.StatusConflict, "DUPLICATE_CLIENT_FILE_KEY", "A clientFileKey occurs more than once in the manifest batch"))
			return
		}
		keys[file.ClientFileKey] = struct{}{}
		file.SHA256 = strings.ToLower(file.SHA256)
		file.MediaType = strings.ToUpper(file.MediaType)
		present, err := s.store.HasCommittedIdentity(r.Context(), request.DeviceID, file.SHA256, file.SizeBytes)
		if err != nil {
			s.internalError(w, "inspect committed file", err)
			return
		}
		if !present && !s.storage.HasBudget(file.SizeBytes, s.cfg.MinimumFreeBytes) {
			writeError(w, problem(http.StatusInsufficientStorage, "INSUFFICIENT_STORAGE", "Storage safety margin would be exceeded"))
			return
		}
	}
	plan, err := s.store.PlanManifest(r.Context(), runID, request.DeviceID, name, request.Files,
		s.cfg.SegmentBytes, s.cfg.PartialRetention, time.Now().UTC())
	if err != nil {
		s.writeStoreError(w, err, "manifest")
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"runId": runID, "state": "PLANNED", "files": plan})
}

func (s *Server) completeBackup(w http.ResponseWriter, r *http.Request) { s.runAction(w, r, true) }
func (s *Server) cancelBackup(w http.ResponseWriter, r *http.Request)   { s.runAction(w, r, false) }

func (s *Server) runAction(w http.ResponseWriter, r *http.Request, complete bool) {
	runID := strings.ToLower(r.PathValue("runID"))
	if !model.IsUUID(runID) {
		writeError(w, problem(http.StatusBadRequest, "INVALID_REQUEST", "runId is invalid"))
		return
	}
	var request runActionRequest
	if err := decodeJSON(w, r, &request, 1024*1024); err != nil {
		writeError(w, err)
		return
	}
	name, apiErr := validDeviceName(request.DeviceName)
	if apiErr != nil {
		writeError(w, apiErr)
		return
	}
	request.DeviceID = strings.ToLower(request.DeviceID)
	if !model.IsUUID(request.DeviceID) {
		writeError(w, problem(http.StatusBadRequest, "VALIDATION_FAILED", "deviceId is invalid"))
		return
	}
	if err := s.store.UpsertDevice(r.Context(), request.DeviceID, name, time.Now().UTC()); err != nil {
		s.internalError(w, "update device", err)
		return
	}
	if complete {
		run, pending, err := s.store.CompleteRun(r.Context(), runID, request.DeviceID, time.Now().UTC())
		if errors.Is(err, store.ErrIncompleteRun) {
			apiErr := problem(http.StatusConflict, "TRANSFERS_INCOMPLETE", "All planned uploads must be committed before completing the run")
			apiErr.Properties = map[string]any{"pendingTransfers": pending}
			writeError(w, apiErr)
			return
		}
		if errors.Is(err, store.ErrTerminalRun) {
			writeError(w, problem(http.StatusConflict, "RUN_CANCELLED", "A cancelled backup run cannot be completed"))
			return
		}
		if err != nil {
			s.writeStoreError(w, err, "complete run")
			return
		}
		writeJSON(w, http.StatusOK, runResponse(run))
		return
	}
	run, err := s.store.CancelRun(r.Context(), runID, request.DeviceID)
	if errors.Is(err, store.ErrTerminalRun) {
		writeError(w, problem(http.StatusConflict, "RUN_COMPLETED", "A completed backup run cannot be cancelled"))
		return
	}
	if err != nil {
		s.writeStoreError(w, err, "cancel run")
		return
	}
	writeJSON(w, http.StatusOK, runResponse(run))
}

func (s *Server) validateManifestFile(file *model.ManifestFile) *apiError {
	if strings.TrimSpace(file.ClientFileKey) == "" || len(file.ClientFileKey) > 256 {
		return problem(http.StatusBadRequest, "VALIDATION_FAILED", "clientFileKey is required and must be at most 256 characters")
	}
	if strings.TrimSpace(file.DisplayName) == "" || len(file.DisplayName) > 255 || file.DisplayName == "." ||
		file.DisplayName == ".." || strings.ContainsAny(file.DisplayName, "/\\") || hasControl(file.DisplayName) {
		return problem(http.StatusBadRequest, "INVALID_FILE_NAME", "displayName must be a plain file name without path separators or dot segments")
	}
	if file.RelativePath != nil {
		relative := *file.RelativePath
		if len(relative) > 1024 || hasControl(relative) || strings.HasPrefix(relative, "/") || strings.HasPrefix(relative, "\\") ||
			(len(relative) >= 2 && relative[1] == ':') {
			return problem(http.StatusBadRequest, "INVALID_RELATIVE_PATH", "relativePath must be a safe relative path")
		}
		for _, segment := range strings.Split(strings.ReplaceAll(relative, "\\", "/"), "/") {
			if segment == "." || segment == ".." {
				return problem(http.StatusBadRequest, "INVALID_RELATIVE_PATH", "relativePath cannot contain traversal segments")
			}
		}
	}
	media := strings.ToUpper(file.MediaType)
	if !supportedMedia[media] {
		return problem(http.StatusBadRequest, "UNSUPPORTED_MEDIA_TYPE", "Unsupported logical mediaType")
	}
	if file.MIMEType == "" || len(file.MIMEType) > 255 || hasControl(file.MIMEType) || !strings.Contains(file.MIMEType, "/") {
		return problem(http.StatusBadRequest, "INVALID_MIME_TYPE", "mimeType is invalid")
	}
	if file.SizeBytes < 0 || file.SizeBytes > s.cfg.MaxFileBytes {
		return problem(http.StatusBadRequest, "INVALID_FILE_SIZE", "File size is outside the supported range")
	}
	if !shaPattern.MatchString(file.SHA256) {
		return problem(http.StatusBadRequest, "VALIDATION_FAILED", "sha256 must be a 64-character hexadecimal digest")
	}
	return nil
}

func runResponse(run model.BackupRun) backupRunResponse {
	return backupRunResponse{RunID: run.RunID, DeviceID: run.DeviceID, State: run.State,
		StartedAt: run.StartedAt, CompletedAt: run.CompletedAt, FileCount: run.FileCount, ByteCount: run.ByteCount}
}

func (s *Server) writeStoreError(w http.ResponseWriter, err error, operation string) {
	switch {
	case errors.Is(err, store.ErrNotFound), errors.Is(err, store.ErrNotOwned):
		writeError(w, problem(http.StatusNotFound, "RUN_NOT_FOUND", "Backup run was not found for this device"))
	case errors.Is(err, store.ErrTerminalRun):
		writeError(w, problem(http.StatusConflict, "RUN_NOT_ACTIVE", "The backup run no longer accepts this operation"))
	case errors.Is(err, store.ErrDuplicateKey):
		writeError(w, problem(http.StatusConflict, "DUPLICATE_CLIENT_FILE_KEY", "The backup run already contains this clientFileKey"))
	default:
		s.internalError(w, operation, err)
	}
}

func (s *Server) internalError(w http.ResponseWriter, operation string, err error) {
	s.logger.Error("operation_failed", "operation", operation, "error", err)
	writeError(w, err)
}
