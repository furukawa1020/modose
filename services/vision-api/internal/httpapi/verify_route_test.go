package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestVerifyRouteIsRegisteredAsPostOnly(t *testing.T) {
	router := NewVisionRouter(nil, VisionAnalyzers{})

	post := httptest.NewRequest(http.MethodPost, "/v1/vision/verify", nil)
	postRecorder := httptest.NewRecorder()
	router.ServeHTTP(postRecorder, post)
	if postRecorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("POST status = %d, want %d", postRecorder.Code, http.StatusServiceUnavailable)
	}

	get := httptest.NewRequest(http.MethodGet, "/v1/vision/verify", nil)
	getRecorder := httptest.NewRecorder()
	router.ServeHTTP(getRecorder, get)
	if getRecorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("GET status = %d, want %d", getRecorder.Code, http.StatusMethodNotAllowed)
	}
	if getRecorder.Header().Get("Allow") != http.MethodPost {
		t.Fatalf("Allow = %q, want POST", getRecorder.Header().Get("Allow"))
	}
}
