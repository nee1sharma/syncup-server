package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strings"
)

type apiError struct {
	Status     int
	Code       string
	Detail     string
	Properties map[string]any
	Headers    map[string]string
}

func (e *apiError) Error() string { return e.Code + ": " + e.Detail }

func problem(status int, code, detail string) *apiError {
	return &apiError{Status: status, Code: code, Detail: detail}
}

func writeError(w http.ResponseWriter, err error) {
	var apiErr *apiError
	if !errors.As(err, &apiErr) {
		apiErr = problem(http.StatusInternalServerError, "INTERNAL_ERROR", "The server could not complete the request")
	}
	for name, value := range apiErr.Headers {
		w.Header().Set(name, value)
	}
	body := map[string]any{
		"type":   "urn:syncup:error:" + strings.ToLower(apiErr.Code),
		"title":  http.StatusText(apiErr.Status),
		"status": apiErr.Status,
		"detail": apiErr.Detail,
		"code":   apiErr.Code,
	}
	for name, value := range apiErr.Properties {
		body[name] = value
	}
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(apiErr.Status)
	_ = json.NewEncoder(w).Encode(body)
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any, maxBytes int64) *apiError {
	body := http.MaxBytesReader(w, r.Body, maxBytes)
	decoder := json.NewDecoder(body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil {
		var tooLarge *http.MaxBytesError
		if errors.As(err, &tooLarge) {
			return problem(http.StatusRequestEntityTooLarge, "MANIFEST_BODY_TOO_LARGE", "JSON request body exceeds the configured limit")
		}
		return problem(http.StatusBadRequest, "INVALID_REQUEST", "The request body is malformed: "+safeJSONError(err))
	}
	if err := decoder.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return problem(http.StatusBadRequest, "INVALID_REQUEST", "The request body must contain one JSON object")
	}
	return nil
}

func safeJSONError(err error) string {
	var syntax *json.SyntaxError
	var typeErr *json.UnmarshalTypeError
	switch {
	case errors.As(err, &syntax):
		return fmt.Sprintf("invalid JSON near byte %d", syntax.Offset)
	case errors.As(err, &typeErr):
		return "a field has the wrong JSON type"
	case errors.Is(err, io.EOF):
		return "body is empty"
	default:
		message := err.Error()
		if strings.HasPrefix(message, "json: unknown field ") {
			return message
		}
		return "invalid JSON"
	}
}
