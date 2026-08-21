package httpapi

import (
	"context"
	"net/http"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/baselineapi"
)

func TestRouterServesBaselineOnlyOnPost(t *testing.T) {
	analyzer := baselineAnalyzerFunc(func(context.Context, []byte) (baselineapi.Output, error) {
		return validBaselineOutput(), nil
	})
	router := NewRouterWithBaseline(
		ReadinessProbeFunc(func(context.Context) error { return nil }),
		analyzer,
	)

	recorder := serveBaseline(t, router, []byte{1}, "image/jpeg", true)
	if recorder.Code != http.StatusOK {
		t.Fatalf("POST status = %d, body = %s", recorder.Code, recorder.Body.String())
	}

	recorder = assertResponse(
		t,
		router,
		http.MethodGet,
		"/v1/vision/baseline",
		http.StatusMethodNotAllowed,
		`"code":"method_not_allowed"`,
	)
	if recorder.Header().Get("Allow") != http.MethodPost {
		t.Fatalf("Allow = %q, want POST", recorder.Header().Get("Allow"))
	}
}

func TestRouterKeepsBaselineKnownWhenDependencyIsUnavailable(t *testing.T) {
	router := NewRouter(ReadinessProbeFunc(func(context.Context) error { return nil }))
	recorder := serveBaseline(t, router, []byte{1}, "image/jpeg", true)
	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
}
