# SyncUp API curl examples

These examples cover every HTTP endpoint exposed by the service. The server
defaults to `http://localhost:8500` and intentionally has no authentication in
trusted-LAN version 1.

## Setup

Set these values before running the requests:

```bash
export BASE_URL="http://localhost:8500"
export DEVICE_ID="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
export DEVICE_NAME="My Phone"
export RUN_ID="replace-after-creating-a-backup"
export TRANSFER_ID="replace-from-the-manifest-plan"
export FILE_ID="replace-from-the-file-list"
export FILE_PATH="/absolute/path/to/file.jpg"
export FILE_SIZE="$(wc -c < "$FILE_PATH" | tr -d ' ')"
export FILE_SHA256="$(shasum -a 256 "$FILE_PATH" | awk '{print $1}')"
```

Commands that extract IDs from responses use
[`jq`](https://jqlang.github.io/jq/).

## Server identity

### `GET /api/v1/server`

```bash
curl --fail-with-body \
  --request GET \
  --header "Accept: application/json" \
  "$BASE_URL/api/v1/server"
```

The JSON response includes `serverId`, `serverName`, `apiVersion`,
`appVersion`, and `capabilities`.

## Backup runs

### `POST /api/v1/backups`

Creates a run. Reusing the same `deviceId` and `idempotencyKey` returns the
existing run.

```bash
curl --fail-with-body \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Accept: application/json" \
  --data '{
    "deviceId": "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
    "deviceName": "My Phone",
    "idempotencyKey": "backup-2026-07-04-001"
  }' \
  "$BASE_URL/api/v1/backups"
```

Create a run and retain its server-issued ID:

```bash
CREATE_RESPONSE="$(
  curl --fail-with-body --silent \
    --request POST \
    --header "Content-Type: application/json" \
    --data "{
      \"deviceId\": \"$DEVICE_ID\",
      \"deviceName\": \"My Phone\",
      \"idempotencyKey\": \"backup-$(date +%s)\"
    }" \
    "$BASE_URL/api/v1/backups"
)"

export RUN_ID="$(printf '%s' "$CREATE_RESPONSE" | jq -r '.runId')"
printf '%s\n' "$CREATE_RESPONSE" | jq
```

### `POST /api/v1/backups/{runId}/manifest`

Supported logical media types are `IMAGE`, `VIDEO`, `AUDIO`, `DOCUMENT`, and
`OTHER`. The response disposition is `UPLOAD`, `RESUME`, or `PRESENT`.

```bash
MANIFEST_RESPONSE="$(
  curl --fail-with-body --silent \
    --request POST \
    --header "Content-Type: application/json" \
    --header "Accept: application/json" \
    --data "$(
      jq -n \
        --arg deviceId "$DEVICE_ID" \
        --arg deviceName "$DEVICE_NAME" \
        --arg displayName "$(basename "$FILE_PATH")" \
        --arg sha256 "$FILE_SHA256" \
        --argjson sizeBytes "$FILE_SIZE" \
        '{
          deviceId: $deviceId,
          deviceName: $deviceName,
          files: [{
            clientFileKey: "media-store-12345",
            displayName: $displayName,
            relativePath: ("DCIM/Camera/" + $displayName),
            mediaType: "IMAGE",
            mimeType: "image/jpeg",
            sizeBytes: $sizeBytes,
            sha256: $sha256,
            capturedAt: "2026-07-04T10:00:00Z",
            modifiedAt: "2026-07-04T10:00:00Z"
          }]
        }'
    )" \
    "$BASE_URL/api/v1/backups/$RUN_ID/manifest"
)"

export TRANSFER_ID="$(printf '%s' "$MANIFEST_RESPONSE" | jq -r '.files[0].transferId')"
export UPLOAD_OFFSET="$(printf '%s' "$MANIFEST_RESPONSE" | jq -r '.files[0].uploadOffset')"
printf '%s\n' "$MANIFEST_RESPONSE" | jq
```

When the disposition is `PRESENT`, `transferId` is `null` and no upload is
required.

### `POST /api/v1/backups/{runId}/complete`

All transfers belonging to the run must be committed first.

```bash
curl --fail-with-body \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Accept: application/json" \
  --data "{\"deviceId\":\"$DEVICE_ID\",\"deviceName\":\"$DEVICE_NAME\"}" \
  "$BASE_URL/api/v1/backups/$RUN_ID/complete"
```

### `POST /api/v1/backups/{runId}/cancel`

Use this on an active run. A completed run cannot be cancelled.

```bash
curl --fail-with-body \
  --request POST \
  --header "Content-Type: application/json" \
  --header "Accept: application/json" \
  --data "{\"deviceId\":\"$DEVICE_ID\",\"deviceName\":\"$DEVICE_NAME\"}" \
  "$BASE_URL/api/v1/backups/$RUN_ID/cancel"
```

## Transfers

### `GET /api/v1/transfers/{transferId}`

Returns transfer state and the authoritative durable upload offset.

```bash
curl --fail-with-body \
  --request GET \
  --header "Accept: application/json" \
  --header "X-SyncUp-Device-Id: $DEVICE_ID" \
  --header "X-SyncUp-Device-Name: $DEVICE_NAME" \
  --header "X-SyncUp-Run-Id: $RUN_ID" \
  "$BASE_URL/api/v1/transfers/$TRANSFER_ID"
```

Read the current offset into a shell variable:

```bash
export UPLOAD_OFFSET="$(
  curl --fail-with-body --silent \
    --header "X-SyncUp-Device-Id: $DEVICE_ID" \
    --header "X-SyncUp-Device-Name: $DEVICE_NAME" \
    --header "X-SyncUp-Run-Id: $RUN_ID" \
    "$BASE_URL/api/v1/transfers/$TRANSFER_ID" |
  jq -r '.uploadOffset'
)"
```

### `PUT /api/v1/transfers/{transferId}/content`

Upload an entire file when the plan offset is zero:

```bash
curl --fail-with-body --include \
  --request PUT \
  --header "Content-Type: application/octet-stream" \
  --header "Content-Length: $FILE_SIZE" \
  --header "Upload-Offset: 0" \
  --header "X-SyncUp-Device-Id: $DEVICE_ID" \
  --header "X-SyncUp-Device-Name: $DEVICE_NAME" \
  --header "X-SyncUp-Run-Id: $RUN_ID" \
  --data-binary "@$FILE_PATH" \
  "$BASE_URL/api/v1/transfers/$TRANSFER_ID/content"
```

Successful segments return `204 No Content`, a durable `Upload-Offset`, and
`Upload-Complete`. Each segment must be no larger than the configured
`syncup.transfer.max-segment-bytes`.

To resume from `UPLOAD_OFFSET`, create a file containing only the remaining
bytes so curl can send an exact `Content-Length`:

```bash
export SEGMENT_FILE="/tmp/syncup-upload-segment.bin"
tail -c "+$((UPLOAD_OFFSET + 1))" "$FILE_PATH" > "$SEGMENT_FILE"
export SEGMENT_SIZE="$(wc -c < "$SEGMENT_FILE" | tr -d ' ')"

curl --fail-with-body --include \
  --request PUT \
  --header "Content-Type: application/octet-stream" \
  --header "Content-Length: $SEGMENT_SIZE" \
  --header "Upload-Offset: $UPLOAD_OFFSET" \
  --header "X-SyncUp-Device-Id: $DEVICE_ID" \
  --header "X-SyncUp-Device-Name: $DEVICE_NAME" \
  --header "X-SyncUp-Run-Id: $RUN_ID" \
  --data-binary "@$SEGMENT_FILE" \
  "$BASE_URL/api/v1/transfers/$TRANSFER_ID/content"
```

For files larger than the maximum segment size, split the source into segments
and repeat the request with the latest returned `Upload-Offset`.

## Restore metadata

### `GET /api/v1/files`

List the newest committed files with default pagination:

```bash
curl --fail-with-body \
  --request GET \
  --header "Accept: application/json" \
  "$BASE_URL/api/v1/files"
```

Filter and set the page size:

```bash
curl --fail-with-body --get \
  --header "Accept: application/json" \
  --data-urlencode "deviceId=$DEVICE_ID" \
  --data-urlencode "mediaType=IMAGE" \
  --data-urlencode "capturedAfter=2026-01-01T00:00:00Z" \
  --data-urlencode "limit=50" \
  "$BASE_URL/api/v1/files"
```

Request the next page using the opaque `nextCursor` from the previous response:

```bash
export NEXT_CURSOR="paste-nextCursor-here"

curl --fail-with-body --get \
  --header "Accept: application/json" \
  --data-urlencode "deviceId=$DEVICE_ID" \
  --data-urlencode "limit=50" \
  --data-urlencode "cursor=$NEXT_CURSOR" \
  "$BASE_URL/api/v1/files"
```

Capture the first listed file ID:

```bash
export FILE_ID="$(
  curl --fail-with-body --silent \
    --get \
    --data-urlencode "deviceId=$DEVICE_ID" \
    --data-urlencode "limit=1" \
    "$BASE_URL/api/v1/files" |
  jq -r '.files[0].fileId'
)"
```

## Restore content

### `GET /api/v1/files/{fileId}/content`

Download the complete file:

```bash
curl --fail-with-body \
  --request GET \
  --output "./restored-file" \
  "$BASE_URL/api/v1/files/$FILE_ID/content"
```

Download a single byte range:

```bash
curl --fail-with-body \
  --request GET \
  --header "Range: bytes=1048576-" \
  --dump-header - \
  --output "./restored-file.part" \
  "$BASE_URL/api/v1/files/$FILE_ID/content"
```

Suffix ranges are also supported:

```bash
curl --fail-with-body --include \
  --request GET \
  --header "Range: bytes=-1024" \
  "$BASE_URL/api/v1/files/$FILE_ID/content"
```

Version 1 accepts at most one range per request.

## Actuator

Only the actuator root, health, and application information are reachable.
Detailed health, metrics, environment, and administrative endpoints remain
unexposed.

### `GET /actuator`

```bash
curl --fail-with-body \
  --request GET \
  --header "Accept: application/json" \
  "$BASE_URL/actuator"
```

### `GET /actuator/health`

```bash
curl --fail-with-body \
  --request GET \
  --header "Accept: application/json" \
  "$BASE_URL/actuator/health"
```

### `GET /actuator/info`

```bash
curl --fail-with-body \
  --request GET \
  --header "Accept: application/json" \
  "$BASE_URL/actuator/info"
```

### `GET /actuator/health/liveness`

```bash
curl --fail-with-body \
  --request GET \
  --header "Accept: application/json" \
  "$BASE_URL/actuator/health/liveness"
```

### `GET /actuator/health/readiness`

```bash
curl --fail-with-body \
  --request GET \
  --header "Accept: application/json" \
  "$BASE_URL/actuator/health/readiness"
```

## UDP discovery

UDP discovery is not an HTTP API, so curl cannot call it. A comparable request
can be sent with netcat:

```bash
printf '%s' '{
  "type": "SYNCUP_DISCOVER",
  "apiVersion": "v1",
  "requestId": "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
}' | nc -u -w 2 255.255.255.255 9999
```
