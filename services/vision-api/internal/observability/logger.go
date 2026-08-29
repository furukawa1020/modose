package observability

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"sync"
)

type Recorder interface {
	Record(Event) error
}

type RecordError struct {
	Reason string
	cause  error
}

func (e *RecordError) Error() string {
	return fmt.Sprintf("observation record failed: %s", e.Reason)
}

func (e *RecordError) Unwrap() error {
	return e.cause
}

type JSONLogger struct {
	writer io.Writer
	mutex  sync.Mutex
}

var _ Recorder = (*JSONLogger)(nil)

func NewJSONLogger(writer io.Writer) *JSONLogger {
	return &JSONLogger{writer: writer}
}

func (logger *JSONLogger) Record(event Event) error {
	if logger == nil || logger.writer == nil {
		return recordError("logger_unavailable", nil)
	}
	validated, err := validateEvent(event)
	if err != nil {
		return recordError("invalid_event", err)
	}

	payload := logPayload{
		Operation:     validated.Operation(),
		LatencyMS:     validated.LatencyMS(),
		ModelID:       validated.ModelID(),
		SchemaVersion: validated.SchemaVersion(),
		ErrorCode:     validated.ErrorCode(),
	}

	logger.mutex.Lock()
	defer logger.mutex.Unlock()
	if err := json.NewEncoder(logger.writer).Encode(payload); err != nil {
		return recordError("write_failed", err)
	}
	return nil
}

type logPayload struct {
	Operation     Operation `json:"operation"`
	LatencyMS     int64     `json:"latency_ms"`
	ModelID       string    `json:"model_id,omitempty"`
	SchemaVersion string    `json:"schema_version,omitempty"`
	ErrorCode     string    `json:"error_code,omitempty"`
}

func validateEvent(event Event) (Event, error) {
	validated, err := NewEvent(event.Operation(), event.LatencyMS())
	if err != nil {
		return Event{}, err
	}
	validated, err = validated.WithModelID(event.ModelID())
	if err != nil {
		return Event{}, err
	}
	validated, err = validated.WithSchemaVersion(event.SchemaVersion())
	if err != nil {
		return Event{}, err
	}
	validated, err = validated.WithErrorCode(event.ErrorCode())
	if err != nil {
		return Event{}, err
	}
	return validated, nil
}

func recordError(reason string, cause error) error {
	return &RecordError{Reason: reason, cause: cause}
}

var _ = errors.Is
