package observability

import (
	"errors"
	"strings"
	"testing"
)

type metricSinkStub struct {
	latencyOperation Operation
	latencyMS        int64
	errorOperation   Operation
	errorCode        string
	latencyCalls     int
	errorCalls       int
	latencyErr       error
	errorErr         error
}

func (sink *metricSinkStub) ObserveLatency(
	operation Operation,
	latencyMS int64,
) error {
	sink.latencyCalls++
	sink.latencyOperation = operation
	sink.latencyMS = latencyMS
	return sink.latencyErr
}

func (sink *metricSinkStub) IncrementError(
	operation Operation,
	errorCode string,
) error {
	sink.errorCalls++
	sink.errorOperation = operation
	sink.errorCode = errorCode
	return sink.errorErr
}

func TestMetricsRecorderAlwaysObservesLatency(t *testing.T) {
	t.Parallel()

	event, err := NewEvent(OperationBaseline, 1250)
	if err != nil {
		t.Fatal(err)
	}
	sink := &metricSinkStub{}

	if err := NewMetricsRecorder(sink).Record(event); err != nil {
		t.Fatalf("Record() error = %v", err)
	}

	if sink.latencyCalls != 1 ||
		sink.latencyOperation != OperationBaseline ||
		sink.latencyMS != 1250 {
		t.Fatalf(
			"latency calls = %d, operation = %q, value = %d",
			sink.latencyCalls,
			sink.latencyOperation,
			sink.latencyMS,
		)
	}
	if sink.errorCalls != 0 {
		t.Fatalf("error calls = %d, want 0", sink.errorCalls)
	}
}

func TestMetricsRecorderCountsOnlyValidatedPublicError(t *testing.T) {
	t.Parallel()

	event, err := NewEvent(OperationVerify, 430)
	if err != nil {
		t.Fatal(err)
	}
	event, err = event.WithErrorCode("schema_invalid")
	if err != nil {
		t.Fatal(err)
	}
	sink := &metricSinkStub{}

	if err := NewMetricsRecorder(sink).Record(event); err != nil {
		t.Fatal(err)
	}

	if sink.latencyCalls != 1 || sink.errorCalls != 1 {
		t.Fatalf(
			"latency calls = %d, error calls = %d",
			sink.latencyCalls,
			sink.errorCalls,
		)
	}
	if sink.errorOperation != OperationVerify ||
		sink.errorCode != "schema_invalid" {
		t.Fatalf(
			"error operation = %q, code = %q",
			sink.errorOperation,
			sink.errorCode,
		)
	}
}

func TestMetricsRecorderRejectsInvalidEventBeforeSink(t *testing.T) {
	t.Parallel()

	sink := &metricSinkStub{}
	err := NewMetricsRecorder(sink).Record(Event{})

	assertMetricRecordReason(t, err, "invalid_event")
	if sink.latencyCalls != 0 || sink.errorCalls != 0 {
		t.Fatalf(
			"latency calls = %d, error calls = %d",
			sink.latencyCalls,
			sink.errorCalls,
		)
	}
}

func TestMetricsRecorderRejectsUnavailableSink(t *testing.T) {
	t.Parallel()

	err := NewMetricsRecorder(nil).Record(Event{})
	assertMetricRecordReason(t, err, "sink_unavailable")
}

func TestMetricsRecorderAttemptsBothWritesAndSanitizesFailures(t *testing.T) {
	t.Parallel()

	latencyErr := errors.New("private latency backend detail")
	errorErr := errors.New("private counter backend detail")
	sink := &metricSinkStub{
		latencyErr: latencyErr,
		errorErr:   errorErr,
	}
	event, err := NewEvent(OperationCompare, 700)
	if err != nil {
		t.Fatal(err)
	}
	event, err = event.WithErrorCode("upstream_unavailable")
	if err != nil {
		t.Fatal(err)
	}

	err = NewMetricsRecorder(sink).Record(event)

	assertMetricRecordReason(t, err, "sink_write_failed")
	if sink.latencyCalls != 1 || sink.errorCalls != 1 {
		t.Fatalf(
			"latency calls = %d, error calls = %d",
			sink.latencyCalls,
			sink.errorCalls,
		)
	}
	if !errors.Is(err, latencyErr) || !errors.Is(err, errorErr) {
		t.Fatal("sink failures were not preserved as causes")
	}
	if strings.Contains(err.Error(), latencyErr.Error()) ||
		strings.Contains(err.Error(), errorErr.Error()) {
		t.Fatalf("public error leaked sink detail: %v", err)
	}
}

func assertMetricRecordReason(t *testing.T, err error, want string) {
	t.Helper()

	var recordErr *MetricRecordError
	if !errors.As(err, &recordErr) {
		t.Fatalf("error = %v, want MetricRecordError", err)
	}
	if recordErr.Reason != want {
		t.Fatalf("reason = %q, want %q", recordErr.Reason, want)
	}
}
