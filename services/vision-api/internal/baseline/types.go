package baseline

import "fmt"

const (
	MinObjects = 1
	MaxObjects = 5
	BoxScale   = 1000
)

type Symmetry string

const (
	SymmetryNone       Symmetry = "none"
	SymmetryBilateral  Symmetry = "bilateral"
	SymmetryRotational Symmetry = "rotational"
)

type ExclusionReason string

const (
	ExclusionTransparent        ExclusionReason = "transparent"
	ExclusionReflective         ExclusionReason = "reflective"
	ExclusionDeformable         ExclusionReason = "deformable"
	ExclusionUnsupportedShape   ExclusionReason = "unsupported_shape"
	ExclusionFixed              ExclusionReason = "fixed"
	ExclusionDuplicateAppearance ExclusionReason = "duplicate_appearance"
)

type BoundingBox struct {
	YMin int `json:"yMin"`
	XMin int `json:"xMin"`
	YMax int `json:"yMax"`
	XMax int `json:"xMax"`
}

type Object struct {
	ID                   string      `json:"id"`
	DisplayName          string      `json:"displayName"`
	AppearanceFeatures   []string    `json:"appearanceFeatures"`
	BoundingBox          BoundingBox `json:"boundingBox"`
	OrientationImportant bool        `json:"orientationImportant"`
	Symmetry             Symmetry    `json:"symmetry"`
}

type ExcludedCandidate struct {
	DisplayName string          `json:"displayName"`
	Reason      ExclusionReason `json:"reason"`
}

type Result struct {
	Objects            []Object            `json:"objects"`
	ExcludedCandidates []ExcludedCandidate `json:"excludedCandidates"`
}

type ValidationError struct {
	Field  string
	Reason string
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("invalid baseline result: %s is %s", e.Field, e.Reason)
}

func Validate(result Result) error {
	if len(result.Objects) < MinObjects || len(result.Objects) > MaxObjects {
		return invalid("objects", "out_of_range")
	}

	ids := make(map[string]struct{}, len(result.Objects))
	for index, object := range result.Objects {
		prefix := fmt.Sprintf("objects[%d]", index)
		if object.ID == "" {
			return invalid(prefix+".id", "required")
		}
		if _, exists := ids[object.ID]; exists {
			return invalid(prefix+".id", "duplicate")
		}
		ids[object.ID] = struct{}{}
		if object.DisplayName == "" {
			return invalid(prefix+".displayName", "required")
		}
		if len(object.AppearanceFeatures) == 0 {
			return invalid(prefix+".appearanceFeatures", "required")
		}
		for featureIndex, feature := range object.AppearanceFeatures {
			if feature == "" {
				return invalid(fmt.Sprintf("%s.appearanceFeatures[%d]", prefix, featureIndex), "required")
			}
		}
		if !validBox(object.BoundingBox) {
			return invalid(prefix+".boundingBox", "invalid")
		}
		if !validSymmetry(object.Symmetry) {
			return invalid(prefix+".symmetry", "unknown")
		}
	}

	for index, candidate := range result.ExcludedCandidates {
		prefix := fmt.Sprintf("excludedCandidates[%d]", index)
		if candidate.DisplayName == "" {
			return invalid(prefix+".displayName", "required")
		}
		if !validExclusionReason(candidate.Reason) {
			return invalid(prefix+".reason", "unknown")
		}
	}
	return nil
}

func validBox(box BoundingBox) bool {
	return box.YMin >= 0 && box.XMin >= 0 &&
		box.YMax <= BoxScale && box.XMax <= BoxScale &&
		box.YMin < box.YMax && box.XMin < box.XMax
}

func validSymmetry(value Symmetry) bool {
	switch value {
	case SymmetryNone, SymmetryBilateral, SymmetryRotational:
		return true
	default:
		return false
	}
}

func validExclusionReason(value ExclusionReason) bool {
	switch value {
	case ExclusionTransparent,
		ExclusionReflective,
		ExclusionDeformable,
		ExclusionUnsupportedShape,
		ExclusionFixed,
		ExclusionDuplicateAppearance:
		return true
	default:
		return false
	}
}

func invalid(field, reason string) error {
	return &ValidationError{Field: field, Reason: reason}
}
