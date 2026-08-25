package scenemetadata

import (
	"fmt"
	"strings"
	"time"
)

const (
	MinObjects = 1
	MaxObjects = 5
	MaxRetries = 3
)

type Result string

const (
	ResultVerified        Result = "verified"
	ResultNeedsCorrection Result = "needs_correction"
	ResultUncertain       Result = "uncertain"
)

type Scene struct {
	SceneID           string
	CreatedAt         time.Time
	CompletedAt       time.Time
	ObjectCount       int
	Result            Result
	BaselineLatencyMs int64
	CompareLatencyMs  int64
	VerifyLatencyMs   int64
	ModelID           string
	PromptVersion     string
	RetryCount        int
	AppVersion        string
	SchemaVersion     string
}

type ValidationError struct {
	Field  string
	Reason string
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("invalid scene metadata: %s is %s", e.Field, e.Reason)
}

func ValidateOwnerID(uid string) error {
	return validateDocumentID("uid", uid)
}

func Validate(scene Scene) error {
	if err := validateDocumentID("sceneId", scene.SceneID); err != nil {
		return err
	}
	if scene.CreatedAt.IsZero() {
		return invalid("createdAt", "required")
	}
	if scene.CompletedAt.IsZero() {
		return invalid("completedAt", "required")
	}
	if scene.CompletedAt.Before(scene.CreatedAt) {
		return invalid("completedAt", "before_createdAt")
	}
	if scene.ObjectCount < MinObjects || scene.ObjectCount > MaxObjects {
		return invalid("objectCount", "out_of_range")
	}
	if !validResult(scene.Result) {
		return invalid("result", "unknown")
	}
	if scene.BaselineLatencyMs < 0 {
		return invalid("baselineLatencyMs", "negative")
	}
	if scene.CompareLatencyMs < 0 {
		return invalid("compareLatencyMs", "negative")
	}
	if scene.VerifyLatencyMs < 0 {
		return invalid("verifyLatencyMs", "negative")
	}
	if scene.RetryCount < 0 || scene.RetryCount > MaxRetries {
		return invalid("retryCount", "out_of_range")
	}
	if strings.TrimSpace(scene.ModelID) == "" {
		return invalid("modelId", "required")
	}
	if strings.TrimSpace(scene.PromptVersion) == "" {
		return invalid("promptVersion", "required")
	}
	if strings.TrimSpace(scene.AppVersion) == "" {
		return invalid("appVersion", "required")
	}
	if strings.TrimSpace(scene.SchemaVersion) == "" {
		return invalid("schemaVersion", "required")
	}
	return nil
}

func validateDocumentID(field, value string) error {
	if strings.TrimSpace(value) == "" {
		return invalid(field, "required")
	}
	if strings.ContainsAny(value, "/\\") || value == "." || value == ".." {
		return invalid(field, "invalid_document_id")
	}
	return nil
}

func validResult(result Result) bool {
	switch result {
	case ResultVerified, ResultNeedsCorrection, ResultUncertain:
		return true
	default:
		return false
	}
}

func invalid(field, reason string) error {
	return &ValidationError{Field: field, Reason: reason}
}
