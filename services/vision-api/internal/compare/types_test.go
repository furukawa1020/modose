package compare

import (
	"errors"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
)

func TestValidateAcceptsOneMatchPerBaselineObject(t *testing.T) {
	box := validBoxValue()
	result := Result{
		Matches: []Match{
			{BaselineObjectID: "wallet", State: StateMoved, Confidence: 0.94, CurrentBox: &box},
			{BaselineObjectID: "key", State: StateMissing, Confidence: 0.88},
			{BaselineObjectID: "pen", State: StateAmbiguous, Confidence: 0.51, AmbiguityReason: "候補が2個ある"},
		},
		AddedObjects: []AddedObject{{
			DisplayName: "赤いカード",
			CurrentBox:  validBoxValue(),
			Confidence:  0.91,
		}},
	}

	if err := Validate(result, []string{"wallet", "key", "pen"}); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestValidateRejectsMissingUnknownAndDuplicateBaselineIDs(t *testing.T) {
	box := validBoxValue()
	tests := []struct {
		name     string
		ids      []string
		matches  []Match
	}{
		{
			name: "missing",
			ids:  []string{"wallet", "key"},
			matches: []Match{{BaselineObjectID: "wallet", State: StateAligned, CurrentBox: &box}},
		},
		{
			name: "unknown",
			ids:  []string{"wallet"},
			matches: []Match{{BaselineObjectID: "key", State: StateAligned, CurrentBox: &box}},
		},
		{
			name: "duplicate",
			ids:  []string{"wallet", "key"},
			matches: []Match{
				{BaselineObjectID: "wallet", State: StateAligned, CurrentBox: &box},
				{BaselineObjectID: "wallet", State: StateMoved, CurrentBox: &box},
			},
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if err := Validate(Result{Matches: test.matches}, test.ids); err == nil {
				t.Fatal("Validate() error = nil")
			}
		})
	}
}

func TestValidateEnforcesStateSpecificFields(t *testing.T) {
	box := validBoxValue()
	tests := []Match{
		{BaselineObjectID: "wallet", State: StateMoved, Confidence: 0.9},
		{BaselineObjectID: "wallet", State: StateMissing, Confidence: 0.9, CurrentBox: &box},
		{BaselineObjectID: "wallet", State: StateAmbiguous, Confidence: 0.5},
		{BaselineObjectID: "wallet", State: StateAligned, Confidence: 0.9, CurrentBox: &box, AmbiguityReason: "不要"},
	}
	for _, match := range tests {
		var validationError *ValidationError
		err := Validate(Result{Matches: []Match{match}}, []string{"wallet"})
		if !errors.As(err, &validationError) {
			t.Fatalf("Validate(%#v) error = %#v", match, err)
		}
	}
}

func TestValidateRejectsUnknownStateConfidenceAndBox(t *testing.T) {
	box := validBoxValue()
	outOfRangeBox := baseline.BoundingBox{YMin: 0, XMin: 0, YMax: 1001, XMax: 100}
	tests := []Match{
		{BaselineObjectID: "wallet", State: "probably_moved", Confidence: 0.8, CurrentBox: &box},
		{BaselineObjectID: "wallet", State: StateMoved, Confidence: 1.1, CurrentBox: &box},
		{BaselineObjectID: "wallet", State: StateMoved, Confidence: 0.8, CurrentBox: &outOfRangeBox},
	}
	for _, match := range tests {
		if err := Validate(Result{Matches: []Match{match}}, []string{"wallet"}); err == nil {
			t.Fatalf("Validate(%#v) error = nil", match)
		}
	}
}

func validBoxValue() baseline.BoundingBox {
	return baseline.BoundingBox{YMin: 100, XMin: 200, YMax: 400, XMax: 600}
}
