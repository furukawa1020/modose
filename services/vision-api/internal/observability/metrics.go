package observability

import (
	"errors"
	"fmt"
)

type MetricSink interface {
	ObserveLatency(Operation, int64) error
	IncrementError(Operation, string) error
}

type MetricRecordError struct {
	Reason string
	cause  error
}

func (e *MetricRecordError) Error() string {
	return fmt.Sprintf("observation metric record failed: %s", e.Reason)
}

func (e *MetricRecordError) Unwrap() error {
	return e.cause
}

type MetricsRecorder struct {
	sink MetricSink
}

var _ Recorder = (*MetricsRecorder)(nil)

func NewMetricsRecorder(sink MetricSink) *MetricsRecorder {
	return &MetricsRecorder{sink: sink}
}

func (recorder *MetricsRecorder) Record(event Event) error {
	if recorder == nil || recorder.sink == nil {
		return metricRecordError("sink_unavailable", nil)
	}
	validated, err := validateEvent(event)
	if err != nil {
		return metricRecordError("invalid_event", err)
	}

	var failures []error
	if err := recorder.sink.ObserveLatency(
		validated.Operation(),
		validated.LatencyMS(),
	); err != nil {
		failures = append(failures, err)
	}
	if validated.ErrorCode() != "" {
		if err := recorder.sink.IncrementError(
			validated.Operation(),
			validated.ErrorCode(),
		); err != nil {
			failures = append(failures, err)
		}
	}
	if len(failures) > 0 {
		return metricRecordError("sink_write_failed", errors.Join(failures...))
	}
	return nil
}

func metricRecordError(reason string, cause error) error {
	return &MetricRecordError{Reason: reason, cause: cause}
}
