package structured

import (
	"errors"
	"testing"
)

type testResult struct {
	Status string `json:"status"`
}

func TestDecodeAcceptsSingleValidatedObject(t *testing.T) {
	result, err := Decode(`{"status":"verified"}`, func(value testResult) error {
		if value.Status != "verified" {
			return errors.New("unexpected status")
		}
		return nil
	})
	if err != nil {
		t.Fatalf("Decode() error = %v", err)
	}
	if result.Status != "verified" {
		t.Errorf("Status = %q", result.Status)
	}
}

func TestDecodeRejectsEmptyInvalidUnknownAndTrailingJSON(t *testing.T) {
	tests := []struct {
		name   string
		text   string
		reason FailureReason
	}{
		{name: "empty", text: "  ", reason: FailureEmptyResponse},
		{name: "invalid", text: "{", reason: FailureInvalidJSON},
		{name: "unknown", text: `{"status":"verified","extra":true}`, reason: FailureUnknownField},
		{name: "trailing", text: `{"status":"verified"} {"status":"verified"}`, reason: FailureTrailingContent},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			_, err := Decode(test.text, func(testResult) error { return nil })
			var decodeError *DecodeError
			if !errors.As(err, &decodeError) || decodeError.Reason != test.reason {
				t.Fatalf("error = %#v, want %s", err, test.reason)
			}
		})
	}
}

func TestDecodeWrapsDomainValidationFailure(t *testing.T) {
	want := errors.New("status is not accepted")
	_, err := Decode(`{"status":"uncertain"}`, func(testResult) error {
		return want
	})

	var decodeError *DecodeError
	if !errors.As(err, &decodeError) || decodeError.Reason != FailureDomainRejected {
		t.Fatalf("error = %#v", err)
	}
	if !errors.Is(err, want) {
		t.Errorf("error does not wrap validator failure: %v", err)
	}
}

func TestDecodeRejectsMissingValidator(t *testing.T) {
	_, err := Decode[testResult](`{"status":"verified"}`, nil)

	var decodeError *DecodeError
	if !errors.As(err, &decodeError) || decodeError.Reason != FailureValidatorMissing {
		t.Fatalf("error = %#v", err)
	}
}
