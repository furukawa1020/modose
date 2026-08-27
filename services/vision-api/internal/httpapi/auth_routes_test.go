package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/identity"
)

func TestVisionRoutesRequireIDToken(t *testing.T) {
	verifier := &fakeRouteIDTokenVerifier{}
	router := NewVisionRouter(
		ReadinessProbeFunc(func(context.Context) error { return nil }),
		VisionAnalyzers{IDTokenVerifier: verifier},
	)
	tests := []struct {
		method string
		path   string
	}{
		{method: http.MethodPost, path: "/v1/vision/baseline"},
		{method: http.MethodPost, path: "/v1/vision/compare"},
		{method: http.MethodPost, path: "/v1/vision/verify"},
		{method: http.MethodPost, path: "/v1/scenes/metadata"},
		{method: http.MethodDelete, path: "/v1/scenes/scene-1"},
	}
	for _, test := range tests {
		t.Run(test.method+" "+test.path, func(t *testing.T) {
			recorder := httptest.NewRecorder()
			router.ServeHTTP(
				recorder,
				httptest.NewRequest(test.method, test.path, nil),
			)
			if recorder.Code != http.StatusUnauthorized {
				t.Fatalf("status = %d, want %d", recorder.Code, http.StatusUnauthorized)
			}
		})
	}
	if verifier.calls != 0 {
		t.Fatalf("verifier calls = %d, want 0", verifier.calls)
	}
}

func TestHealthRoutesRemainUnauthenticated(t *testing.T) {
	router := NewVisionRouter(
		ReadinessProbeFunc(func(context.Context) error { return nil }),
		VisionAnalyzers{},
	)

	for _, path := range []string{"/healthz", "/readyz"} {
		recorder := httptest.NewRecorder()
		router.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, path, nil))
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s status = %d, want %d", path, recorder.Code, http.StatusOK)
		}
	}
}

func TestMethodRejectionRunsBeforeAuthentication(t *testing.T) {
	verifier := &fakeRouteIDTokenVerifier{}
	router := NewVisionRouter(nil, VisionAnalyzers{IDTokenVerifier: verifier})
	recorder := httptest.NewRecorder()

	router.ServeHTTP(
		recorder,
		httptest.NewRequest(http.MethodGet, "/v1/vision/baseline", nil),
	)

	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusMethodNotAllowed)
	}
	if verifier.calls != 0 {
		t.Fatalf("verifier calls = %d, want 0", verifier.calls)
	}
}

func TestConfiguredVerifierRunsBeforeProtectedHandler(t *testing.T) {
	verifier := &fakeRouteIDTokenVerifier{}
	router := NewVisionRouter(nil, VisionAnalyzers{IDTokenVerifier: verifier})
	request := httptest.NewRequest(http.MethodPost, "/v1/vision/baseline", nil)
	request.Header.Set("Authorization", "Bearer signed-token")
	recorder := httptest.NewRecorder()

	router.ServeHTTP(recorder, request)

	if recorder.Code == http.StatusUnauthorized {
		t.Fatal("authenticated request was rejected as unauthorized")
	}
	if verifier.calls != 1 || verifier.token != "signed-token" {
		t.Fatalf("calls = %d, token = %q", verifier.calls, verifier.token)
	}
}

type fakeRouteIDTokenVerifier struct {
	calls int
	token string
}

func (verifier *fakeRouteIDTokenVerifier) VerifyIDToken(
	_ context.Context,
	rawToken string,
) (identity.Principal, error) {
	verifier.calls++
	verifier.token = rawToken
	return identity.Principal{UID: "verified-user"}, nil
}
