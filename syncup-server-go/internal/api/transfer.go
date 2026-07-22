package api

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"syncup-server/internal/model"
	"syncup-server/internal/store"
)

const (
	deviceIDHeader     = "X-SyncUp-Device-Id"
	deviceNameHeader   = "X-SyncUp-Device-Name"
	runIDHeader        = "X-SyncUp-Run-Id"
	uploadOffsetHeader = "Upload-Offset"
)

type transferAuth struct{ deviceID, deviceName, runID string }

func (s *Server) transferStatus(w http.ResponseWriter, r *http.Request) {
	id := strings.ToLower(r.PathValue("transferID"))
	if !model.IsUUID(id) {
		writeError(w, problem(http.StatusBadRequest, "INVALID_REQUEST", "transferId is invalid"))
		return
	}
	auth, apiErr := parseTransferAuth(r)
	if apiErr != nil {
		writeError(w, apiErr)
		return
	}
	if err := s.store.UpsertDevice(r.Context(), auth.deviceID, auth.deviceName, time.Now().UTC()); err != nil {
		s.internalError(w, "update device", err)
		return
	}
	transfer, err := s.store.Transfer(r.Context(), id)
	if err != nil || transfer.DeviceID != auth.deviceID || transfer.RunID != auth.runID {
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			s.internalError(w, "get transfer", err)
			return
		}
		writeError(w, problem(http.StatusNotFound, "TRANSFER_NOT_FOUND", "Transfer was not found for this device and backup run"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"transferId": transfer.TransferID, "state": transfer.State,
		"uploadOffset": transfer.Accepted, "expectedSize": transfer.ExpectedSize, "expiresAt": transfer.ExpiresAt})
}

func (s *Server) uploadContent(w http.ResponseWriter, r *http.Request) {
	id := strings.ToLower(r.PathValue("transferID"))
	if !model.IsUUID(id) {
		writeError(w, problem(http.StatusBadRequest, "INVALID_REQUEST", "transferId is invalid"))
		return
	}
	auth, apiErr := parseTransferAuth(r)
	if apiErr != nil {
		writeError(w, apiErr)
		return
	}
	if r.ContentLength < 0 {
		writeError(w, problem(http.StatusLengthRequired, "CONTENT_LENGTH_REQUIRED", "Content-Length is required for upload segments"))
		return
	}
	if r.ContentLength > s.cfg.MaxSegmentBytes {
		writeError(w, problem(http.StatusRequestEntityTooLarge, "SEGMENT_TOO_LARGE", "Upload segment exceeds the configured maximum"))
		return
	}
	offset, err := strconv.ParseInt(r.Header.Get(uploadOffsetHeader), 10, 64)
	if err != nil || offset < 0 {
		writeError(w, problem(http.StatusBadRequest, "INVALID_UPLOAD_OFFSET", "Upload-Offset must be a non-negative integer"))
		return
	}
	release, capacityDetail := s.limiter.acquire(id, auth.deviceID)
	if release == nil {
		err := problem(http.StatusTooManyRequests, "TRANSFER_CAPACITY", capacityDetail)
		err.Headers = map[string]string{"Retry-After": "1"}
		err.Properties = map[string]any{"retryAfterSeconds": 1}
		writeError(w, err)
		return
	}
	defer release()
	if err := s.store.UpsertDevice(r.Context(), auth.deviceID, auth.deviceName, time.Now().UTC()); err != nil {
		s.internalError(w, "update device", err)
		return
	}
	transfer, err := s.store.Transfer(r.Context(), id)
	if err != nil || transfer.DeviceID != auth.deviceID || transfer.RunID != auth.runID {
		if err != nil && !errors.Is(err, store.ErrNotFound) {
			s.internalError(w, "get transfer", err)
			return
		}
		writeError(w, problem(http.StatusNotFound, "TRANSFER_NOT_FOUND", "Transfer was not found for this device and backup run"))
		return
	}
	run, err := s.store.Run(r.Context(), auth.runID)
	if err != nil || run.DeviceID != auth.deviceID {
		writeError(w, problem(http.StatusNotFound, "RUN_NOT_FOUND", "Backup run was not found for this device"))
		return
	}
	if run.State == "CANCELLED" || run.State == "COMPLETED" {
		writeError(w, problem(http.StatusConflict, "RUN_NOT_ACTIVE", "The backup run no longer accepts upload content"))
		return
	}
	if transfer.State == "COMMITTED" {
		w.Header().Set(uploadOffsetHeader, strconv.FormatInt(transfer.ExpectedSize, 10))
		w.Header().Set("Upload-Complete", "true")
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if transfer.State == "REJECTED" || transfer.State == "EXPIRED" {
		writeError(w, problem(http.StatusConflict, "TRANSFER_NOT_ACTIVE", "The transfer no longer accepts upload content"))
		return
	}
	if transfer.Accepted != offset {
		err := problem(http.StatusConflict, "UPLOAD_OFFSET_MISMATCH", "Upload-Offset does not match the durable server offset")
		err.Headers = map[string]string{uploadOffsetHeader: strconv.FormatInt(transfer.Accepted, 10)}
		err.Properties = map[string]any{"uploadOffset": transfer.Accepted}
		writeError(w, err)
		return
	}
	if r.ContentLength > transfer.ExpectedSize-offset {
		writeError(w, problem(http.StatusRequestEntityTooLarge, "TRANSFER_SIZE_EXCEEDED", "Segment would exceed the expected file size"))
		return
	}
	if !s.storage.HasBudget(r.ContentLength, s.cfg.MinimumFreeBytes) {
		writeError(w, problem(http.StatusInsufficientStorage, "INSUFFICIENT_STORAGE", "Storage safety margin would be exceeded"))
		return
	}
	partial, err := s.storage.Resolve(transfer.PartialPath)
	if err != nil {
		s.internalError(w, "resolve partial", err)
		return
	}
	if apiErr := writeSegment(partial, offset, r.ContentLength, r.Body); apiErr != nil {
		writeError(w, apiErr)
		return
	}
	accepted := offset + r.ContentLength
	state := "PARTIAL"
	if accepted == 0 {
		state = "PENDING"
	}
	if err := s.store.UpdateTransferOffset(r.Context(), id, accepted, state, time.Now().UTC(), time.Now().UTC().Add(s.cfg.PartialRetention)); err != nil {
		_ = os.Truncate(partial, offset)
		s.internalError(w, "save transfer offset", err)
		return
	}
	_ = s.store.UpdateRunState(r.Context(), auth.runID, "TRANSFERRING")
	complete := accepted == transfer.ExpectedSize
	if complete {
		if apiErr := s.verifyAndCommit(r, transfer, partial, auth.deviceName); apiErr != nil {
			writeError(w, apiErr)
			return
		}
	}
	w.Header().Set(uploadOffsetHeader, strconv.FormatInt(accepted, 10))
	w.Header().Set("Upload-Complete", strconv.FormatBool(complete))
	w.WriteHeader(http.StatusNoContent)
}

func parseTransferAuth(r *http.Request) (transferAuth, *apiError) {
	auth := transferAuth{deviceID: strings.ToLower(r.Header.Get(deviceIDHeader)),
		deviceName: r.Header.Get(deviceNameHeader), runID: strings.ToLower(r.Header.Get(runIDHeader))}
	name, err := validDeviceName(auth.deviceName)
	if err != nil {
		return auth, err
	}
	auth.deviceName = name
	if !model.IsUUID(auth.deviceID) || !model.IsUUID(auth.runID) {
		return auth, problem(http.StatusBadRequest, "INVALID_REQUEST", "Required SyncUp device and run headers are missing or invalid")
	}
	return auth, nil
}

func writeSegment(path string, offset, length int64, input io.Reader) *apiError {
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return storageError()
	}
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return storageError()
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return storageError()
	}
	if info.Size() != offset {
		authoritative := info.Size()
		if authoritative > offset {
			authoritative = offset
		}
		apiErr := problem(http.StatusConflict, "PARTIAL_OFFSET_MISMATCH", "Partial file does not match the durable server offset")
		apiErr.Properties = map[string]any{"uploadOffset": authoritative}
		apiErr.Headers = map[string]string{uploadOffsetHeader: strconv.FormatInt(authoritative, 10)}
		return apiErr
	}
	if _, err := file.Seek(offset, io.SeekStart); err != nil {
		return storageError()
	}
	written, copyErr := io.CopyN(file, input, length)
	if copyErr != nil || written != length {
		_ = file.Truncate(offset)
		_ = file.Sync()
		return problem(http.StatusBadRequest, "EARLY_EOF", "Request body ended before Content-Length bytes were received")
	}
	var extra [1]byte
	if count, _ := input.Read(extra[:]); count != 0 {
		_ = file.Truncate(offset)
		_ = file.Sync()
		return problem(http.StatusBadRequest, "EXCESS_REQUEST_BODY", "Request body contains more bytes than Content-Length")
	}
	if err := file.Sync(); err != nil {
		_ = file.Truncate(offset)
		return storageError()
	}
	return nil
}

func (s *Server) verifyAndCommit(r *http.Request, transfer model.Transfer, partial, deviceName string) *apiError {
	actual, err := fileSHA256(partial)
	if err != nil {
		return storageError()
	}
	if actual != transfer.ExpectedSHA {
		target, _ := s.storage.Resolve("quarantine/" + transfer.TransferID + "-checksum.part")
		_ = os.Rename(partial, target)
		_ = s.store.MarkTransferRejected(r.Context(), transfer.TransferID, time.Now().UTC())
		return problem(http.StatusUnprocessableEntity, "CHECKSUM_MISMATCH", "Uploaded content does not match the declared SHA-256")
	}
	s.commitMu.Lock()
	defer s.commitMu.Unlock()
	reused, err := s.store.CommitExistingIdentity(r.Context(), transfer.DeviceID, transfer.ExpectedSHA,
		transfer.ExpectedSize, transfer.TransferID, time.Now().UTC())
	if err != nil {
		s.logger.Error("deduplicate_transfer_failed", "error", err)
		return problem(http.StatusInternalServerError, "INTERNAL_ERROR", "File metadata could not be committed")
	}
	if reused {
		_ = os.Remove(partial)
		return nil
	}
	manifest, err := s.store.ManifestEntry(r.Context(), transfer.RunID, transfer.ClientFileKey)
	if err != nil {
		s.logger.Error("manifest_missing_for_transfer", "transfer_id", transfer.TransferID, "error", err)
		return problem(http.StatusInternalServerError, "INTERNAL_ERROR", "The transfer manifest is unavailable")
	}
	storedPath := filepath.ToSlash(filepath.Join("data", deviceName, manifest.DisplayName))
	destination, err := s.storage.Resolve(storedPath)
	if err != nil {
		return storageError()
	}
	fileID, err := model.NewUUID()
	if err != nil {
		return storageError()
	}
	stored := model.StoredFile{FileID: fileID, DeviceID: transfer.DeviceID, ClientFileKey: transfer.ClientFileKey,
		OriginalName: manifest.DisplayName, OriginalRelativePath: manifest.RelativePath, MediaType: manifest.MediaType,
		MIMEType: manifest.MIMEType, SizeBytes: transfer.ExpectedSize, SHA256: transfer.ExpectedSHA,
		CapturedAt: manifest.CapturedAt, ModifiedAt: manifest.ModifiedAt, StoredPath: storedPath,
		BackedUpAt: time.Now().UTC(), Status: "COMMITTED"}
	if info, statErr := os.Stat(destination); statErr == nil && info.Mode().IsRegular() {
		matches, hashErr := contentMatches(destination, transfer.ExpectedSize, transfer.ExpectedSHA)
		if hashErr != nil {
			return storageError()
		}
		if !matches {
			_ = os.Remove(partial)
			_ = s.store.MarkTransferRejected(r.Context(), transfer.TransferID, time.Now().UTC())
			return problem(http.StatusConflict, "FILE_PATH_CONFLICT", "A file with the same device name and file name already exists")
		}
		if _, err := s.store.CommitReusedFile(r.Context(), stored, transfer.TransferID, time.Now().UTC()); err != nil {
			s.logger.Error("reuse_file_failed", "error", err)
			return problem(http.StatusInternalServerError, "INTERNAL_ERROR", "File metadata could not be committed")
		}
		_ = os.Remove(partial)
		return nil
	} else if statErr != nil && !errors.Is(statErr, os.ErrNotExist) {
		return storageError()
	}
	if err := s.store.PrepareFile(r.Context(), stored, transfer.TransferID); err != nil {
		s.logger.Error("stage_file_failed", "error", err)
		return problem(http.StatusConflict, "FILE_PATH_CONFLICT", "File metadata conflicts with existing content")
	}
	if err := os.MkdirAll(filepath.Dir(destination), 0o750); err != nil {
		return storageError()
	}
	if err := os.Link(partial, destination); err != nil {
		conflict := errors.Is(err, os.ErrExist)
		_ = s.store.AbortPreparedFile(r.Context(), fileID, transfer.TransferID, conflict, time.Now().UTC())
		if conflict {
			return problem(http.StatusConflict, "FILE_PATH_CONFLICT", "A file with the same device name and file name already exists")
		}
		return storageError()
	}
	_ = os.Remove(partial)
	if err := s.store.FinalizeFile(r.Context(), fileID, transfer.TransferID, time.Now().UTC()); err != nil {
		s.logger.Error("finalize_file_failed", "error", err)
		return problem(http.StatusInternalServerError, "INTERNAL_ERROR", "File metadata could not be finalized")
	}
	return nil
}

func fileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	digest := sha256.New()
	if _, err := io.Copy(digest, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

func contentMatches(path string, size int64, sha string) (bool, error) {
	info, err := os.Stat(path)
	if err != nil {
		return false, err
	}
	if info.Size() != size {
		return false, nil
	}
	actual, err := fileSHA256(path)
	return actual == sha, err
}

func storageError() *apiError {
	return problem(http.StatusInsufficientStorage, "STORAGE_IO_ERROR", "Storage operation could not be completed")
}
