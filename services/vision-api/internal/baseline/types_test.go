package baseline

import (
	"errors"
	"testing"
)

func TestValidateAcceptsOneToFiveSupportedObjects(t *testing.T) {
	result := validResult()
	result.ExcludedCandidates = []ExcludedCandidate{{
		DisplayName: "透明なグラス",
		Reason:      ExclusionTransparent,
	}}

	if err := Validate(result); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestValidateRejectsObjectCountOutsideProductScope(t *testing.T) {
	for _, objects := range [][]Object{
		nil,
		{
			validObject("1"), validObject("2"), validObject("3"),
			validObject("4"), validObject("5"), validObject("6"),
		},
	} {
		var validationError *ValidationError
		err := Validate(Result{Objects: objects})
		if !errors.As(err, &validationError) ||
			validationError.Field != "objects" ||
			validationError.Reason != "out_of_range" {
			t.Fatalf("Validate() error = %#v", err)
		}
	}
}

func TestValidateRejectsDuplicateIdentityAndMissingFeatures(t *testing.T) {
	duplicate := validResult()
	duplicate.Objects = append(duplicate.Objects, validObject("object-1"))

	missingFeature := validResult()
	missingFeature.Objects[0].AppearanceFeatures = nil

	for _, result := range []Result{duplicate, missingFeature} {
		if err := Validate(result); err == nil {
			t.Fatalf("Validate(%#v) error = nil", result)
		}
	}
}

func TestValidateRejectsOutOfRangeAndDegenerateBoxes(t *testing.T) {
	boxes := []BoundingBox{
		{YMin: -1, XMin: 10, YMax: 100, XMax: 100},
		{YMin: 10, XMin: 10, YMax: 1001, XMax: 100},
		{YMin: 10, XMin: 10, YMax: 10, XMax: 100},
		{YMin: 10, XMin: 100, YMax: 100, XMax: 100},
	}
	for _, box := range boxes {
		result := validResult()
		result.Objects[0].BoundingBox = box
		if err := Validate(result); err == nil {
			t.Fatalf("Validate() accepted box %#v", box)
		}
	}
}

func TestValidateRejectsUnknownEnums(t *testing.T) {
	unknownSymmetry := validResult()
	unknownSymmetry.Objects[0].Symmetry = "approximately_round"

	unknownExclusion := validResult()
	unknownExclusion.ExcludedCandidates = []ExcludedCandidate{{
		DisplayName: "判定不能",
		Reason:      "maybe_unsupported",
	}}

	for _, result := range []Result{unknownSymmetry, unknownExclusion} {
		var validationError *ValidationError
		if err := Validate(result); !errors.As(err, &validationError) {
			t.Fatalf("Validate() error = %#v", err)
		}
	}
}

func validResult() Result {
	return Result{Objects: []Object{validObject("object-1")}}
}

func validObject(id string) Object {
	return Object{
		ID:                   id,
		DisplayName:          "黒い財布",
		AppearanceFeatures:   []string{"黒色", "長方形", "金色の留め具"},
		BoundingBox:          BoundingBox{YMin: 100, XMin: 200, YMax: 500, XMax: 700},
		OrientationImportant: true,
		Symmetry:             SymmetryNone,
	}
}
