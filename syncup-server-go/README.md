# SyncUp Server for Go

This directory is a standalone Go implementation of the SyncUp backend. It does
not depend on or modify the Spring/Gradle project in the parent directory.

The server implements the existing v1 contract:

- stable server identity and SQLite metadata
- idempotent backup runs and incremental manifest planning
- durable, resumable raw uploads with SHA-256 verification
- safe filesystem commits with collision protection
- paginated restore metadata and single-range downloads
- `/actuator/health`, `/actuator/info`, and UDP LAN discovery

Version 1 intentionally has no authentication or TLS. Run it only on a trusted
private LAN; do not publish either port to the internet.

## Requirements

- Go 1.25 or newer
- at least 5 GiB free by default (configurable for development)

The only direct third-party dependency is the pure-Go SQLite driver. No C
compiler is required.

## Run

From this directory:

```bash
go run ./cmd/syncup-server
```

Defaults:

| Setting | Default |
|---|---|
| HTTP | `0.0.0.0:8500` |
| UDP discovery | `0.0.0.0:9999` |
| Storage | `./syncup-data` |
| Preferred segment | 8 MiB |
| Maximum segment | 4 GiB |
| Maximum file | 1 TiB |

For a small local development disk:

```bash
go run ./cmd/syncup-server \
  --server-name="Development Mac" \
  --storage-root=./dev-data \
  --minimum-free-bytes=0
```

Build a binary:

```bash
go build -trimpath -ldflags "-s -w -X main.version=1.0.0" \
  -o ./bin/syncup-server ./cmd/syncup-server
./bin/syncup-server
```

## Configuration

The main settings have command-line flags. Every setting can also be supplied by
environment variable:

| Environment variable | Example |
|---|---|
| `SYNCUP_HTTP_ADDRESS` | `0.0.0.0:8500` |
| `SYNCUP_SERVER_NAME` | `Home Mac` |
| `SYNCUP_STORAGE_ROOT` | `/Volumes/Backup/syncup-data` |
| `SYNCUP_MINIMUM_FREE_BYTES` | `5368709120` |
| `SYNCUP_DISCOVERY_ENABLED` | `true` |
| `SYNCUP_DISCOVERY_PORT` | `9999` |
| `SYNCUP_DISCOVERY_MAX_DATAGRAM_BYTES` | `2048` |
| `SYNCUP_SEGMENT_BYTES` | `8388608` |
| `SYNCUP_MAX_SEGMENT_BYTES` | `4294967296` |
| `SYNCUP_MAX_FILE_BYTES` | `1099511627776` |
| `SYNCUP_MAX_CONCURRENT_PER_DEVICE` | `2` |
| `SYNCUP_MAX_CONCURRENT_TOTAL` | `4` |
| `SYNCUP_MANIFEST_MAX_FILES` | `500` |
| `SYNCUP_MANIFEST_MAX_BODY_BYTES` | `4194304` |
| `SYNCUP_PARTIAL_RETENTION` | `168h` |
| `SYNCUP_SHUTDOWN_TIMEOUT` | `30s` |

Flags override environment variables. Run `go run ./cmd/syncup-server -h` for
the available flags.

## Storage

The storage root is process-locked and contains:

```text
syncup-data/
├── data/<device-name>/<original-name>
├── partial/<transfer-id>.part
├── quarantine/
├── syncup.db
├── server-id
└── syncup.lock
```

SQLite uses WAL mode, foreign keys, a busy timeout, and full synchronous writes.
Upload bytes are fsynced before their offset is acknowledged. An unclean restart
interrupts active runs and reconciles partial-file lengths against durable
database offsets so clients can resume.

## Verify

```bash
go test ./...
go vet ./...
```

The integration test exercises create → plan → segmented upload → checksum
commit → complete → list → ranged restore, plus incremental `PRESENT` planning.
The curl examples in the parent project at `../docs/curls.md` target the same v1
HTTP contract.

## Container

```bash
docker build -t syncup-server-go .
docker run --rm \
  -p 8500:8500/tcp -p 9999:9999/udp \
  -v "$PWD/syncup-data:/data" \
  -e SYNCUP_MINIMUM_FREE_BYTES=0 \
  syncup-server-go
```

UDP discovery from a bridged container depends on the host platform. Native
execution or host networking is more reliable for discovery.
