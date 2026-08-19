package httpapi

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestHealthAndReadiness(t *testing.T) {
	router := NewRouter(ReadinessProbeFunc(func(context.Context) error { return nil }))

	assertResponse(t, router, http.MethodGet, "/healthz", http.StatusOK, `"status":"ok"`)
	assertResponse(t, router, http.MethodGet, "/readyz", http.StatusOK, `"status":"ready"`)
}

func TestReadinessFailureReturnsTypedServiceUnavailable(t *testing.T) {
	router := NewRouter(ReadinessProbeFunc(func(context.Context) error {
		return errors.New("private dependency detail")
	}))

	recorder := assertResponse(
		t,
		router,
		http.MethodGet,
		"/readyz",
		http.StatusServiceUnavailable,
		`"code":"service_not_ready"`,
	)
	if strings.Contains(recorder.Body.String(), "private dependency detail") {
		t.Fatalf("response leaked probe error: %s", recorder.Body.String())
	}
}

func TestUnknownPathAndUnsupportedMethodUseTypedErrors(t *testing.T) {
	router := NewRouter(ReadinessProbeFunc(func(context.Context) error { return nil }))

	assertResponse(t, router, http.MethodGet, "/unknown", http.StatusNotFound, `"code":"not_found"`)
	recorder := assertResponse(
		t,
		router,
		http.MethodPost,
		"/healthz",
		http.StatusMethodNotAllowed,
		`"code":"method_not_allowed"`,
	)
	if recorder.Header().Get("Allow") != http.MethodGet {
		t.Fatalf("Allow = %q", recorder.Header().Get("Allow"))
	}
}

func TestNilProbeIsNotReady(t *testing.T) {
	assertResponse(
		t,
		NewRouter(nil),
		http.MethodGet,
		"/readyz",
		http.StatusServiceUnavailable,
		`"code":"service_not_ready"`,
	)
}

func assertResponse(
	t *testing.T,
	handler http.Handler,
	method string,
	path string,
	wantStatus int,
	wantBody string,
) *httptest.ResponseRecorder {
	t.Helper()
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(method, path, nil))
	if recorder.Code != wantStatus {
		t.Fatalf("%s %s status = %d, want %d", method, path, recorder.Code, wantStatus)
	}
	if !strings.Contains(recorder.Body.String(), wantBody) {
		t.Fatalf("%s %s body = %s, want %s", method, path, recorder.Body.String(), wantBody)
	}
	return recorder
}
