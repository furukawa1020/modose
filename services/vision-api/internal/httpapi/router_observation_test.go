package httpapi

import (
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
	"time"

	"github.com/furukawa1020/modose/services/vision-api/internal/observability"
)

type routerObservationRecorder struct {
	operations []observability.Operation
}

func (recorder *routerObservationRecorder) Record(
	event observability.Event,
) error {
	recorder.operations = append(recorder.operations, event.Operation())
	return nil
}

func TestVisionRouterObservesOnlyBusinessRoutes(t *testing.T) {
	t.Parallel()

	recorder := &routerObservationRecorder{}
	now := func() time.Time {
		return time.Date(2026, 8, 30, 12, 0, 0, 0, time.UTC)
	}
	router := NewVisionRouter(nil, VisionAnalyzers{
		Observation: ObservationConfig{
			Recorder: recorder,
			Now:      now,
		},
	})
	requests := []struct {
		method string
		path   string
	}{
		{method: http.MethodGet, path: "/healthz"},
		{method: http.MethodGet, path: "/readyz"},
		{method: http.MethodPost, path: "/v1/vision/baseline"},
		{method: http.MethodPost, path: "/v1/vision/compare"},
		{method: http.MethodPost, path: "/v1/vision/verify"},
		{method: http.MethodPost, path: "/v1/scenes/metadata"},
		{method: http.MethodDelete, path: "/v1/scenes/scene-1"},
	}

	for _, request := range requests {
		router.ServeHTTP(
			httptest.NewRecorder(),
			httptest.NewRequest(request.method, request.path, nil),
		)
	}

	want := []observability.Operation{
		observability.OperationBaseline,
		observability.OperationCompare,
		observability.OperationVerify,
		observability.OperationMetadataStore,
		observability.OperationMetadataDelete,
	}
	if !reflect.DeepEqual(recorder.operations, want) {
		t.Fatalf("operations = %#v, want %#v", recorder.operations, want)
	}
}

func TestVisionRouterObservesMethodRejection(t *testing.T) {
	t.Parallel()

	recorder := &routerObservationRecorder{}
	router := NewVisionRouter(nil, VisionAnalyzers{
		Observation: ObservationConfig{
			Recorder: recorder,
			Now:      time.Now,
		},
	})
	response := httptest.NewRecorder()

	router.ServeHTTP(
		response,
		httptest.NewRequest(http.MethodGet, "/v1/vision/baseline", nil),
	)

	if response.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d", response.Code)
	}
	if !reflect.DeepEqual(
		recorder.operations,
		[]observability.Operation{observability.OperationBaseline},
	) {
		t.Fatalf("operations = %#v", recorder.operations)
	}
}
