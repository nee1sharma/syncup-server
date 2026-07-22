package api

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"

	"syncup-server/internal/config"
	storagepkg "syncup-server/internal/storage"
	"syncup-server/internal/store"
)

const testDeviceID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"

func TestBackupUploadRestoreLifecycle(t *testing.T) {
	client, baseURL, closeServer := newTestServer(t)
	defer closeServer()

	run := createRun(t, client, baseURL, "run-1")
	reusedRun := createRun(t, client, baseURL, "run-1")
	if reusedRun.RunID != run.RunID {
		t.Fatalf("idempotent create returned a different run: %s != %s", reusedRun.RunID, run.RunID)
	}
	payload := []byte("hello syncup")
	digest := sha256.Sum256(payload)
	manifest := doJSON(t, client, http.MethodPost, baseURL+"/api/v1/backups/"+run.RunID+"/manifest", map[string]any{
		"deviceId": testDeviceID, "deviceName": "My Phone", "files": []map[string]any{{
			"clientFileKey": "media-1", "displayName": "hello.txt", "relativePath": "Documents/hello.txt",
			"mediaType": "DOCUMENT", "mimeType": "text/plain", "sizeBytes": len(payload),
			"sha256": hex.EncodeToString(digest[:]),
		}}}, http.StatusOK)
	var plan struct {
		Files []struct {
			Disposition, TransferID string
			UploadOffset            int64
		} `json:"files"`
	}
	decodeResponse(t, manifest, &plan)
	if len(plan.Files) != 1 || plan.Files[0].Disposition != "UPLOAD" || plan.Files[0].TransferID == "" {
		t.Fatalf("unexpected plan: %+v", plan)
	}

	first := upload(t, client, baseURL, run.RunID, plan.Files[0].TransferID, 0, payload[:5])
	if first.Header.Get("Upload-Offset") != "5" || first.Header.Get("Upload-Complete") != "false" {
		t.Fatalf("unexpected first upload headers: %v", first.Header)
	}
	_ = first.Body.Close()
	conflictRequest, _ := http.NewRequest(http.MethodPut, baseURL+"/api/v1/transfers/"+plan.Files[0].TransferID+"/content", bytes.NewReader([]byte("x")))
	conflictRequest.Header.Set(deviceIDHeader, testDeviceID)
	conflictRequest.Header.Set(deviceNameHeader, "My Phone")
	conflictRequest.Header.Set(runIDHeader, run.RunID)
	conflictRequest.Header.Set(uploadOffsetHeader, "0")
	conflictResponse, err := client.Do(conflictRequest)
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, conflictResponse.Body)
	_ = conflictResponse.Body.Close()
	if conflictResponse.StatusCode != http.StatusConflict || conflictResponse.Header.Get(uploadOffsetHeader) != "5" {
		t.Fatalf("offset conflict did not return the durable offset: status=%d headers=%v", conflictResponse.StatusCode, conflictResponse.Header)
	}
	second := upload(t, client, baseURL, run.RunID, plan.Files[0].TransferID, 5, payload[5:])
	if second.Header.Get("Upload-Offset") != "12" || second.Header.Get("Upload-Complete") != "true" {
		t.Fatalf("unexpected final upload headers: %v", second.Header)
	}
	_ = second.Body.Close()

	completed := doJSON(t, client, http.MethodPost, baseURL+"/api/v1/backups/"+run.RunID+"/complete",
		map[string]any{"deviceId": testDeviceID, "deviceName": "My Phone"}, http.StatusOK)
	var completedRun runPayload
	decodeResponse(t, completed, &completedRun)
	if completedRun.State != "COMPLETED" || completedRun.FileCount != 1 || completedRun.ByteCount != int64(len(payload)) {
		t.Fatalf("unexpected completed run: %+v", completedRun)
	}

	response, err := client.Get(baseURL + "/api/v1/files?deviceId=" + testDeviceID + "&limit=1")
	if err != nil {
		t.Fatal(err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		t.Fatalf("list status = %d", response.StatusCode)
	}
	var page struct {
		Files []struct {
			FileID, OriginalName, SHA256 string
			SizeBytes                    int64
		} `json:"files"`
		NextCursor *string `json:"nextCursor"`
	}
	decodeResponse(t, response, &page)
	if len(page.Files) != 1 || page.Files[0].OriginalName != "hello.txt" || page.Files[0].SHA256 != hex.EncodeToString(digest[:]) {
		t.Fatalf("unexpected file page: %+v", page)
	}

	rangeRequest, _ := http.NewRequest(http.MethodGet, baseURL+"/api/v1/files/"+page.Files[0].FileID+"/content", nil)
	rangeRequest.Header.Set("Range", "bytes=6-")
	rangeResponse, err := client.Do(rangeRequest)
	if err != nil {
		t.Fatal(err)
	}
	rangeBody, _ := io.ReadAll(rangeResponse.Body)
	_ = rangeResponse.Body.Close()
	if rangeResponse.StatusCode != http.StatusPartialContent || string(rangeBody) != "syncup" || rangeResponse.Header.Get("Content-Range") != "bytes 6-11/12" {
		t.Fatalf("unexpected range response: status=%d headers=%v body=%q", rangeResponse.StatusCode, rangeResponse.Header, rangeBody)
	}

	secondRun := createRun(t, client, baseURL, "run-2")
	presentResponse := doJSON(t, client, http.MethodPost, baseURL+"/api/v1/backups/"+secondRun.RunID+"/manifest", map[string]any{
		"deviceId": testDeviceID, "deviceName": "My Phone", "files": []map[string]any{{
			"clientFileKey": "media-2", "displayName": "renamed.txt", "relativePath": "Documents/renamed.txt",
			"mediaType": "DOCUMENT", "mimeType": "text/plain", "sizeBytes": len(payload), "sha256": hex.EncodeToString(digest[:]),
		}}}, http.StatusOK)
	decodeResponse(t, presentResponse, &plan)
	if plan.Files[0].Disposition != "PRESENT" || plan.Files[0].UploadOffset != int64(len(payload)) {
		t.Fatalf("expected PRESENT plan, got %+v", plan.Files[0])
	}
}

func TestManifestRejectsTraversal(t *testing.T) {
	client, baseURL, closeServer := newTestServer(t)
	defer closeServer()
	run := createRun(t, client, baseURL, "bad-path")
	response := doJSON(t, client, http.MethodPost, baseURL+"/api/v1/backups/"+run.RunID+"/manifest", map[string]any{
		"deviceId": testDeviceID, "deviceName": "My Phone", "files": []map[string]any{{
			"clientFileKey": "bad", "displayName": "file.txt", "relativePath": "../file.txt",
			"mediaType": "DOCUMENT", "mimeType": "text/plain", "sizeBytes": 0, "sha256": strings.Repeat("0", 64),
		}}}, http.StatusBadRequest)
	var body map[string]any
	decodeResponse(t, response, &body)
	if body["code"] != "INVALID_RELATIVE_PATH" {
		t.Fatalf("unexpected error: %v", body)
	}
}

type runPayload struct {
	RunID     string `json:"runId"`
	State     string `json:"state"`
	FileCount int64  `json:"fileCount"`
	ByteCount int64  `json:"byteCount"`
}

func newTestServer(t *testing.T) (*http.Client, string, func()) {
	t.Helper()
	storage, err := storagepkg.Open(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	database, err := store.Open(filepath.Join(storage.Root, "syncup.db"))
	if err != nil {
		storage.Close()
		t.Fatal(err)
	}
	identity, err := database.Identity(t.Context(), "Test Server")
	if err != nil {
		database.Close()
		storage.Close()
		t.Fatal(err)
	}
	cfg := config.Config{ServerName: "Test Server", StorageRoot: storage.Root, MinimumFreeBytes: 0,
		SegmentBytes: 4, MaxSegmentBytes: 1024 * 1024, MaxFileBytes: 1024 * 1024,
		MaxConcurrentPerDevice: 2, MaxConcurrentTotal: 4, ManifestMaxFiles: 500,
		ManifestMaxBodyBytes: 4 * 1024 * 1024, PartialRetention: time.Hour, AppVersion: "test"}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	server := httptest.NewServer(New(cfg, storage, database, identity, logger).Handler())
	return server.Client(), server.URL, func() { server.Close(); _ = database.Close(); _ = storage.Close() }
}

func createRun(t *testing.T, client *http.Client, baseURL, key string) runPayload {
	t.Helper()
	response := doJSON(t, client, http.MethodPost, baseURL+"/api/v1/backups", map[string]any{
		"deviceId": testDeviceID, "deviceName": "My Phone", "idempotencyKey": key,
	}, http.StatusCreated)
	var run runPayload
	decodeResponse(t, response, &run)
	if run.RunID == "" || run.State != "PREPARING" {
		t.Fatalf("unexpected run: %+v", run)
	}
	return run
}

func upload(t *testing.T, client *http.Client, baseURL, runID, transferID string, offset int64, body []byte) *http.Response {
	t.Helper()
	request, _ := http.NewRequest(http.MethodPut, baseURL+"/api/v1/transfers/"+transferID+"/content", bytes.NewReader(body))
	request.Header.Set(deviceIDHeader, testDeviceID)
	request.Header.Set(deviceNameHeader, "My Phone")
	request.Header.Set(runIDHeader, runID)
	request.Header.Set(uploadOffsetHeader, strconv.FormatInt(offset, 10))
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != http.StatusNoContent {
		payload, _ := io.ReadAll(response.Body)
		t.Fatalf("upload status=%d body=%s", response.StatusCode, payload)
	}
	return response
}

func doJSON(t *testing.T, client *http.Client, method, url string, body any, expected int) *http.Response {
	t.Helper()
	encoded := mustJSON(body)
	request, _ := http.NewRequest(method, url, bytes.NewReader(encoded))
	request.Header.Set("Content-Type", "application/json")
	response, err := client.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	if response.StatusCode != expected {
		payload, _ := io.ReadAll(response.Body)
		response.Body.Close()
		t.Fatalf("%s %s: status=%d body=%s", method, url, response.StatusCode, payload)
	}
	return response
}

func decodeResponse(t *testing.T, response *http.Response, target any) {
	t.Helper()
	defer response.Body.Close()
	if err := json.NewDecoder(response.Body).Decode(target); err != nil {
		t.Fatal(err)
	}
}

func mustJSON(value any) []byte {
	encoded, err := json.Marshal(value)
	if err != nil {
		panic(err)
	}
	return encoded
}
