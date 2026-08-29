package cloudmetrics

import (
	"context"
	"errors"
	"strings"
	"testing"
	"time"

	"cloud.google.com/go/monitoring/apiv3/v2/monitoringpb"

	"github.com/furukawa1020/modose/services/vision-api/internal/observability"
)

func TestSinkFlushesAggregatedPrivacySafeSeries(t *testing.T) {
	at := time.Date(2026, 8, 30, 1, 2, 3, 0, time.UTC)
	var request *monitoringpb.CreateTimeSeriesRequest
	sink, err := newSink("modose-test", func(
		_ context.Context,
		got *monitoringpb.CreateTimeSeriesRequest,
	) error {
		request = got
		return nil
	}, func() time.Time { return at })
	if err != nil {
		t.Fatal(err)
	}
	if err := sink.ObserveLatency(observability.OperationBaseline, 100); err != nil {
		t.Fatal(err)
	}
	if err := sink.ObserveLatency(observability.OperationBaseline, 300); err != nil {
		t.Fatal(err)
	}
	if err := sink.IncrementError(
		observability.OperationBaseline,
		"invalid_request",
	); err != nil {
		t.Fatal(err)
	}

	if err := sink.Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	if request.Name != "projects/modose-test" || len(request.TimeSeries) != 2 {
		t.Fatalf("request = %#v", request)
	}
	values := map[string]float64{}
	for _, series := range request.TimeSeries {
		if series.Resource.Type != "global" ||
			series.Resource.Labels["project_id"] != "modose-test" {
			t.Fatalf("resource = %#v", series.Resource)
		}
		if len(series.Points) != 1 ||
			!series.Points[0].Interval.EndTime.AsTime().Equal(at) {
			t.Fatalf("points = %#v", series.Points)
		}
		values[series.Metric.Type] = series.Points[0].Value.GetDoubleValue()
		if series.Metric.Type == errorMetricType {
			values[series.Metric.Type] = float64(series.Points[0].Value.GetInt64Value())
			if series.Metric.Labels["error_code"] != "invalid_request" {
				t.Fatalf("labels = %#v", series.Metric.Labels)
			}
		}
	}
	if values[latencyMetricType] != 200 || values[errorMetricType] != 1 {
		t.Fatalf("values = %#v", values)
	}
}

func TestSinkRetainsFailedFlushAndClearsSuccessfulFlush(t *testing.T) {
	privateErr := errors.New("private monitoring destination")
	calls := 0
	sink, err := newSink("modose-test", func(
		context.Context,
		*monitoringpb.CreateTimeSeriesRequest,
	) error {
		calls++
		if calls == 1 {
			return privateErr
		}
		return nil
	}, time.Now)
	if err != nil {
		t.Fatal(err)
	}
	if err := sink.ObserveLatency(observability.OperationCompare, 50); err != nil {
		t.Fatal(err)
	}

	err = sink.Flush(context.Background())
	var cloudErr *Error
	if !errors.As(err, &cloudErr) || cloudErr.Reason != "write_failed" {
		t.Fatalf("error = %v", err)
	}
	if !errors.Is(err, privateErr) || strings.Contains(err.Error(), privateErr.Error()) {
		t.Fatalf("error did not preserve and sanitize cause: %v", err)
	}
	if err := sink.Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	if err := sink.Flush(context.Background()); err != nil {
		t.Fatal(err)
	}
	if calls != 2 {
		t.Fatalf("calls = %d, want 2", calls)
	}
}

func TestSinkRejectsInvalidInputsBeforeAggregation(t *testing.T) {
	sink, err := newSink("modose-test", func(
		context.Context,
		*monitoringpb.CreateTimeSeriesRequest,
	) error { return nil }, time.Now)
	if err != nil {
		t.Fatal(err)
	}
	if err := sink.ObserveLatency("unknown", 1); err == nil {
		t.Fatal("unsupported operation accepted")
	}
	if err := sink.IncrementError(
		observability.OperationVerify,
		"Private Error",
	); err == nil {
		t.Fatal("unsafe error code accepted")
	}
	if _, err := newSink("../unsafe", nil, time.Now); err == nil {
		t.Fatal("unsafe project accepted")
	}
}
