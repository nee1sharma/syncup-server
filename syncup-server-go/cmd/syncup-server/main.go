package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"syscall"
	"time"

	"syncup-server/internal/api"
	"syncup-server/internal/config"
	"syncup-server/internal/discovery"
	storagepkg "syncup-server/internal/storage"
	"syncup-server/internal/store"
)

var version = "dev"

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	if err := run(logger); err != nil {
		logger.Error("server_stopped", "error", err)
		os.Exit(1)
	}
}

func run(logger *slog.Logger) error {
	cfg, err := config.Load(os.Args[1:], version)
	if err != nil {
		return fmt.Errorf("configuration: %w", err)
	}
	storage, err := storagepkg.Open(cfg.StorageRoot, cfg.MinimumFreeBytes)
	if err != nil {
		return err
	}
	defer storage.Close()
	database, err := store.Open(filepath.Join(storage.Root, "syncup.db"))
	if err != nil {
		return err
	}
	defer database.Close()
	ctx := context.Background()
	identity, err := database.Identity(ctx, cfg.ServerName)
	if err != nil {
		return fmt.Errorf("server identity: %w", err)
	}
	if err := storage.EnsureIdentityFile(identity.ServerID); err != nil {
		return err
	}
	if err := database.InterruptActiveRuns(ctx); err != nil {
		return fmt.Errorf("recover backup runs: %w", err)
	}
	if err := database.ReconcilePartials(ctx, storage.Resolve); err != nil {
		return fmt.Errorf("recover partial uploads: %w", err)
	}

	application := api.New(cfg, storage, database, identity, logger)
	httpServer := &http.Server{
		Addr: cfg.HTTPAddress, Handler: application.Handler(), ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout: 2 * time.Minute, MaxHeaderBytes: 32 * 1024,
	}
	listener, err := net.Listen("tcp", cfg.HTTPAddress)
	if err != nil {
		return fmt.Errorf("bind HTTP server: %w", err)
	}

	runCtx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	errorsChannel := make(chan error, 2)
	go func() {
		logger.Info("http_started", "address", listener.Addr().String(), "storage_root", storage.Root,
			"server_id", identity.ServerID, "server_name", identity.ServerName,
			"warning", "trusted LAN only; authentication and TLS are disabled")
		err := httpServer.Serve(listener)
		if err != nil && !errors.Is(err, http.ErrServerClosed) {
			errorsChannel <- err
		}
	}()
	if cfg.DiscoveryEnabled {
		_, portText, _ := net.SplitHostPort(listener.Addr().String())
		httpPort, _ := strconv.Atoi(portText)
		responder := &discovery.Responder{Port: cfg.DiscoveryPort, HTTPPort: httpPort,
			MaxDatagram: cfg.DiscoveryMaxDatagram, Identity: identity, Logger: logger}
		go func() {
			if err := responder.Run(runCtx); err != nil {
				errorsChannel <- err
			}
		}()
	}

	select {
	case <-runCtx.Done():
	case err := <-errorsChannel:
		cancel()
		shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
		defer shutdownCancel()
		_ = httpServer.Shutdown(shutdownCtx)
		return err
	}
	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer shutdownCancel()
	if err := httpServer.Shutdown(shutdownCtx); err != nil {
		return fmt.Errorf("HTTP shutdown: %w", err)
	}
	logger.Info("http_stopped")
	return nil
}
