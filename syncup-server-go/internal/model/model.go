package model

import "time"

type ServerIdentity struct {
	ServerID   string
	ServerName string
	CreatedAt  time.Time
}

type BackupRun struct {
	RunID          string
	DeviceID       string
	IdempotencyKey string
	State          string
	StartedAt      time.Time
	CompletedAt    *time.Time
	FileCount      int64
	ByteCount      int64
}

type ManifestFile struct {
	ClientFileKey string     `json:"clientFileKey"`
	DisplayName   string     `json:"displayName"`
	RelativePath  *string    `json:"relativePath"`
	MediaType     string     `json:"mediaType"`
	MIMEType      string     `json:"mimeType"`
	SizeBytes     int64      `json:"sizeBytes"`
	SHA256        string     `json:"sha256"`
	CapturedAt    *time.Time `json:"capturedAt"`
	ModifiedAt    *time.Time `json:"modifiedAt"`
}

type ManifestEntry struct {
	RunID string
	ManifestFile
	Disposition string
}

type PlanItem struct {
	ClientFileKey string  `json:"clientFileKey"`
	Disposition   string  `json:"disposition"`
	FileID        *string `json:"fileId"`
	TransferID    *string `json:"transferId"`
	UploadOffset  int64   `json:"uploadOffset"`
	SegmentBytes  int64   `json:"segmentBytes"`
	RejectionCode *string `json:"rejectionCode"`
}

type Transfer struct {
	TransferID    string
	RunID         string
	DeviceID      string
	ClientFileKey string
	PartialPath   string
	ExpectedSize  int64
	ExpectedSHA   string
	Accepted      int64
	State         string
	LastActivity  time.Time
	ExpiresAt     time.Time
	StagedFileID  *string
}

type StoredFile struct {
	FileID               string     `json:"fileId"`
	DeviceID             string     `json:"deviceId"`
	ClientFileKey        string     `json:"-"`
	OriginalName         string     `json:"originalName"`
	OriginalRelativePath *string    `json:"originalRelativePath"`
	MediaType            string     `json:"mediaType"`
	MIMEType             string     `json:"mimeType"`
	SizeBytes            int64      `json:"sizeBytes"`
	SHA256               string     `json:"sha256"`
	CapturedAt           *time.Time `json:"capturedAt"`
	ModifiedAt           *time.Time `json:"modifiedAt"`
	StoredPath           string     `json:"-"`
	BackedUpAt           time.Time  `json:"backedUpAt"`
	Status               string     `json:"-"`
}
