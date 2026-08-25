package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestCompareRouteIsRegistered(t *testing.T) {
	router := NewVisionRouter(nil, VisionAnalyzers{})

	post := httptest.NewRequest(http.MethodPost, "/v1/vision/compare", nil)
	postRecorder := httptest.NewRecorder()
	router.ServeHTTP(postRecorder, post)
	if postRecorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("POST status = %d, want %d", postRecorder.Code, http.StatusServiceUnavailable)
	}

	get := httptest.NewRequest(http.MethodGet, "/v1/vision/compare", nil)
	getRecorder := httptest.NewRecorder()
	router.ServeHTTP(getRecorder, get)
	if getRecorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET status = %d, want %d", getRecorder.Code, http.StatusMethodNotAllowed)
	}
	if getRecorder.Header().Get("Allow") != http.MethodPost {
		t.Fatalf("Allow = %q, want POST", getRecorder.Header().Get("Allow"))
	}
}

func TestLegacyRouterConstructorsRemainAvailable(t *testing.T) {
	probe := ReadinessProbeFunc(func(_ context.Context) error { return nil })
	routers := []*Router{
		NewRouter(probe),
		NewRouterWithBaseline(probe, nil),
	}
	for index, router := range routers {
		request := httptest.NewRequest(http.MethodGet, "/healthz", nil)
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusOK {
			t.Fatalf("router %d status = %d", index, recorder.Code)
		}
	}
}
