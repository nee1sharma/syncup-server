package config

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDefaultStorageRootIsDocumentsSyncUp(t *testing.T) {
	home, err := os.UserHomeDir()
	if err != nil {
		t.Fatal(err)
	}
	root, err := defaultStorageRoot()
	if err != nil {
		t.Fatal(err)
	}
	want := filepath.Join(home, "Documents", "SyncUp")
	if root != want {
		t.Fatalf("default storage root = %q, want %q", root, want)
	}
}
