package observability

import (
	"errors"
	"reflect"
	"strings"
	"testing"
)

func TestEventAcceptsOnlyPrivacySafeFields(t *testing.T) {
	t.Parallel()

	event, err := NewEvent(OperationBaseline, 1250)
	if err != nil {
		t.Fatal(err)
	}
	event, err = event.WithModelID("publishers/google/models/gemini-3.5-flash")
	if err != nil {
		t.Fatal(err)
	}
	event, err = event.WithSchemaVersion("1.0")
	if err != nil {
		t.Fatal(err)
	}
	event, err = event.WithErrorCode("invalid_request")
	if err != nil {
		t.Fatal(err)
	}

	if event.Operation() != OperationBaseline ||
		event.LatencyMS() != 1250 ||
		event.ModelID() != "publishers/google/models/gemini-3.5-flash" ||
		event.SchemaVersion() != "1.0" ||
		event.ErrorCode() != "invalid_request" {
		t.Fatalf("event = %#v", event)
	}
}

func TestEventRejectsUnsupportedOperationAndLatency(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name      string
		operation Operation
		latencyMS int64
		field     string
	}{
		{name: "operation", operation: "object_detected", field: "operation"},
		{name: "negative latency", operation: OperationCompare, latencyMS: -1, field: "latency_ms"},
		{name: "excessive latency", operation: OperationVerify, latencyMS: maxLatencyMS + 1, field: "latency_ms"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			_, err := NewEvent(test.operation, test.latencyMS)
			assertValidationField(t, err, test.field)
		})
	}
}

func TestEventRejectsUnsafeOptionalValues(t *testing.T) {
	t.Parallel()

	base, err := NewEvent(OperationMetadataStore, 5)
	if err != nil {
		t.Fatal(err)
	}
	tests := []struct {
		name  string
		apply func() error
		field string
	}{
		{
			name: "model newline",
			apply: func() error {
				_, err := base.WithModelID("gemini\nprivate")
				return err
			},
			field: "model_id",
		},
		{
			name: "schema space",
			apply: func() error {
				_, err := base.WithSchemaVersion("version private")
				return err
			},
			field: "schema_version",
		},
		{
			name: "error uppercase",
			apply: func() error {
				_, err := base.WithErrorCode("Internal_Error")
				return err
			},
			field: "error_code",
		},
		{
			name: "model too long",
			apply: func() error {
				_, err := base.WithModelID(strings.Repeat("a", maxModelIDBytes+1))
				return err
			},
			field: "model_id",
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			assertValidationField(t, test.apply(), test.field)
		})
	}
}

func TestAllowedAttributeKeysAreExactAndReturnedAsCopy(t *testing.T) {
	t.Parallel()

	want := []AttributeKey{
		AttributeLatencyMS,
		AttributeModelID,
		AttributeSchemaVersion,
		AttributeErrorCode,
	}
	got := AllowedAttributeKeys()
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("keys = %#v, want %#v", got, want)
	}
	got[0] = "image"
	if reflect.DeepEqual(AllowedAttributeKeys(), got) {
		t.Fatal("allowed keys were mutated by caller")
	}
}

func TestForbiddenPrivacyKeysAreRejected(t *testing.T) {
	t.Parallel()

	forbidden := []AttributeKey{
		"image",
		"image_url",
		"object_name",
		"label",
		"prompt",
		"signature",
		"embedding",
		"id_token",
		"app_check_token",
		"uid",
		"request_body",
		"response_body",
	}
	for _, key := range forbidden {
		t.Run(string(key), func(t *testing.T) {
			t.Parallel()

			err := ValidateAttributeKeys(key)
			assertValidationField(t, err, "attribute_key")
			if strings.Contains(err.Error(), string(key)) {
				t.Fatalf("validation error leaked forbidden key: %v", err)
			}
		})
	}
}

func TestAllDeclaredAttributeKeysPassValidation(t *testing.T) {
	t.Parallel()

	if err := ValidateAttributeKeys(AllowedAttributeKeys()...); err != nil {
		t.Fatalf("ValidateAttributeKeys() error = %v", err)
	}
}

func assertValidationField(t *testing.T, err error, field string) {
	t.Helper()

	var validationErr *ValidationError
	if !errors.As(err, &validationErr) {
		t.Fatalf("error = %v, want ValidationError", err)
	}
	if validationErr.Field != field {
		t.Fatalf("field = %q, want %q", validationErr.Field, field)
	}
}
