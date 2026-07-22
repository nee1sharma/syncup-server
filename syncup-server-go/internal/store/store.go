package store

import (
	"context"
	"database/sql"
	_ "embed"
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"

	_ "modernc.org/sqlite"
	"syncup-server/internal/model"
)

//go:embed schema.sql
var schema string

var (
	ErrNotFound      = errors.New("not found")
	ErrNotOwned      = errors.New("not owned")
	ErrTerminalRun   = errors.New("run is terminal")
	ErrDuplicateKey  = errors.New("duplicate client file key")
	ErrIncompleteRun = errors.New("run has incomplete transfers")
)

type Store struct{ db *sql.DB }

func Open(path string) (*Store, error) {
	query := make(url.Values)
	query.Add("_pragma", "foreign_keys(ON)")
	query.Add("_pragma", "journal_mode(WAL)")
	query.Add("_pragma", "busy_timeout(5000)")
	query.Add("_pragma", "synchronous(FULL)")
	dsn := (&url.URL{Scheme: "file", Path: path, RawQuery: query.Encode()}).String()
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("open SQLite: %w", err)
	}
	db.SetMaxOpenConns(4)
	db.SetMaxIdleConns(4)
	for _, pragma := range []string{
		"PRAGMA foreign_keys = ON",
		"PRAGMA journal_mode = WAL",
		"PRAGMA busy_timeout = 5000",
		"PRAGMA synchronous = FULL",
	} {
		if _, err := db.Exec(pragma); err != nil {
			_ = db.Close()
			return nil, fmt.Errorf("configure SQLite: %w", err)
		}
	}
	if _, err := db.Exec(schema); err != nil {
		_ = db.Close()
		return nil, fmt.Errorf("migrate SQLite: %w", err)
	}
	return &Store{db: db}, nil
}

func (s *Store) Close() error                   { return s.db.Close() }
func (s *Store) Ping(ctx context.Context) error { return s.db.PingContext(ctx) }

func (s *Store) Identity(ctx context.Context, serverName string) (model.ServerIdentity, error) {
	var identity model.ServerIdentity
	var created string
	err := s.db.QueryRowContext(ctx, "SELECT server_id, server_name, created_at FROM server_identity LIMIT 1").
		Scan(&identity.ServerID, &identity.ServerName, &created)
	if err == nil {
		identity.CreatedAt, err = time.Parse(time.RFC3339Nano, created)
		return identity, err
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return identity, err
	}
	id, err := model.NewUUID()
	if err != nil {
		return identity, err
	}
	identity = model.ServerIdentity{ServerID: id, ServerName: strings.TrimSpace(serverName), CreatedAt: time.Now().UTC()}
	_, err = s.db.ExecContext(ctx,
		"INSERT INTO server_identity(server_id, server_name, created_at) VALUES (?, ?, ?)",
		identity.ServerID, identity.ServerName, formatTime(identity.CreatedAt))
	if err != nil {
		return s.Identity(ctx, serverName)
	}
	return identity, nil
}

func (s *Store) UpsertDevice(ctx context.Context, id, name string, now time.Time) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO devices(device_id, device_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?)
		ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name, last_seen_at=excluded.last_seen_at`,
		id, name, formatTime(now), formatTime(now))
	return err
}

func (s *Store) CreateOrGetRun(ctx context.Context, deviceID, deviceName, key string, now time.Time) (model.BackupRun, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return model.BackupRun{}, err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `
		INSERT INTO devices(device_id, device_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?)
		ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name, last_seen_at=excluded.last_seen_at`,
		deviceID, deviceName, formatTime(now), formatTime(now)); err != nil {
		return model.BackupRun{}, err
	}
	id, err := model.NewUUID()
	if err != nil {
		return model.BackupRun{}, err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO backup_runs(
		run_id, device_id, idempotency_key, state, started_at, file_count, byte_count)
		VALUES (?, ?, ?, 'PREPARING', ?, 0, 0)
		ON CONFLICT(device_id, idempotency_key) DO NOTHING`, id, deviceID, key, formatTime(now))
	if err != nil {
		return model.BackupRun{}, err
	}
	run, err := queryRun(ctx, tx, "WHERE device_id=? AND idempotency_key=?", deviceID, key)
	if err != nil {
		return model.BackupRun{}, err
	}
	return run, tx.Commit()
}

func (s *Store) Run(ctx context.Context, runID string) (model.BackupRun, error) {
	return queryRun(ctx, s.db, "WHERE run_id=?", runID)
}

func (s *Store) HasCommittedIdentity(ctx context.Context, deviceID, sha string, size int64) (bool, error) {
	var count int
	err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM files WHERE device_id=? AND sha256=?
		AND size_bytes=? AND status='COMMITTED'`, deviceID, sha, size).Scan(&count)
	return count != 0, err
}

type rowQueryer interface {
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

func queryRun(ctx context.Context, q rowQueryer, suffix string, args ...any) (model.BackupRun, error) {
	var run model.BackupRun
	var started string
	var completed sql.NullString
	err := q.QueryRowContext(ctx, `SELECT run_id, device_id, idempotency_key, state, started_at,
		completed_at, file_count, byte_count FROM backup_runs `+suffix, args...).Scan(
		&run.RunID, &run.DeviceID, &run.IdempotencyKey, &run.State, &started,
		&completed, &run.FileCount, &run.ByteCount)
	if errors.Is(err, sql.ErrNoRows) {
		return run, ErrNotFound
	}
	if err != nil {
		return run, err
	}
	run.StartedAt, err = time.Parse(time.RFC3339Nano, started)
	if err != nil {
		return run, err
	}
	run.CompletedAt, err = parseNullTime(completed)
	return run, err
}

func (s *Store) PlanManifest(ctx context.Context, runID, deviceID, deviceName string,
	files []model.ManifestFile, segmentBytes int64, retention time.Duration, now time.Time) ([]model.PlanItem, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, `
		INSERT INTO devices(device_id, device_name, first_seen_at, last_seen_at) VALUES (?, ?, ?, ?)
		ON CONFLICT(device_id) DO UPDATE SET device_name=excluded.device_name, last_seen_at=excluded.last_seen_at`,
		deviceID, deviceName, formatTime(now), formatTime(now)); err != nil {
		return nil, err
	}
	run, err := queryRun(ctx, tx, "WHERE run_id=?", runID)
	if err != nil {
		return nil, err
	}
	if run.DeviceID != deviceID {
		return nil, ErrNotOwned
	}
	if run.State == "COMPLETED" || run.State == "CANCELLED" || run.State == "FAILED" {
		return nil, ErrTerminalRun
	}
	plan := make([]model.PlanItem, 0, len(files))
	for _, file := range files {
		var count int
		if err := tx.QueryRowContext(ctx, "SELECT COUNT(*) FROM manifest_entries WHERE run_id=? AND client_file_key=?", runID, file.ClientFileKey).Scan(&count); err != nil {
			return nil, err
		}
		if count != 0 {
			return nil, ErrDuplicateKey
		}
		var fileID string
		err := tx.QueryRowContext(ctx, `SELECT file_id FROM files WHERE device_id=? AND sha256=?
			AND size_bytes=? AND status='COMMITTED' LIMIT 1`, deviceID, file.SHA256, file.SizeBytes).Scan(&fileID)
		disposition := "PRESENT"
		var result model.PlanItem
		if err == nil {
			result = model.PlanItem{ClientFileKey: file.ClientFileKey, Disposition: disposition, FileID: &fileID,
				UploadOffset: file.SizeBytes, SegmentBytes: segmentBytes}
		} else if errors.Is(err, sql.ErrNoRows) {
			disposition = "UPLOAD"
			transferID, idErr := model.NewUUID()
			if idErr != nil {
				return nil, idErr
			}
			partial := "partial/" + transferID + ".part"
			_, err = tx.ExecContext(ctx, `INSERT INTO transfers(transfer_id, run_id, device_id,
				client_file_key, partial_path, expected_size, expected_sha256, accepted_offset,
				state, last_activity_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, 0, 'PENDING', ?, ?)`,
				transferID, runID, deviceID, file.ClientFileKey, partial, file.SizeBytes, file.SHA256,
				formatTime(now), formatTime(now.Add(retention)))
			if err != nil {
				return nil, err
			}
			result = model.PlanItem{ClientFileKey: file.ClientFileKey, Disposition: disposition,
				TransferID: &transferID, UploadOffset: 0, SegmentBytes: segmentBytes}
		} else {
			return nil, err
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO manifest_entries(run_id, client_file_key,
			display_name, relative_path, media_type, mime_type, size_bytes, sha256,
			captured_at, modified_at, disposition) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
			runID, file.ClientFileKey, file.DisplayName, file.RelativePath, file.MediaType,
			file.MIMEType, file.SizeBytes, file.SHA256, optionalTime(file.CapturedAt),
			optionalTime(file.ModifiedAt), disposition)
		if err != nil {
			return nil, err
		}
		plan = append(plan, result)
	}
	if _, err := tx.ExecContext(ctx, "UPDATE backup_runs SET state='PLANNED' WHERE run_id=?", runID); err != nil {
		return nil, err
	}
	return plan, tx.Commit()
}

func (s *Store) Transfer(ctx context.Context, id string) (model.Transfer, error) {
	var transfer model.Transfer
	var activity, expires string
	var staged sql.NullString
	err := s.db.QueryRowContext(ctx, `SELECT transfer_id, run_id, device_id, client_file_key,
		partial_path, expected_size, expected_sha256, accepted_offset, state,
		last_activity_at, expires_at, staged_file_id FROM transfers WHERE transfer_id=?`, id).Scan(
		&transfer.TransferID, &transfer.RunID, &transfer.DeviceID, &transfer.ClientFileKey,
		&transfer.PartialPath, &transfer.ExpectedSize, &transfer.ExpectedSHA, &transfer.Accepted,
		&transfer.State, &activity, &expires, &staged)
	if errors.Is(err, sql.ErrNoRows) {
		return transfer, ErrNotFound
	}
	if err != nil {
		return transfer, err
	}
	transfer.LastActivity, err = time.Parse(time.RFC3339Nano, activity)
	if err != nil {
		return transfer, err
	}
	transfer.ExpiresAt, err = time.Parse(time.RFC3339Nano, expires)
	if err != nil {
		return transfer, err
	}
	if staged.Valid {
		transfer.StagedFileID = &staged.String
	}
	return transfer, nil
}

func (s *Store) UpdateTransferOffset(ctx context.Context, id string, offset int64, state string, now, expires time.Time) error {
	_, err := s.db.ExecContext(ctx, `UPDATE transfers SET accepted_offset=?, state=?,
		last_activity_at=?, expires_at=? WHERE transfer_id=?`, offset, state, formatTime(now), formatTime(expires), id)
	return err
}

func (s *Store) UpdateRunState(ctx context.Context, id, state string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE backup_runs SET state=? WHERE run_id=?", state, id)
	return err
}

func (s *Store) MarkTransferRejected(ctx context.Context, id string, now time.Time) error {
	_, err := s.db.ExecContext(ctx, "UPDATE transfers SET state='REJECTED', last_activity_at=? WHERE transfer_id=?", formatTime(now), id)
	return err
}

func (s *Store) CommitExistingIdentity(ctx context.Context, deviceID, sha string, size int64,
	transferID string, now time.Time) (bool, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var fileID string
	err = tx.QueryRowContext(ctx, `SELECT file_id FROM files WHERE device_id=? AND sha256=?
		AND size_bytes=? AND status='COMMITTED' LIMIT 1`, deviceID, sha, size).Scan(&fileID)
	if errors.Is(err, sql.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	_, err = tx.ExecContext(ctx, `UPDATE transfers SET state='COMMITTED', staged_file_id=?,
		accepted_offset=expected_size, last_activity_at=? WHERE transfer_id=?`, fileID, formatTime(now), transferID)
	if err != nil {
		return false, err
	}
	return true, tx.Commit()
}

func (s *Store) ManifestEntry(ctx context.Context, runID, key string) (model.ManifestEntry, error) {
	var entry model.ManifestEntry
	var relative, captured, modified sql.NullString
	err := s.db.QueryRowContext(ctx, `SELECT run_id, client_file_key, display_name, relative_path,
		media_type, mime_type, size_bytes, sha256, captured_at, modified_at, disposition
		FROM manifest_entries WHERE run_id=? AND client_file_key=?`, runID, key).Scan(
		&entry.RunID, &entry.ClientFileKey, &entry.DisplayName, &relative, &entry.MediaType,
		&entry.MIMEType, &entry.SizeBytes, &entry.SHA256, &captured, &modified, &entry.Disposition)
	if errors.Is(err, sql.ErrNoRows) {
		return entry, ErrNotFound
	}
	if err != nil {
		return entry, err
	}
	if relative.Valid {
		entry.RelativePath = &relative.String
	}
	entry.CapturedAt, err = parseNullTime(captured)
	if err != nil {
		return entry, err
	}
	entry.ModifiedAt, err = parseNullTime(modified)
	return entry, err
}

func (s *Store) PrepareFile(ctx context.Context, file model.StoredFile, transferID string) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "DELETE FROM files WHERE stored_path=? AND status <> 'COMMITTED'", file.StoredPath); err != nil {
		return err
	}
	_, err = tx.ExecContext(ctx, `INSERT INTO files(file_id, device_id, client_file_key,
		original_name, original_relative_path, media_type, mime_type, size_bytes, sha256,
		captured_at, modified_at, stored_path, backed_up_at, status)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUARANTINED')`,
		file.FileID, file.DeviceID, file.ClientFileKey, file.OriginalName, file.OriginalRelativePath,
		file.MediaType, file.MIMEType, file.SizeBytes, file.SHA256, optionalTime(file.CapturedAt),
		optionalTime(file.ModifiedAt), file.StoredPath, formatTime(file.BackedUpAt))
	if err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "UPDATE transfers SET state='VERIFYING', staged_file_id=? WHERE transfer_id=?", file.FileID, transferID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) FinalizeFile(ctx context.Context, fileID, transferID string, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx, "UPDATE files SET status='COMMITTED' WHERE file_id=?", fileID); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE transfers SET state='COMMITTED', accepted_offset=expected_size,
		last_activity_at=? WHERE transfer_id=?`, formatTime(now), transferID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) AbortPreparedFile(ctx context.Context, fileID, transferID string, rejected bool, now time.Time) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	state := "PARTIAL"
	if rejected {
		state = "REJECTED"
	}
	if _, err = tx.ExecContext(ctx, `UPDATE transfers SET state=?, staged_file_id=NULL,
		last_activity_at=? WHERE transfer_id=?`, state, formatTime(now), transferID); err != nil {
		return err
	}
	if _, err = tx.ExecContext(ctx, "DELETE FROM files WHERE file_id=? AND status='QUARANTINED'", fileID); err != nil {
		return err
	}
	return tx.Commit()
}

func (s *Store) CommitReusedFile(ctx context.Context, file model.StoredFile, transferID string, now time.Time) (string, error) {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	var existingID string
	err = tx.QueryRowContext(ctx, "SELECT file_id FROM files WHERE stored_path=?", file.StoredPath).Scan(&existingID)
	if errors.Is(err, sql.ErrNoRows) {
		existingID = file.FileID
		_, err = tx.ExecContext(ctx, `INSERT INTO files(file_id, device_id, client_file_key,
			original_name, original_relative_path, media_type, mime_type, size_bytes, sha256,
			captured_at, modified_at, stored_path, backed_up_at, status)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMMITTED')`, existingID,
			file.DeviceID, file.ClientFileKey, file.OriginalName, file.OriginalRelativePath,
			file.MediaType, file.MIMEType, file.SizeBytes, file.SHA256, optionalTime(file.CapturedAt),
			optionalTime(file.ModifiedAt), file.StoredPath, formatTime(file.BackedUpAt))
	} else if err == nil {
		_, err = tx.ExecContext(ctx, `UPDATE files SET device_id=?, client_file_key=?, original_name=?,
			original_relative_path=?, media_type=?, mime_type=?, size_bytes=?, sha256=?, captured_at=?,
			modified_at=?, backed_up_at=?, status='COMMITTED' WHERE file_id=?`, file.DeviceID,
			file.ClientFileKey, file.OriginalName, file.OriginalRelativePath, file.MediaType,
			file.MIMEType, file.SizeBytes, file.SHA256, optionalTime(file.CapturedAt),
			optionalTime(file.ModifiedAt), formatTime(file.BackedUpAt), existingID)
	}
	if err != nil {
		return "", err
	}
	if _, err = tx.ExecContext(ctx, `UPDATE transfers SET state='COMMITTED', staged_file_id=?,
		accepted_offset=expected_size, last_activity_at=? WHERE transfer_id=?`, existingID,
		formatTime(now), transferID); err != nil {
		return "", err
	}
	return existingID, tx.Commit()
}

func (s *Store) CompleteRun(ctx context.Context, runID, deviceID string, now time.Time) (model.BackupRun, int64, error) {
	run, err := s.Run(ctx, runID)
	if err != nil {
		return run, 0, err
	}
	if run.DeviceID != deviceID {
		return run, 0, ErrNotOwned
	}
	if run.State == "CANCELLED" {
		return run, 0, ErrTerminalRun
	}
	if run.State == "COMPLETED" {
		return run, 0, nil
	}
	var pending int64
	if err := s.db.QueryRowContext(ctx, "SELECT COUNT(*) FROM transfers WHERE run_id=? AND state <> 'COMMITTED'", runID).Scan(&pending); err != nil {
		return run, 0, err
	}
	if pending > 0 {
		return run, pending, ErrIncompleteRun
	}
	_, err = s.db.ExecContext(ctx, `UPDATE backup_runs SET state='COMPLETED', completed_at=?,
		file_count=(SELECT COUNT(*) FROM manifest_entries WHERE run_id=? AND disposition <> 'REJECTED'),
		byte_count=COALESCE((SELECT SUM(size_bytes) FROM manifest_entries WHERE run_id=? AND disposition <> 'REJECTED'),0)
		WHERE run_id=?`, formatTime(now), runID, runID, runID)
	if err != nil {
		return run, 0, err
	}
	run, err = s.Run(ctx, runID)
	return run, 0, err
}

func (s *Store) CancelRun(ctx context.Context, runID, deviceID string) (model.BackupRun, error) {
	run, err := s.Run(ctx, runID)
	if err != nil {
		return run, err
	}
	if run.DeviceID != deviceID {
		return run, ErrNotOwned
	}
	if run.State == "COMPLETED" {
		return run, ErrTerminalRun
	}
	if _, err = s.db.ExecContext(ctx, "UPDATE backup_runs SET state='CANCELLED' WHERE run_id=?", runID); err != nil {
		return run, err
	}
	return s.Run(ctx, runID)
}

type FileCursor struct {
	BackedUpAt time.Time
	FileID     string
}

func (s *Store) ListFiles(ctx context.Context, deviceID, mediaType string, capturedAfter *time.Time,
	cursor *FileCursor, limit int) ([]model.StoredFile, error) {
	query := `SELECT file_id, device_id, client_file_key, original_name, original_relative_path,
		media_type, mime_type, size_bytes, sha256, captured_at, modified_at, stored_path,
		backed_up_at, status FROM files WHERE status='COMMITTED'`
	args := make([]any, 0, 8)
	if deviceID != "" {
		query += " AND device_id=?"
		args = append(args, deviceID)
	}
	if mediaType != "" {
		query += " AND media_type=?"
		args = append(args, mediaType)
	}
	if capturedAfter != nil {
		query += " AND captured_at>=?"
		args = append(args, formatTime(*capturedAfter))
	}
	if cursor != nil {
		query += " AND (backed_up_at<? OR (backed_up_at=? AND file_id<?))"
		args = append(args, formatTime(cursor.BackedUpAt), formatTime(cursor.BackedUpAt), cursor.FileID)
	}
	query += " ORDER BY backed_up_at DESC, file_id DESC LIMIT ?"
	args = append(args, limit)
	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var files []model.StoredFile
	for rows.Next() {
		file, err := scanFile(rows)
		if err != nil {
			return nil, err
		}
		files = append(files, file)
	}
	return files, rows.Err()
}

func (s *Store) File(ctx context.Context, id string) (model.StoredFile, error) {
	row := s.db.QueryRowContext(ctx, `SELECT file_id, device_id, client_file_key, original_name,
		original_relative_path, media_type, mime_type, size_bytes, sha256, captured_at,
		modified_at, stored_path, backed_up_at, status FROM files WHERE file_id=?`, id)
	file, err := scanFile(row)
	if errors.Is(err, sql.ErrNoRows) {
		return file, ErrNotFound
	}
	return file, err
}

type scanner interface{ Scan(...any) error }

func scanFile(row scanner) (model.StoredFile, error) {
	var file model.StoredFile
	var relative, captured, modified sql.NullString
	var backed string
	err := row.Scan(&file.FileID, &file.DeviceID, &file.ClientFileKey, &file.OriginalName,
		&relative, &file.MediaType, &file.MIMEType, &file.SizeBytes, &file.SHA256,
		&captured, &modified, &file.StoredPath, &backed, &file.Status)
	if err != nil {
		return file, err
	}
	if relative.Valid {
		file.OriginalRelativePath = &relative.String
	}
	file.CapturedAt, err = parseNullTime(captured)
	if err != nil {
		return file, err
	}
	file.ModifiedAt, err = parseNullTime(modified)
	if err != nil {
		return file, err
	}
	file.BackedUpAt, err = time.Parse(time.RFC3339Nano, backed)
	return file, err
}

func (s *Store) MarkFileMissing(ctx context.Context, id string) error {
	_, err := s.db.ExecContext(ctx, "UPDATE files SET status='MISSING' WHERE file_id=?", id)
	return err
}

func (s *Store) InterruptActiveRuns(ctx context.Context) error {
	_, err := s.db.ExecContext(ctx, `UPDATE backup_runs SET state='INTERRUPTED'
		WHERE state IN ('PREPARING','PLANNED','TRANSFERRING')`)
	return err
}

// ReconcilePartials makes the SQLite offset authoritative after an unclean shutdown.
// Extra bytes are truncated; a partial that is shorter than the durable offset moves
// the database offset back to the physical file size so the client can resume safely.
func (s *Store) ReconcilePartials(ctx context.Context, resolve func(string) (string, error)) error {
	rows, err := s.db.QueryContext(ctx, `SELECT transfer_id, partial_path, accepted_offset
		FROM transfers WHERE state IN ('PENDING','PARTIAL')`)
	if err != nil {
		return err
	}
	type partial struct {
		id, path string
		offset   int64
	}
	var entries []partial
	for rows.Next() {
		var entry partial
		if err := rows.Scan(&entry.id, &entry.path, &entry.offset); err != nil {
			rows.Close()
			return err
		}
		entries = append(entries, entry)
	}
	if err := rows.Close(); err != nil {
		return err
	}
	for _, entry := range entries {
		path, err := resolve(entry.path)
		if err != nil {
			return err
		}
		info, err := os.Stat(path)
		if errors.Is(err, os.ErrNotExist) {
			if entry.offset != 0 {
				if _, err := s.db.ExecContext(ctx, "UPDATE transfers SET accepted_offset=0, state='PENDING' WHERE transfer_id=?", entry.id); err != nil {
					return err
				}
			}
			continue
		}
		if err != nil {
			return err
		}
		if info.Size() > entry.offset {
			if err := os.Truncate(path, entry.offset); err != nil {
				return err
			}
		} else if info.Size() < entry.offset {
			state := "PARTIAL"
			if info.Size() == 0 {
				state = "PENDING"
			}
			if _, err := s.db.ExecContext(ctx, "UPDATE transfers SET accepted_offset=?, state=? WHERE transfer_id=?", info.Size(), state, entry.id); err != nil {
				return err
			}
		}
	}
	return nil
}

func formatTime(value time.Time) string { return value.UTC().Format(time.RFC3339Nano) }
func optionalTime(value *time.Time) any {
	if value == nil {
		return nil
	}
	return formatTime(*value)
}
func parseNullTime(value sql.NullString) (*time.Time, error) {
	if !value.Valid {
		return nil, nil
	}
	parsed, err := time.Parse(time.RFC3339Nano, value.String)
	if err != nil {
		return nil, err
	}
	return &parsed, nil
}
