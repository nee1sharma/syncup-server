package storage

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

type Storage struct {
	Root     string
	lockFile *os.File
}

func Open(root string, minimumFreeBytes int64) (*Storage, error) {
	absolute, err := filepath.Abs(root)
	if err != nil {
		return nil, fmt.Errorf("resolve storage root: %w", err)
	}
	absolute = filepath.Clean(absolute)
	for _, directory := range []string{"", "data", "partial", "quarantine"} {
		if err := os.MkdirAll(filepath.Join(absolute, directory), 0o750); err != nil {
			return nil, fmt.Errorf("create storage directory: %w", err)
		}
	}
	lock, err := os.OpenFile(filepath.Join(absolute, "syncup.lock"), os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, fmt.Errorf("open storage lock: %w", err)
	}
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		_ = lock.Close()
		return nil, errors.New("another SyncUp server is using this storage root")
	}
	s := &Storage{Root: absolute, lockFile: lock}
	available, err := s.AvailableBytes()
	if err != nil {
		_ = s.Close()
		return nil, err
	}
	if available < minimumFreeBytes {
		_ = s.Close()
		return nil, fmt.Errorf("storage has %d free bytes; at least %d required", available, minimumFreeBytes)
	}
	return s, nil
}

func (s *Storage) Resolve(relative string) (string, error) {
	if relative == "" || filepath.IsAbs(relative) {
		return "", errors.New("storage path must be relative")
	}
	resolved := filepath.Clean(filepath.Join(s.Root, filepath.FromSlash(relative)))
	prefix := s.Root + string(os.PathSeparator)
	if resolved != s.Root && !strings.HasPrefix(resolved, prefix) {
		return "", errors.New("storage path escapes root")
	}
	return resolved, nil
}

func (s *Storage) AvailableBytes() (int64, error) {
	var stat syscall.Statfs_t
	if err := syscall.Statfs(s.Root, &stat); err != nil {
		return 0, fmt.Errorf("read storage capacity: %w", err)
	}
	return int64(stat.Bavail) * int64(stat.Bsize), nil
}

func (s *Storage) HasBudget(incoming, reserve int64) bool {
	if incoming < 0 || reserve > int64(^uint64(0)>>1)-incoming {
		return false
	}
	available, err := s.AvailableBytes()
	return err == nil && available >= incoming+reserve
}

func (s *Storage) EnsureIdentityFile(id string) error {
	path := filepath.Join(s.Root, "server-id")
	contents, err := os.ReadFile(path)
	if err == nil {
		if strings.TrimSpace(string(contents)) != id {
			return errors.New("server-id file does not match SQLite identity")
		}
		return nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return fmt.Errorf("read server identity file: %w", err)
	}
	if err := os.WriteFile(path, []byte(id+"\n"), 0o600); err != nil {
		return fmt.Errorf("write server identity file: %w", err)
	}
	return nil
}

func (s *Storage) Close() error {
	if s.lockFile == nil {
		return nil
	}
	unlockErr := syscall.Flock(int(s.lockFile.Fd()), syscall.LOCK_UN)
	closeErr := s.lockFile.Close()
	s.lockFile = nil
	return errors.Join(unlockErr, closeErr)
}
