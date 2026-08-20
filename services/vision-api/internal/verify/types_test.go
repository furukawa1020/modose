package verify

import (
	"errors"
	"testing"
)

func TestValidateAcceptsThreeConsistentStatuses(t *testing.T) {
	ids := []string{"wallet", "key"}
	results := []Result{
		{Status: StatusVerified},
		{
			Status: StatusNeedsCorrection,
			Corrections: []Correction{{
				BaselineObjectID: "wallet",
				Reason:           "目標位置からずれている",
			}},
		},
		{Status: StatusUncertain, UncertaintyReason: "鍵が一部遮蔽されている"},
	}
	for _, result := range results {
		if err := Validate(result, ids); err != nil {
			t.Fatalf("Validate(%#v) error = %v", result, err)
		}
	}
}

func TestIsVerifiedOnlyAcceptsValidatedVerifiedResult(t *testing.T) {
	ids := []string{"wallet"}
	if !IsVerified(Result{Status: StatusVerified}, ids) {
		t.Fatal("valid verified result was not accepted")
	}
	if IsVerified(Result{Status: StatusNeedsCorrection, Corrections: []Correction{{
		BaselineObjectID: "wallet",
		Reason:           "ずれ",
	}}}, ids) {
		t.Fatal("needs_correction was accepted")
	}
	if IsVerified(Result{Status: StatusVerified, UncertaintyReason: "不明"}, ids) {
		t.Fatal("inconsistent verified result was accepted")
	}
}

func TestValidateRejectsStatusFieldContradictions(t *testing.T) {
	ids := []string{"wallet"}
	results := []Result{
		{Status: StatusVerified, Corrections: []Correction{{BaselineObjectID: "wallet", Reason: "ずれ"}}},
		{Status: StatusVerified, UncertaintyReason: "不明"},
		{Status: StatusNeedsCorrection},
		{Status: StatusNeedsCorrection, Corrections: []Correction{{BaselineObjectID: "wallet", Reason: "ずれ"}}, UncertaintyReason: "不明"},
		{Status: StatusUncertain},
		{Status: StatusUncertain, Corrections: []Correction{{BaselineObjectID: "wallet", Reason: "ずれ"}}, UncertaintyReason: "不明"},
	}
	for _, result := range results {
		var validationError *ValidationError
		if err := Validate(result, ids); !errors.As(err, &validationError) {
			t.Fatalf("Validate(%#v) error = %#v", result, err)
		}
	}
}

func TestValidateRejectsUnknownAndDuplicateCorrectionIDs(t *testing.T) {
	results := []Result{
		{
			Status: StatusNeedsCorrection,
			Corrections: []Correction{{BaselineObjectID: "unknown", Reason: "ずれ"}},
		},
		{
			Status: StatusNeedsCorrection,
			Corrections: []Correction{
				{BaselineObjectID: "wallet", Reason: "位置"},
				{BaselineObjectID: "wallet", Reason: "向き"},
			},
		},
		{
			Status: StatusNeedsCorrection,
			Corrections: []Correction{{BaselineObjectID: "wallet"}},
		},
	}
	for _, result := range results {
		if err := Validate(result, []string{"wallet"}); err == nil {
			t.Fatalf("Validate(%#v) error = nil", result)
		}
	}
}

func TestValidateRejectsUnknownStatusAndInvalidBaselineIDs(t *testing.T) {
	if err := Validate(Result{Status: "probably_verified"}, []string{"wallet"}); err == nil {
		t.Fatal("unknown status was accepted")
	}
	if err := Validate(Result{Status: StatusVerified}, nil); err == nil {
		t.Fatal("empty baseline IDs were accepted")
	}
	if err := Validate(Result{Status: StatusVerified}, []string{"wallet", "wallet"}); err == nil {
		t.Fatal("duplicate baseline IDs were accepted")
	}
}
