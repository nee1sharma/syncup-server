package api

import (
	"context"
	"log/slog"
	"net/http"
	"runtime/debug"
	"strings"
	"sync"
	"time"

	"syncup-server/internal/config"
	"syncup-server/internal/model"
	storagepkg "syncup-server/internal/storage"
	"syncup-server/internal/store"
)

var capabilities = []string{"INCREMENTAL_BACKUP", "RESUMABLE_UPLOAD", "RANGE_DOWNLOAD"}

type Server struct {
	cfg      config.Config
	storage  *storagepkg.Storage
	store    *store.Store
	identity model.ServerIdentity
	logger   *slog.Logger
	handler  http.Handler
	limiter  *transferLimiter
	commitMu sync.Mutex
}

func New(cfg config.Config, storage *storagepkg.Storage, database *store.Store,
	identity model.ServerIdentity, logger *slog.Logger) *Server {
	s := &Server{cfg: cfg, storage: storage, store: database, identity: identity, logger: logger,
		limiter: newTransferLimiter(cfg.MaxConcurrentTotal, cfg.MaxConcurrentPerDevice)}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/server", s.getServer)
	mux.HandleFunc("POST /api/v1/backups", s.createBackup)
	mux.HandleFunc("POST /api/v1/backups/{runID}/manifest", s.submitManifest)
	mux.HandleFunc("POST /api/v1/backups/{runID}/complete", s.completeBackup)
	mux.HandleFunc("POST /api/v1/backups/{runID}/cancel", s.cancelBackup)
	mux.HandleFunc("GET /api/v1/transfers/{transferID}", s.transferStatus)
	mux.HandleFunc("PUT /api/v1/transfers/{transferID}/content", s.uploadContent)
	mux.HandleFunc("GET /api/v1/files", s.listFiles)
	mux.HandleFunc("GET /api/v1/files/{fileID}/content", s.downloadFile)
	mux.HandleFunc("GET /actuator/health", s.health)
	mux.HandleFunc("GET /actuator/info", s.info)
	s.handler = s.recoverMiddleware(s.loggingMiddleware(mux))
	return s
}

func (s *Server) Handler() http.Handler { return s.handler }

func (s *Server) getServer(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{
		"serverId": s.identity.ServerID, "serverName": s.identity.ServerName,
		"apiVersion": "v1", "appVersion": s.cfg.AppVersion, "capabilities": capabilities,
	})
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.store.Ping(ctx); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "DOWN"})
		return
	}
	if _, err := s.storage.AvailableBytes(); err != nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"status": "DOWN"})
		return
	}
	writeJSON(w, http.StatusOK, map[string]string{"status": "UP"})
}

func (s *Server) info(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]any{"app": map[string]string{
		"name": "SyncUp Server", "version": s.cfg.AppVersion, "apiVersion": "v1"}})
}

func (s *Server) loggingMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		next.ServeHTTP(w, r)
		s.logger.Info("http_request", "method", r.Method, "path", r.URL.Path,
			"remote", r.RemoteAddr, "duration_ms", time.Since(start).Milliseconds())
	})
}

func (s *Server) recoverMiddleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if recovered := recover(); recovered != nil {
				s.logger.Error("request_panic", "error", recovered, "stack", string(debug.Stack()))
				writeError(w, problem(http.StatusInternalServerError, "INTERNAL_ERROR", "The server could not complete the request"))
			}
		}()
		next.ServeHTTP(w, r)
	})
}

func validDeviceName(value string) (string, *apiError) {
	trimmed := strings.TrimSpace(value)
	if trimmed == "" || len(trimmed) > 120 || trimmed == "." || trimmed == ".." ||
		strings.ContainsAny(trimmed, "/\\") || hasControl(trimmed) {
		return "", problem(http.StatusBadRequest, "INVALID_DEVICE_NAME",
			"deviceName must be a plain folder name without path separators, control characters, or dot segments")
	}
	return trimmed, nil
}

func hasControl(value string) bool {
	for _, char := range value {
		if char < 0x20 || (char >= 0x7f && char <= 0x9f) {
			return true
		}
	}
	return false
}
