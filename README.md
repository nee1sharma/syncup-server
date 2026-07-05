# SyncUp Server

Trusted-LAN Spring Boot server for incremental phone backups and restore downloads.
It uses Java 25, Spring Boot 4.1, SQLite/Flyway, raw streaming uploads, and local
filesystem storage. Version 1 intentionally has no authentication or TLS.

## Run

```bash
./gradlew bootRun
```

The defaults listen on HTTP port `8500`, UDP discovery port `9999`, and store
state under `./syncup-data`. Override any setting with Spring command-line
properties, for example:

```bash
./gradlew bootRun --args='--syncup.server.name=Home Mac --syncup.storage.root=/Volumes/Backup/syncup-data'
java -jar build/libs/syncup-server-1.0.0.jar --syncup.storage.root=/Volumes/Backup/syncup-data
```

Do not forward the HTTP or discovery ports, publish them through a tunnel, or run
the service on an untrusted network.

For a user-facing setup and configuration guide, see [docs/user-guide.md](docs/user-guide.md).

## HTTP API v1

All request and response bodies below are JSON unless an endpoint says otherwise.
Validation failures use RFC 9457 Problem Details and include a stable `code`.

### Server

`GET /api/v1/server` returns the stable server identity, API version, app
version, and capabilities.

### Backup run

`POST /api/v1/backups`

```json
{
  "deviceId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "deviceName": "My Phone",
  "idempotencyKey": "client-generated-retry-key"
}
```

`POST /api/v1/backups/{runId}/manifest`

```json
{
  "deviceId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
  "deviceName": "My Phone",
  "files": [
    {
      "clientFileKey": "media-store-id-or-other-stable-key",
      "displayName": "IMG_0001.jpg",
      "relativePath": "DCIM/Camera/IMG_0001.jpg",
      "mediaType": "IMAGE",
      "mimeType": "image/jpeg",
      "sizeBytes": 123456,
      "sha256": "64-lower-or-upper-case-hex-characters",
      "capturedAt": "2026-07-03T12:00:00Z",
      "modifiedAt": "2026-07-03T12:00:00Z"
    }
  ]
}
```

Each plan item is `PRESENT`, `UPLOAD`, or `RESUME`. Upload/resume items contain
`transferId`, `uploadOffset`, and the preferred `segmentBytes`.

### Backup limits and supported files

- Manifest submissions accept up to `500` files per request.
- Manifest JSON bodies are limited to `4 MiB`.
- Each file can be up to `1 TiB`.
- Multiple backup runs can exist, but active upload streams are limited to `2` per device and `4` total across the server.
- Upload segments default to `8 MiB`; the maximum allowed segment size is `4 GiB`.
- Supported logical media types are `IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`, and `OTHER`.
- `displayName` must be a plain filename with no path separators or dot segments.
- `relativePath` must be a safe relative path with no traversal segments.
- `mimeType` must look like a normal media type such as `image/jpeg`.
- `sha256` must be a 64-character hexadecimal digest.

`POST /api/v1/backups/{runId}/complete` and
`POST /api/v1/backups/{runId}/cancel` both accept:

```json
{"deviceId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa","deviceName":"My Phone"}
```

### Upload

`PUT /api/v1/transfers/{transferId}/content` accepts a raw binary request body.
It requires:

- `Content-Length`
- `Upload-Offset`
- `X-SyncUp-Device-Id`
- `X-SyncUp-Device-Name`
- `X-SyncUp-Run-Id`

Backed-up files are stored under `syncup-data/data/<file-id>/<displayName>`, so
the original filename is preserved on disk while `deviceId` remains the
ownership key for metadata and authorization checks.

A successful segment returns `204` with the durable `Upload-Offset` and
`Upload-Complete`. An offset conflict returns `409` and the authoritative
`Upload-Offset`. `GET /api/v1/transfers/{transferId}` with the same device/run
headers reports resumable state.

### Restore

`GET /api/v1/files` supports optional `deviceId`, `mediaType`, `capturedAfter`,
opaque `cursor`, and `limit` parameters.

`GET /api/v1/files/{fileId}/content` returns full content or one HTTP byte range.
Multiple ranges are not supported. Responses include `Accept-Ranges`, `ETag`,
`Content-Length`, and, for partial responses, `Content-Range`.

### Operations

Only `/actuator/health` and `/actuator/info` are exposed. Health details,
environment, metrics, and administrative endpoints are not exposed over HTTP.

## UDP discovery v1

Send this JSON as a UDP datagram to port `9999`:

```json
{
  "type": "SYNCUP_DISCOVER",
  "apiVersion": "v1",
  "requestId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
}
```

The server unicasts a response to the sender:

```json
{
  "type": "SYNCUP_SERVER",
  "requestId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
  "serverId": "cccccccc-cccc-cccc-cccc-cccccccccccc",
  "serverName": "My Laptop",
  "apiVersion": "v1",
  "httpPort": 8500,
  "baseUrl": "http://192.168.1.10:8500/api/v1",
  "capabilities": [
    "INCREMENTAL_BACKUP",
    "RESUMABLE_UPLOAD",
    "RANGE_DOWNLOAD"
  ]
}
```

## Release a build

1. Bump `info.app.version` in `src/main/resources/application.yml` to the next release version, usually the minor component, for example `1.0.0` -> `1.1.0`.
2. Bump `version` in `build.gradle` to keep the runnable jar name aligned with the release version.
3. Create a Git tag that matches the app version, for example `v1.0.0`, and push it to GitHub.
4. GitHub Actions runs `./gradlew clean test bootJar` and uses GitHub CLI to create the release and upload the runnable jar plus a versioned copy of `docs/user-guide.md` as release assets.

Example:

```bash
./gradlew clean test bootJar
java -jar build/libs/syncup-server-1.0.0.jar
```
