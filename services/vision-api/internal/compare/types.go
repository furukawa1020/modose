package compare

import (
	"fmt"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
)

type State string

const (
	StateAligned      State = "aligned"
	StateMoved        State = "moved"
	StateRotated      State = "rotated"
	StateMovedRotated State = "moved_rotated"
	StateMissing      State = "missing"
	StateOccluded     State = "occluded"
	StateAmbiguous    State = "ambiguous"
)

type Match struct {
	BaselineObjectID string                `json:"baselineObjectId"`
	State            State                 `json:"state"`
	Confidence       float64               `json:"confidence"`
	CurrentBox       *baseline.BoundingBox `json:"currentBox"`
	AmbiguityReason  string                `json:"ambiguityReason"`
}

type AddedObject struct {
	DisplayName string               `json:"displayName"`
	CurrentBox  baseline.BoundingBox `json:"currentBox"`
	Confidence  float64              `json:"confidence"`
}

type Result struct {
	Matches      []Match       `json:"matches"`
	AddedObjects []AddedObject `json:"addedObjects"`
}

type ValidationError struct {
	Field  string
	Reason string
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("invalid compare result: %s is %s", e.Field, e.Reason)
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
	if len(expected) == 0 || len(result.Matches) != len(expected) {
		return invalid("matches", "count_mismatch")
	}

	seen := make(map[string]struct{}, len(result.Matches))
	for index, match := range result.Matches {
		prefix := fmt.Sprintf("matches[%d]", index)
		if _, exists := expected[match.BaselineObjectID]; !exists {
			return invalid(prefix+".baselineObjectId", "unknown")
		}
		if _, exists := seen[match.BaselineObjectID]; exists {
			return invalid(prefix+".baselineObjectId", "duplicate")
		}
		seen[match.BaselineObjectID] = struct{}{}
		if !validState(match.State) {
			return invalid(prefix+".state", "unknown")
		}
		if match.Confidence < 0 || match.Confidence > 1 {
			return invalid(prefix+".confidence", "out_of_range")
		}
		if requiresCurrentBox(match.State) && match.CurrentBox == nil {
			return invalid(prefix+".currentBox", "required")
		}
		if match.State == StateMissing && match.CurrentBox != nil {
			return invalid(prefix+".currentBox", "forbidden")
		}
		if match.CurrentBox != nil && !validBox(*match.CurrentBox) {
			return invalid(prefix+".currentBox", "invalid")
		}
		if match.State == StateAmbiguous && match.AmbiguityReason == "" {
			return invalid(prefix+".ambiguityReason", "required")
		}
		if match.State != StateAmbiguous && match.AmbiguityReason != "" {
			return invalid(prefix+".ambiguityReason", "forbidden")
		}
	}

	for index, object := range result.AddedObjects {
		prefix := fmt.Sprintf("addedObjects[%d]", index)
		if object.DisplayName == "" {
			return invalid(prefix+".displayName", "required")
		}
		if object.Confidence < 0 || object.Confidence > 1 {
			return invalid(prefix+".confidence", "out_of_range")
		}
		if !validBox(object.CurrentBox) {
			return invalid(prefix+".currentBox", "invalid")
		}
	}
	return nil
}

func validState(state State) bool {
	switch state {
	case StateAligned, StateMoved, StateRotated, StateMovedRotated,
		StateMissing, StateOccluded, StateAmbiguous:
		return true
	default:
		return false
	}
}

func requiresCurrentBox(state State) bool {
	switch state {
	case StateAligned, StateMoved, StateRotated, StateMovedRotated:
		return true
	default:
		return false
	}
}

func validBox(box baseline.BoundingBox) bool {
	return box.YMin >= 0 && box.XMin >= 0 &&
		box.YMax <= baseline.BoxScale && box.XMax <= baseline.BoxScale &&
		box.YMin < box.YMax && box.XMin < box.XMax
}

func invalid(field, reason string) error {
	return &ValidationError{Field: field, Reason: reason}
}
