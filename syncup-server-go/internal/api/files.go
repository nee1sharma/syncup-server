package api

import (
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"syncup-server/internal/model"
	"syncup-server/internal/store"
)

func (s *Server) listFiles(w http.ResponseWriter, r *http.Request) {
	query := r.URL.Query()
	deviceID := strings.ToLower(query.Get("deviceId"))
	if deviceID != "" && !model.IsUUID(deviceID) {
		writeError(w, problem(http.StatusBadRequest, "INVALID_REQUEST", "deviceId is invalid"))
		return
	}
	mediaType := strings.ToUpper(query.Get("mediaType"))
	var capturedAfter *time.Time
	if value := query.Get("capturedAfter"); value != "" {
		parsed, err := time.Parse(time.RFC3339, value)
		if err != nil {
			writeError(w, problem(http.StatusBadRequest, "INVALID_REQUEST", "capturedAfter is invalid"))
			return
		}
		capturedAfter = &parsed
	}
	limit := 100
	if value := query.Get("limit"); value != "" {
		parsed, err := strconv.Atoi(value)
		if err != nil {
			writeError(w, problem(http.StatusBadRequest, "INVALID_PAGE_SIZE", "limit must be between 1 and 500"))
			return
		}
		limit = parsed
	}
	if limit < 1 || limit > 500 {
		writeError(w, problem(http.StatusBadRequest, "INVALID_PAGE_SIZE", "limit must be between 1 and 500"))
		return
	}
	cursor, apiErr := decodeCursor(query.Get("cursor"))
	if apiErr != nil {
		writeError(w, apiErr)
		return
	}
	files, err := s.store.ListFiles(r.Context(), deviceID, mediaType, capturedAfter, cursor, limit+1)
	if err != nil {
		s.internalError(w, "list files", err)
		return
	}
	more := len(files) > limit
	if more {
		files = files[:limit]
	}
	if files == nil {
		files = []model.StoredFile{}
	}
	var next *string
	if more && len(files) != 0 {
		encoded := encodeCursor(files[len(files)-1])
		next = &encoded
	}
	writeJSON(w, http.StatusOK, struct {
		Files      []model.StoredFile `json:"files"`
		NextCursor *string            `json:"nextCursor"`
	}{files, next})
}

func (s *Server) downloadFile(w http.ResponseWriter, r *http.Request) {
	id := strings.ToLower(r.PathValue("fileID"))
	if !model.IsUUID(id) {
		writeError(w, problem(http.StatusBadRequest, "INVALID_REQUEST", "fileId is invalid"))
		return
	}
	file, err := s.store.File(r.Context(), id)
	if errors.Is(err, store.ErrNotFound) || (err == nil && file.Status != "COMMITTED") {
		writeError(w, problem(http.StatusNotFound, "FILE_NOT_FOUND", "Committed file was not found"))
		return
	}
	if err != nil {
		s.internalError(w, "get file", err)
		return
	}
	path, err := s.storage.Resolve(file.StoredPath)
	if err != nil {
		s.internalError(w, "resolve file", err)
		return
	}
	handle, err := os.Open(path)
	if err != nil {
		_ = s.store.MarkFileMissing(r.Context(), id)
		writeError(w, problem(http.StatusGone, "FILE_CONTENT_MISSING", "File content is no longer available"))
		return
	}
	defer handle.Close()
	info, err := handle.Stat()
	if err != nil || !info.Mode().IsRegular() || info.Size() != file.SizeBytes {
		_ = s.store.MarkFileMissing(r.Context(), id)
		writeError(w, problem(http.StatusGone, "FILE_CONTENT_MISSING", "File content is no longer available"))
		return
	}
	byteRange, apiErr := parseByteRange(r.Header.Get("Range"), file.SizeBytes)
	if apiErr != nil {
		apiErr.Headers = map[string]string{"Content-Range": fmt.Sprintf("bytes */%d", file.SizeBytes)}
		writeError(w, apiErr)
		return
	}
	start, length, status := int64(0), file.SizeBytes, http.StatusOK
	if byteRange != nil {
		start = byteRange.start
		length = byteRange.end - byteRange.start + 1
		status = http.StatusPartialContent
		w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", byteRange.start, byteRange.end, file.SizeBytes))
	}
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("ETag", `"`+file.SHA256+`"`)
	w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	disposition := mime.FormatMediaType("attachment", map[string]string{"filename": file.OriginalName})
	w.Header().Set("Content-Disposition", disposition)
	if _, _, err := mime.ParseMediaType(file.MIMEType); err == nil {
		w.Header().Set("Content-Type", file.MIMEType)
	} else {
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	w.WriteHeader(status)
	if length == 0 {
		return
	}
	if _, err := handle.Seek(start, io.SeekStart); err != nil {
		return
	}
	_, _ = io.CopyN(w, handle, length)
}

type byteRange struct{ start, end int64 }

func parseByteRange(header string, size int64) (*byteRange, *apiError) {
	if strings.TrimSpace(header) == "" {
		return nil, nil
	}
	unsatisfiable := func() (*byteRange, *apiError) {
		return nil, problem(http.StatusRequestedRangeNotSatisfiable, "RANGE_NOT_SATISFIABLE", "Requested byte range cannot be satisfied")
	}
	if !strings.HasPrefix(header, "bytes=") || strings.Contains(header, ",") || size == 0 {
		return unsatisfiable()
	}
	value := strings.TrimSpace(strings.TrimPrefix(header, "bytes="))
	dash := strings.IndexByte(value, '-')
	if dash < 0 || strings.Contains(value[dash+1:], "-") {
		return unsatisfiable()
	}
	if dash == 0 {
		suffix, err := strconv.ParseInt(value[1:], 10, 64)
		if err != nil || suffix <= 0 {
			return unsatisfiable()
		}
		start := size - suffix
		if start < 0 {
			start = 0
		}
		return &byteRange{start: start, end: size - 1}, nil
	}
	start, err := strconv.ParseInt(value[:dash], 10, 64)
	if err != nil || start < 0 || start >= size {
		return unsatisfiable()
	}
	end := size - 1
	if dash != len(value)-1 {
		end, err = strconv.ParseInt(value[dash+1:], 10, 64)
		if err != nil || end < start {
			return unsatisfiable()
		}
		if end >= size {
			end = size - 1
		}
	}
	return &byteRange{start: start, end: end}, nil
}

func encodeCursor(file model.StoredFile) string {
	raw := file.BackedUpAt.UTC().Format(time.RFC3339Nano) + "|" + file.FileID
	return base64.RawURLEncoding.EncodeToString([]byte(raw))
}

func decodeCursor(encoded string) (*store.FileCursor, *apiError) {
	if strings.TrimSpace(encoded) == "" {
		return nil, nil
	}
	raw, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return nil, problem(http.StatusBadRequest, "INVALID_CURSOR", "File listing cursor is invalid")
	}
	parts := strings.Split(string(raw), "|")
	if len(parts) != 2 || !model.IsUUID(parts[1]) {
		return nil, problem(http.StatusBadRequest, "INVALID_CURSOR", "File listing cursor is invalid")
	}
	parsed, err := time.Parse(time.RFC3339Nano, parts[0])
	if err != nil {
		return nil, problem(http.StatusBadRequest, "INVALID_CURSOR", "File listing cursor is invalid")
	}
	return &store.FileCursor{BackedUpAt: parsed, FileID: strings.ToLower(parts[1])}, nil
}
