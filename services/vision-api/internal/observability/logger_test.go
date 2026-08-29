package observability

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func TestJSONLoggerWritesOnlyAllowedFields(t *testing.T) {
	t.Parallel()

	event, err := NewEvent(OperationVerify, 840)
	if err != nil {
		t.Fatal(err)
	}
	event, err = event.WithModelID("gemini-3.5-flash")
	if err != nil {
		t.Fatal(err)
	}
	event, err = event.WithSchemaVersion("1.0")
	if err != nil {
		t.Fatal(err)
	}
	event, err = event.WithErrorCode("schema_invalid")
	if err != nil {
		t.Fatal(err)
	}

	var output bytes.Buffer
	if err := NewJSONLogger(&output).Record(event); err != nil {
		t.Fatalf("Record() error = %v", err)
	}

	var payload map[string]any
	if err := json.Unmarshal(output.Bytes(), &payload); err != nil {
		t.Fatalf("log is not JSON: %v", err)
	}
	want := map[string]any{
		"operation":      "verify",
		"latency_ms":     float64(840),
		"model_id":       "gemini-3.5-flash",
		"schema_version": "1.0",
		"error_code":     "schema_invalid",
	}
	if len(payload) != len(want) {
		t.Fatalf("payload keys = %#v", payload)
	}
	for key, value := range want {
		if payload[key] != value {
			t.Fatalf("%s = %#v, want %#v", key, payload[key], value)
		}
	}
}

func TestJSONLoggerOmitsEmptyOptionalFields(t *testing.T) {
	t.Parallel()

	event, err := NewEvent(OperationMetadataDelete, 3)
	if err != nil {
		t.Fatal(err)
	}
	var output bytes.Buffer
	if err := NewJSONLogger(&output).Record(event); err != nil {
		t.Fatal(err)
	}

	line := output.String()
	for _, forbidden := range []string{
		"model_id",
		"schema_version",
		"error_code",
		"image",
		"prompt",
		"token",
		"uid",
	} {
		if strings.Contains(line, forbidden) {
			t.Fatalf("log contains forbidden or absent field %q: %s", forbidden, line)
		}
	}
	if !strings.HasSuffix(line, "\n") {
		t.Fatalf("log is not one JSON line: %q", line)
	}
}

func TestJSONLoggerRejectsInvalidEventWithoutWriting(t *testing.T) {
	 t.Parallel()

	var output bytes.Buffer
	err := NewJSONLogger(&output).Record(Event{})
	assertRecordReason(t, err, "invalid_event")
	if output.Len() != 0 {
		t.Fatalf("output = %q", output.String())
	}
}

func TestJSONLoggerRejectsUnavailableWriter(t *testing.T) {
	t.Parallel()

	err := NewJSONLogger(nil).Record(Event{})
	assertRecordReason(t, err, "logger_unavailable")
}

func TestJSONLoggerSanitizesWriteFailureAndPreservesCause(t *testing.T) {
	t.Parallel()

	privateErr := errors.New("private writer destination detail")
	event, err := NewEvent(OperationCompare, 12)
	if err != nil {
		t.Fatal(err)
	}
	err = NewJSONLogger(failingWriter{err: privateErr}).Record(event)
	assertRecordReason(t, err, "write_failed")
	if !errors.Is(err, privateErr) {
		t.Fatal("writer error was not preserved as cause")
	}
	if strings.Contains(err.Error(), privateErr.Error()) {
		t.Fatalf("public error leaked writer detail: %v", err)
	}
}

func TestJSONLoggerWritesSeparateRecords(t *testing.T) {
	t.Parallel()

	var output bytes.Buffer
	logger := NewJSONLogger(&output)
	for _, operation := range []Operation{OperationBaseline, OperationCompare} {
		event, err := NewEvent(operation, 1)
		if err != nil {
			t.Fatal(err)
		}
		if err := logger.Record(event); err != nil {
			t.Fatal(err)
		}
	}
	lines := strings.Split(strings.TrimSpace(output.String()), "\n")
	if len(lines) != 2 {
		t.Fatalf("lines = %d, output = %q", len(lines), output.String())
	}
	for _, line := range lines {
		if !json.Valid([]byte(line)) {
			t.Fatalf("invalid JSON line: %q", line)
		}
	}
}

type failingWriter struct {
	err error
}

func (writer failingWriter) Write([]byte) (int, error) {
	return 0, writer.err
}

func assertRecordReason(t *testing.T, err error, want string) {
	t.Helper()

	var recordErr *RecordError
	if !errors.As(err, &recordErr) {
		t.Fatalf("error = %v, want RecordError", err)
	}
	if recordErr.Reason != want {
		t.Fatalf("reason = %q, want %q", recordErr.Reason, want)
	}
}
