package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMetadataRoutesAreRegisteredWithFixedMethods(t *testing.T) {
	router := NewVisionRouter(nil, VisionAnalyzers{})

	tests := []struct {
		name   string
		method string
		path   string
		want   int
		allow  string
	}{
		{"store", http.MethodPost, "/v1/scenes/metadata", http.StatusUnauthorized, ""},
		{"delete", http.MethodDelete, "/v1/scenes/scene-1", http.StatusUnauthorized, ""},
		{"store method", http.MethodGet, "/v1/scenes/metadata", http.StatusMethodNotAllowed, http.MethodPost},
		{"delete method", http.MethodPost, "/v1/scenes/scene-1", http.StatusMethodNotAllowed, http.MethodDelete},
		{"missing scene path", http.MethodDelete, "/v1/scenes", http.StatusNotFound, ""},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			request := httptest.NewRequest(test.method, test.path, nil)
			recorder := httptest.NewRecorder()

			router.ServeHTTP(recorder, request)

			if recorder.Code != test.want {
				t.Fatalf("status = %d, want %d", recorder.Code, test.want)
			}
			if test.allow != "" && recorder.Header().Get("Allow") != test.allow {
				t.Fatalf("Allow = %q, want %q", recorder.Header().Get("Allow"), test.allow)
			}
		})
	}
}
