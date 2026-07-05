# SyncUp User Guide

This guide summarizes the defaults in `src/main/resources/application.yml` and
shows the common ways to run and configure SyncUp Server.

## Prerequisites

Before running SyncUp Server, make sure you have:

- Java 25 installed for `./gradlew bootRun`
- Java 25 installed if you plan to run the packaged jar with `java -jar`
- A POSIX shell such as `bash` or `zsh`
- Enough free disk space on the storage root volume for backups and the
  configured safety margin
- Network access on a trusted home or private LAN
- `curl` and `jq` if you plan to use the HTTP examples in [docs/curls.md](curls.md)

You do not need to install a separate database server. SyncUp stores metadata
in SQLite inside the configured storage root.

## Start The Server

Run the server locally with:

```bash
./gradlew bootRun
```

The default HTTP API listens on `0.0.0.0:8500` and UDP discovery listens on
port `9999`.

## Default Settings

| Area | Default | Notes |
|---|---|---|
| HTTP bind address | `0.0.0.0` | Listens on all interfaces |
| HTTP port | `8500` | API and restore endpoint port |
| Server name | `hitstudio` | Reported to discovery clients |
| Discovery | `enabled` | UDP discovery is on by default |
| Discovery port | `9999` | UDP port used for server discovery |
| Storage root | `./syncup-data` | Local data directory for SQLite and files |
| Minimum free space | `5 GiB` | Uploads stop before disk space gets too low |
| Partial retention | `7d` | How long abandoned partial uploads are kept |
| Segment size | `8 MiB` | Preferred upload chunk size |
| Max segment size | `4 GiB` | Largest allowed upload request body |
| Max file size | `1 TiB` | Largest single file that can be backed up |
| Concurrent uploads | `2 per device`, `4 total` | Limits simultaneous uploads |
| Manifest batch size | `500` files | Maximum files per manifest request |
| Manifest body size | `4 MiB` | Maximum manifest JSON body size |
| App version | `1.0.0` | Reported by `GET /api/v1/server` |
| API version | `v1` | Stable HTTP and discovery protocol version |

## Change Settings From The Command Line

Spring Boot accepts command-line overrides for any property.

Examples:

```bash
./gradlew bootRun --args='--syncup.server.name=Home-Mac --syncup.storage.root=/Volumes/Backup/syncup-data'
java -jar syncup-server-1.0.0.jar --syncup.storage.root=/Volumes/Backup/syncup-data
```

Common overrides:

```bash
--syncup.server.name=Home-Mac
--syncup.discovery.enabled=false
--syncup.discovery.port=9999
--syncup.storage.root=/absolute/path/to/syncup-data
--server.port=8500
```

## What The Storage Root Contains

Under the storage root, SyncUp creates:

```text
syncup-data/
├── data/
├── partial/
├── quarantine/
├── syncup.db
├── server-id
└── syncup.lock
```

Committed files are stored under `data/<file-id>/<displayName>`. The
`deviceName` stays as metadata and is not used as the on-disk folder name.

## GitHub Releases

Each GitHub release uploads the runnable jar and a versioned copy of this user
guide together. After downloading the jar asset, start it with `java -jar
syncup-server-1.0.0.jar`. The release job uses `gh release create` to publish
those assets.

## Recommended Setup

- Run the server on a trusted home or private LAN.
- Keep the HTTP and discovery ports private.
- Use an absolute path for `syncup.storage.root` when you move data to another disk.
- Keep enough free disk space above the configured safety margin before large backups.

## Backing Up Files

1. The client discovers the server through UDP discovery or by using the HTTP base URL directly.
2. The client asks `GET /api/v1/server` for the server identity and version.
3. The client creates a backup run and submits a manifest.
4. The server returns which files are already present, which need upload, and which can resume.
5. The client uploads file segments until the run is completed.

## Restoring Files

1. List backed-up files with `GET /api/v1/files`.
2. Pick a `fileId`.
3. Download the file content with `GET /api/v1/files/{fileId}/content`.

## When To Change The Defaults

- Change `syncup.storage.root` when you want the data on another disk or folder.
- Change `syncup.server.name` when you want clients to see a different server name.
- Disable discovery with `syncup.discovery.enabled=false` if you always connect by HTTP base URL.
- Increase the storage root only if you also have enough free disk space for the safety margin.
