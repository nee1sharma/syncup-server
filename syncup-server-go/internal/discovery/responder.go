package discovery

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"syncup-server/internal/model"
)

type Responder struct {
	Port          int
	HTTPPort      int
	MaxDatagram   int
	Identity      model.ServerIdentity
	Logger        *slog.Logger
	mu            sync.Mutex
	lastResponses map[string]time.Time
}

type request struct {
	Type       string `json:"type"`
	APIVersion string `json:"apiVersion"`
	RequestID  string `json:"requestId"`
}

func (r *Responder) Run(ctx context.Context) error {
	address := &net.UDPAddr{IP: net.IPv4zero, Port: r.Port}
	connection, err := net.ListenUDP("udp", address)
	if err != nil {
		return fmt.Errorf("bind UDP discovery: %w", err)
	}
	defer connection.Close()
	if r.lastResponses == nil {
		r.lastResponses = make(map[string]time.Time)
	}
	r.Logger.Info("discovery_started", "port", r.Port)
	defer r.Logger.Info("discovery_stopped")
	go func() { <-ctx.Done(); _ = connection.Close() }()
	buffer := make([]byte, r.MaxDatagram)
	for {
		count, remote, err := connection.ReadFromUDP(buffer)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, net.ErrClosed) {
				return nil
			}
			return fmt.Errorf("read UDP discovery: %w", err)
		}
		if count == len(buffer) {
			continue
		}
		var incoming request
		if json.Unmarshal(buffer[:count], &incoming) != nil || incoming.Type != "SYNCUP_DISCOVER" ||
			incoming.APIVersion != "v1" || !model.IsUUID(incoming.RequestID) || !r.allow(remote.IP.String()) {
			continue
		}
		localIP := routeAddress(remote)
		response := map[string]any{
			"type": "SYNCUP_SERVER", "requestId": incoming.RequestID,
			"serverId": r.Identity.ServerID, "serverName": r.Identity.ServerName,
			"apiVersion": "v1", "httpPort": r.HTTPPort,
			"baseUrl":      "http://" + net.JoinHostPort(localIP, strconv.Itoa(r.HTTPPort)) + "/api/v1",
			"capabilities": []string{"INCREMENTAL_BACKUP", "RESUMABLE_UPLOAD", "RANGE_DOWNLOAD"},
		}
		encoded, err := json.Marshal(response)
		if err != nil {
			continue
		}
		_, _ = connection.WriteToUDP(encoded, remote)
	}
}

func (r *Responder) allow(source string) bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := time.Now()
	if previous, found := r.lastResponses[source]; found && now.Sub(previous) < 250*time.Millisecond {
		return false
	}
	r.lastResponses[source] = now
	if len(r.lastResponses) > 1024 {
		for key, seen := range r.lastResponses {
			if now.Sub(seen) > time.Minute {
				delete(r.lastResponses, key)
			}
		}
	}
	return true
}

func routeAddress(remote *net.UDPAddr) string {
	connection, err := net.DialUDP("udp", nil, &net.UDPAddr{IP: remote.IP, Port: 9})
	if err != nil {
		return "127.0.0.1"
	}
	defer connection.Close()
	local, ok := connection.LocalAddr().(*net.UDPAddr)
	if !ok {
		return "127.0.0.1"
	}
	value := local.IP.String()
	if zone := strings.IndexByte(value, '%'); zone >= 0 {
		value = value[:zone]
	}
	return value
}
