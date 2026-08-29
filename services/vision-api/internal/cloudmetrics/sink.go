package cloudmetrics

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	monitoring "cloud.google.com/go/monitoring/apiv3"
	"cloud.google.com/go/monitoring/apiv3/v2/monitoringpb"
	"google.golang.org/genproto/googleapis/api/metric"
	"google.golang.org/genproto/googleapis/api/monitoredres"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/furukawa1020/modose/services/vision-api/internal/observability"
)

const (
	latencyMetricType = "custom.googleapis.com/modose/api_latency_ms"
	errorMetricType   = "custom.googleapis.com/modose/api_error_count"
	maxErrorSeries    = 195
)

type createTimeSeries func(context.Context, *monitoringpb.CreateTimeSeriesRequest) error

type latencyBucket struct {
	sum   int64
	count int64
}

type errorKey struct {
	operation observability.Operation
	code      string
}

type Sink struct {
	project string
	create  createTimeSeries
	now     func() time.Time
	mutex   sync.Mutex
	latency map[observability.Operation]latencyBucket
	errors  map[errorKey]int64
}

type Error struct {
	Reason string
	cause  error
}

func (e *Error) Error() string {
	return fmt.Sprintf("cloud metric operation failed: %s", e.Reason)
}

func (e *Error) Unwrap() error {
	return e.cause
}

var _ observability.MetricSink = (*Sink)(nil)

func New(
	client *monitoring.MetricClient,
	project string,
) (*Sink, error) {
	if client == nil {
		return nil, cloudError("client_required", nil)
	}
	return newSink(project, client.CreateTimeSeries, time.Now)
}

func (sink *Sink) ObserveLatency(
	operation observability.Operation,
	latencyMS int64,
) error {
	if _, err := observability.NewEvent(operation, latencyMS); err != nil {
		return cloudError("invalid_metric", err)
	}
	sink.mutex.Lock()
	defer sink.mutex.Unlock()
	bucket := sink.latency[operation]
	bucket.sum += latencyMS
	bucket.count++
	sink.latency[operation] = bucket
	return nil
}

func (sink *Sink) IncrementError(
	operation observability.Operation,
	code string,
) error {
	event, err := observability.NewEvent(operation, 0)
	if err == nil {
		_, err = event.WithErrorCode(code)
	}
	if err != nil || strings.TrimSpace(code) == "" {
		return cloudError("invalid_metric", err)
	}

	sink.mutex.Lock()
	defer sink.mutex.Unlock()
	key := errorKey{operation: operation, code: code}
	if _, exists := sink.errors[key]; !exists && len(sink.errors) >= maxErrorSeries {
		return cloudError("series_limit", nil)
	}
	sink.errors[key]++
	return nil
}

func (sink *Sink) Flush(ctx context.Context) error {
	if sink == nil || sink.create == nil {
		return cloudError("sink_unavailable", nil)
	}
	latency, errorCounts := sink.snapshot()
	if len(latency) == 0 && len(errorCounts) == 0 {
		return nil
	}
	request := &monitoringpb.CreateTimeSeriesRequest{
		Name:       "projects/" + sink.project,
		TimeSeries: sink.timeSeries(latency, errorCounts, sink.now()),
	}
	if err := sink.create(ctx, request); err != nil {
		return cloudError("write_failed", err)
	}
	sink.commit(latency, errorCounts)
	return nil
}

func newSink(
	project string,
	create createTimeSeries,
	now func() time.Time,
) (*Sink, error) {
	project = strings.TrimSpace(project)
	if project == "" || strings.ContainsAny(project, "/\\ \t\r\n") {
		return nil, cloudError("invalid_project", nil)
	}
	return &Sink{
		project: project, create: create, now: now,
		latency: make(map[observability.Operation]latencyBucket),
		errors:  make(map[errorKey]int64),
	}, nil
}

func (sink *Sink) snapshot() (
	map[observability.Operation]latencyBucket,
	map[errorKey]int64,
) {
	sink.mutex.Lock()
	defer sink.mutex.Unlock()
	latency := make(map[observability.Operation]latencyBucket, len(sink.latency))
	for key, value := range sink.latency {
		latency[key] = value
	}
	errors := make(map[errorKey]int64, len(sink.errors))
	for key, value := range sink.errors {
		errors[key] = value
	}
	return latency, errors
}

func (sink *Sink) commit(
	latency map[observability.Operation]latencyBucket,
	errors map[errorKey]int64,
) {
	sink.mutex.Lock()
	defer sink.mutex.Unlock()
	for key, flushed := range latency {
		current := sink.latency[key]
		current.sum -= flushed.sum
		current.count -= flushed.count
		if current.count == 0 {
			delete(sink.latency, key)
		} else {
			sink.latency[key] = current
		}
	}
	for key, flushed := range errors {
		sink.errors[key] -= flushed
		if sink.errors[key] == 0 {
			delete(sink.errors, key)
		}
	}
}

func (sink *Sink) timeSeries(
	latency map[observability.Operation]latencyBucket,
	errors map[errorKey]int64,
	at time.Time,
) []*monitoringpb.TimeSeries {
	series := make([]*monitoringpb.TimeSeries, 0, len(latency)+len(errors))
	for operation, bucket := range latency {
		series = append(series, sink.series(
			latencyMetricType,
			map[string]string{"operation": string(operation)},
			&monitoringpb.TypedValue{Value: &monitoringpb.TypedValue_DoubleValue{
				DoubleValue: float64(bucket.sum) / float64(bucket.count),
			}},
			at,
		))
	}
	for key, count := range errors {
		series = append(series, sink.series(
			errorMetricType,
			map[string]string{
				"operation":  string(key.operation),
				"error_code": key.code,
			},
			&monitoringpb.TypedValue{Value: &monitoringpb.TypedValue_Int64Value{
				Int64Value: count,
			}},
			at,
		))
	}
	return series
}

func (sink *Sink) series(
	metricType string,
	labels map[string]string,
	value *monitoringpb.TypedValue,
	at time.Time,
) *monitoringpb.TimeSeries {
	return &monitoringpb.TimeSeries{
		Metric: &metric.Metric{Type: metricType, Labels: labels},
		Resource: &monitoredres.MonitoredResource{
			Type: "global", Labels: map[string]string{"project_id": sink.project},
		},
		Points: []*monitoringpb.Point{{
			Interval: &monitoringpb.TimeInterval{EndTime: timestamppb.New(at)},
			Value:    value,
		}},
	}
}

func cloudError(reason string, cause error) error {
	return &Error{Reason: reason, cause: cause}
}
