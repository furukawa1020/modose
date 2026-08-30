package httpapi

import (
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/furukawa1020/modose/services/vision-api/internal/apierror"
	"github.com/furukawa1020/modose/services/vision-api/internal/observability"
)

type observationRecorderStub struct {
	events []observability.Event
	err    error
}

func (recorder *observationRecorderStub) Record(
	event observability.Event,
) error {
	recorder.events = append(recorder.events, event)
	return recorder.err
}

func TestObserveRequestRecordsOnlySafePublicFields(t *testing.T) {
	t.Parallel()

	recorder := &observationRecorderStub{}
	clock := sequenceClock(
		time.Date(2026, 8, 30, 1, 0, 0, 0, time.UTC),
		time.Date(2026, 8, 30, 1, 0, 0, 125*time.Millisecond, time.UTC),
	)
	handler := observeRequest(
		observability.OperationBaseline,
		ObservationConfig{
			Recorder:      recorder,
			ModelID:       "gemini-3.5-flash",
			SchemaVersion: "1.0",
			Now:           clock,
		},
		http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
			apierror.Write(writer, http.StatusBadRequest, apierror.Error{
				Code:    "invalid_request",
				Message: "The request is invalid.",
			})
		}),
	)
	request := httptest.NewRequest(
		http.MethodPost,
		"/v1/vision/baseline",
		strings.NewReader("private image bytes and token"),
	)
	recorderHTTP := httptest.NewRecorder()

	handler.ServeHTTP(recorderHTTP, request)

	if recorderHTTP.Code != http.StatusBadRequest {
		t.Fatalf("status = %d", recorderHTTP.Code)
	}
	if len(recorder.events) != 1 {
		t.Fatalf("events = %d", len(recorder.events))
	}
	event := recorder.events[0]
	if event.Operation() != observability.OperationBaseline ||
		event.LatencyMS() != 125 ||
		event.ModelID() != "gemini-3.5-flash" ||
		event.SchemaVersion() != "1.0" ||
		event.ErrorCode() != "invalid_request" {
		t.Fatalf("event fields were not mapped safely")
	}
}

func TestObserveRequestRecordsSuccessWithoutErrorCode(t *testing.T) {
	t.Parallel()

	recorder := &observationRecorderStub{}
	handler := observeRequest(
		observability.OperationMetadataDelete,
		ObservationConfig{
			Recorder: recorder,
			Now: sequenceClock(
				time.Unix(0, 0),
				time.Unix(0, int64(4*time.Millisecond)),
			),
		},
		http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
			writer.WriteHeader(http.StatusNoContent)
		}),
	)
	recorderHTTP := httptest.NewRecorder()

	handler.ServeHTTP(
		recorderHTTP,
		httptest.NewRequest(http.MethodDelete, "/v1/scenes/scene-1", nil),
	)

	if recorderHTTP.Code != http.StatusNoContent ||
		len(recorder.events) != 1 ||
		recorder.events[0].ErrorCode() != "" {
		t.Fatalf(
			"status = %d, events = %#v",
			recorderHTTP.Code,
			recorder.events,
		)
	}
}

func TestObserveRequestFailureNeverChangesHTTPResponse(t *testing.T) {
	t.Parallel()

	recorder := &observationRecorderStub{
		err: errors.New("private observation backend detail"),
	}
	handler := observeRequest(
		observability.OperationCompare,
		ObservationConfig{
			Recorder: recorder,
			Now: sequenceClock(
				time.Unix(0, 0),
				time.Unix(0, int64(time.Millisecond)),
			),
		},
		http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
			writer.WriteHeader(http.StatusAccepted)
		}),
	)
	recorderHTTP := httptest.NewRecorder()

	handler.ServeHTTP(
		recorderHTTP,
		httptest.NewRequest(http.MethodPost, "/v1/vision/compare", nil),
	)

	if recorderHTTP.Code != http.StatusAccepted {
		t.Fatalf("status = %d", recorderHTTP.Code)
	}
	if len(recorder.events) != 1 {
		t.Fatalf("events = %d", len(recorder.events))
	}
}

func TestObserveRequestInvalidConfigurationSkipsRecording(t *testing.T) {
	t.Parallel()

	recorder := &observationRecorderStub{}
	handler := observeRequest(
		observability.OperationVerify,
		ObservationConfig{
			Recorder: recorder,
			ModelID:  "unsafe model value",
			Now: sequenceClock(
				time.Unix(0, 0),
				time.Unix(0, int64(time.Millisecond)),
			),
		},
		http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
			writer.WriteHeader(http.StatusOK)
		}),
	)
	recorderHTTP := httptest.NewRecorder()

	handler.ServeHTTP(
		recorderHTTP,
		httptest.NewRequest(http.MethodPost, "/v1/vision/verify", nil),
	)

	if recorderHTTP.Code != http.StatusOK || len(recorder.events) != 0 {
		t.Fatalf(
			"status = %d, events = %d",
			recorderHTTP.Code,
			len(recorder.events),
		)
	}
}

func sequenceClock(times ...time.Time) func() time.Time {
	index := 0
	return func() time.Time {
		value := times[index]
		index++
		return value
	}
}
