package verify

import "fmt"

type Status string

const (
	StatusVerified        Status = "verified"
	StatusNeedsCorrection Status = "needs_correction"
	StatusUncertain       Status = "uncertain"
)

type Correction struct {
	BaselineObjectID string `json:"baselineObjectId"`
	Reason           string `json:"reason"`
}

type Result struct {
	Status            Status       `json:"status"`
	Corrections       []Correction `json:"corrections"`
	UncertaintyReason string       `json:"uncertaintyReason"`
}

type ValidationError struct {
	Field  string
	Reason string
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("invalid verify result: %s is %s", e.Field, e.Reason)
}

func Validate(result Result, baselineObjectIDs []string) error {
	expected := make(map[string]struct{}, len(baselineObjectIDs))
	for _, id := range baselineObjectIDs {
		if id == "" {
			return invalid("baselineObjectIds", "contains_empty")
		}
		if _, exists := expected[id]; exists {
			return invalid("baselineObjectIds", "duplicate")
		}
		expected[id] = struct{}{}
	}
	if len(expected) == 0 {
		return invalid("baselineObjectIds", "required")
	}
	if !validStatus(result.Status) {
		return invalid("status", "unknown")
	}

	switch result.Status {
	case StatusVerified:
		if len(result.Corrections) != 0 {
			return invalid("corrections", "forbidden")
		}
		if result.UncertaintyReason != "" {
			return invalid("uncertaintyReason", "forbidden")
		}
	case StatusNeedsCorrection:
		if len(result.Corrections) == 0 {
			return invalid("corrections", "required")
		}
		if result.UncertaintyReason != "" {
			return invalid("uncertaintyReason", "forbidden")
		}
	case StatusUncertain:
		if len(result.Corrections) != 0 {
			return invalid("corrections", "forbidden")
		}
		if result.UncertaintyReason == "" {
			return invalid("uncertaintyReason", "required")
		}
	}

	seen := make(map[string]struct{}, len(result.Corrections))
	for index, correction := range result.Corrections {
		prefix := fmt.Sprintf("corrections[%d]", index)
		if _, exists := expected[correction.BaselineObjectID]; !exists {
			return invalid(prefix+".baselineObjectId", "unknown")
		}
		if _, exists := seen[correction.BaselineObjectID]; exists {
			return invalid(prefix+".baselineObjectId", "duplicate")
		}
		seen[correction.BaselineObjectID] = struct{}{}
		if correction.Reason == "" {
			return invalid(prefix+".reason", "required")
		}
	}
	return nil
}

func IsVerified(result Result, baselineObjectIDs []string) bool {
	return Validate(result, baselineObjectIDs) == nil && result.Status == StatusVerified
}

func validStatus(status Status) bool {
	switch status {
	case StatusVerified, StatusNeedsCorrection, StatusUncertain:
		return true
	default:
		return false
	}
}

func invalid(field, reason string) error {
	return &ValidationError{Field: field, Reason: reason}
}
