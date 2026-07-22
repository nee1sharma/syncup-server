package config

import (
	"errors"
	"flag"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

const (
	defaultSegmentBytes    = int64(8 * 1024 * 1024)
	defaultMaxSegmentBytes = int64(4 * 1024 * 1024 * 1024)
	defaultMaxFileBytes    = int64(1024 * 1024 * 1024 * 1024)
	defaultMinFreeBytes    = int64(5 * 1024 * 1024 * 1024)
)

type Config struct {
	HTTPAddress            string
	ServerName             string
	StorageRoot            string
	MinimumFreeBytes       int64
	DiscoveryEnabled       bool
	DiscoveryPort          int
	DiscoveryMaxDatagram   int
	SegmentBytes           int64
	MaxSegmentBytes        int64
	MaxFileBytes           int64
	MaxConcurrentPerDevice int
	MaxConcurrentTotal     int
	ManifestMaxFiles       int
	ManifestMaxBodyBytes   int64
	PartialRetention       time.Duration
	ShutdownTimeout        time.Duration
	AppVersion             string
}

func Load(args []string, appVersion string) (Config, error) {
	hostname, _ := os.Hostname()
	if strings.TrimSpace(hostname) == "" {
		hostname = "SyncUp Go Server"
	}
	storageRoot, err := defaultStorageRoot()
	if err != nil {
		return Config{}, err
	}
	c := Config{
		HTTPAddress:            "0.0.0.0:8500",
		ServerName:             hostname,
		StorageRoot:            storageRoot,
		MinimumFreeBytes:       defaultMinFreeBytes,
		DiscoveryEnabled:       true,
		DiscoveryPort:          9999,
		DiscoveryMaxDatagram:   2048,
		SegmentBytes:           defaultSegmentBytes,
		MaxSegmentBytes:        defaultMaxSegmentBytes,
		MaxFileBytes:           defaultMaxFileBytes,
		MaxConcurrentPerDevice: 2,
		MaxConcurrentTotal:     4,
		ManifestMaxFiles:       500,
		ManifestMaxBodyBytes:   4 * 1024 * 1024,
		PartialRetention:       7 * 24 * time.Hour,
		ShutdownTimeout:        30 * time.Second,
		AppVersion:             appVersion,
	}
	if err := applyEnvironment(&c); err != nil {
		return Config{}, err
	}

	fs := flag.NewFlagSet("syncup-server", flag.ContinueOnError)
	fs.StringVar(&c.HTTPAddress, "http-address", c.HTTPAddress, "HTTP listen address")
	fs.StringVar(&c.ServerName, "server-name", c.ServerName, "name advertised to clients")
	fs.StringVar(&c.StorageRoot, "storage-root", c.StorageRoot, "metadata and file storage root")
	fs.Int64Var(&c.MinimumFreeBytes, "minimum-free-bytes", c.MinimumFreeBytes, "reserved free-space safety margin")
	fs.BoolVar(&c.DiscoveryEnabled, "discovery-enabled", c.DiscoveryEnabled, "enable UDP discovery")
	fs.IntVar(&c.DiscoveryPort, "discovery-port", c.DiscoveryPort, "UDP discovery port")
	if err := fs.Parse(args); err != nil {
		return Config{}, err
	}
	if fs.NArg() != 0 {
		return Config{}, fmt.Errorf("unexpected arguments: %s", strings.Join(fs.Args(), " "))
	}
	if err := c.Validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

func defaultStorageRoot() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", fmt.Errorf("determine user home directory: %w", err)
	}
	return filepath.Join(home, "Documents", "SyncUp"), nil
}

func (c Config) Validate() error {
	if strings.TrimSpace(c.ServerName) == "" {
		return errors.New("server name must not be blank")
	}
	if _, _, err := net.SplitHostPort(c.HTTPAddress); err != nil {
		return fmt.Errorf("invalid HTTP address %q: %w", c.HTTPAddress, err)
	}
	if c.StorageRoot == "" {
		return errors.New("storage root must not be blank")
	}
	if c.MinimumFreeBytes < 0 || c.DiscoveryPort < 1 || c.DiscoveryPort > 65535 {
		return errors.New("storage and discovery limits are outside the supported range")
	}
	if c.DiscoveryMaxDatagram < 256 || c.DiscoveryMaxDatagram > 65507 {
		return errors.New("discovery max datagram must be between 256 and 65507 bytes")
	}
	if c.SegmentBytes < 1 || c.MaxSegmentBytes < c.SegmentBytes || c.MaxFileBytes < 1 {
		return errors.New("transfer byte limits are invalid")
	}
	if c.MaxConcurrentPerDevice < 1 || c.MaxConcurrentTotal < 1 {
		return errors.New("transfer concurrency limits must be positive")
	}
	if c.ManifestMaxFiles < 1 || c.ManifestMaxFiles > 10_000 || c.ManifestMaxBodyBytes < 1024 {
		return errors.New("manifest limits are invalid")
	}
	if c.PartialRetention <= 0 || c.ShutdownTimeout <= 0 {
		return errors.New("durations must be positive")
	}
	return nil
}

func applyEnvironment(c *Config) error {
	stringEnv("SYNCUP_HTTP_ADDRESS", &c.HTTPAddress)
	stringEnv("SYNCUP_SERVER_NAME", &c.ServerName)
	stringEnv("SYNCUP_STORAGE_ROOT", &c.StorageRoot)
	if err := int64Env("SYNCUP_MINIMUM_FREE_BYTES", &c.MinimumFreeBytes); err != nil {
		return err
	}
	if err := boolEnv("SYNCUP_DISCOVERY_ENABLED", &c.DiscoveryEnabled); err != nil {
		return err
	}
	if err := intEnv("SYNCUP_DISCOVERY_PORT", &c.DiscoveryPort); err != nil {
		return err
	}
	if err := intEnv("SYNCUP_DISCOVERY_MAX_DATAGRAM_BYTES", &c.DiscoveryMaxDatagram); err != nil {
		return err
	}
	if err := int64Env("SYNCUP_SEGMENT_BYTES", &c.SegmentBytes); err != nil {
		return err
	}
	if err := int64Env("SYNCUP_MAX_SEGMENT_BYTES", &c.MaxSegmentBytes); err != nil {
		return err
	}
	if err := int64Env("SYNCUP_MAX_FILE_BYTES", &c.MaxFileBytes); err != nil {
		return err
	}
	if err := intEnv("SYNCUP_MAX_CONCURRENT_PER_DEVICE", &c.MaxConcurrentPerDevice); err != nil {
		return err
	}
	if err := intEnv("SYNCUP_MAX_CONCURRENT_TOTAL", &c.MaxConcurrentTotal); err != nil {
		return err
	}
	if err := intEnv("SYNCUP_MANIFEST_MAX_FILES", &c.ManifestMaxFiles); err != nil {
		return err
	}
	if err := int64Env("SYNCUP_MANIFEST_MAX_BODY_BYTES", &c.ManifestMaxBodyBytes); err != nil {
		return err
	}
	if err := durationEnv("SYNCUP_PARTIAL_RETENTION", &c.PartialRetention); err != nil {
		return err
	}
	return durationEnv("SYNCUP_SHUTDOWN_TIMEOUT", &c.ShutdownTimeout)
}

func stringEnv(name string, target *string) {
	if value, ok := os.LookupEnv(name); ok {
		*target = value
	}
}

func intEnv(name string, target *int) error {
	value, ok := os.LookupEnv(name)
	if !ok {
		return nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil {
		return fmt.Errorf("%s: %w", name, err)
	}
	*target = parsed
	return nil
}

func int64Env(name string, target *int64) error {
	value, ok := os.LookupEnv(name)
	if !ok {
		return nil
	}
	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil {
		return fmt.Errorf("%s: %w", name, err)
	}
	*target = parsed
	return nil
}

func boolEnv(name string, target *bool) error {
	value, ok := os.LookupEnv(name)
	if !ok {
		return nil
	}
	parsed, err := strconv.ParseBool(value)
	if err != nil {
		return fmt.Errorf("%s: %w", name, err)
	}
	*target = parsed
	return nil
}

func durationEnv(name string, target *time.Duration) error {
	value, ok := os.LookupEnv(name)
	if !ok {
		return nil
	}
	parsed, err := time.ParseDuration(value)
	if err != nil {
		return fmt.Errorf("%s: %w", name, err)
	}
	*target = parsed
	return nil
}
