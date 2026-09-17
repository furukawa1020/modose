package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"
)

func TestPublicProbeAliases(t *testing.T) {
	router := NewRouter(ReadinessProbeFunc(func(context.Context) error { return nil }))
	for _, path := range []string{"/health", "/healthz"} {
		assertResponse(t, router, http.MethodGet, path, http.StatusOK, `"status":"ok"`)
	}
	for _, path := range []string{"/ready", "/readyz"} {
		assertResponse(t, router, http.MethodGet, path, http.StatusOK, `"status":"ready"`)
	}
	for _, path := range []string{"/health", "/ready"} {
		response := assertResponse(t, router, http.MethodPost, path,
			http.StatusMethodNotAllowed, `"code":"method_not_allowed"`)
		if response.Header().Get("Allow") != http.MethodGet {
			t.Fatalf("%s Allow = %q", path, response.Header().Get("Allow"))
		}
	}
}

func TestPublicReadinessFailureRemainsFailClosed(t *testing.T) {
	for _, probe := range []ReadinessProbe{
		nil,
		ReadinessProbeFunc(func(context.Context) error {
			return errors.New("private dependency detail")
		}),
	} {
		response := assertResponse(t, NewRouter(probe), http.MethodGet, "/ready",
			http.StatusServiceUnavailable, `"code":"service_not_ready"`)
		if strings.Contains(response.Body.String(), "private dependency detail") {
			t.Fatal("readiness leaked dependency details")
		}
	}
}
