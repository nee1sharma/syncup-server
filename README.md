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
```

Do not forward the HTTP or discovery ports, publish them through a tunnel, or run
the service on an untrusted network.

## HTTP API v1

All request and response bodies below are JSON unless an endpoint says otherwise.
Validation failures use RFC 9457 Problem Details and include a stable `code`.

### Server

`GET /api/v1/server` returns the stable server identity, API version, and
capabilities.

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

`POST /api/v1/backups/{runId}/complete` and
`POST /api/v1/backups/{runId}/cancel` both accept:

```json
{"deviceId":"aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"}
```

### Upload

`PUT /api/v1/transfers/{transferId}/content` accepts a raw binary request body.
It requires:

- `Content-Length`
- `Upload-Offset`
- `X-SyncUp-Device-Id`
- `X-SyncUp-Run-Id`

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

## Verify and package

```bash
./gradlew clean test
./gradlew bootJar
java -jar build/libs/syncup-server-0.0.1-SNAPSHOT.jar
```
