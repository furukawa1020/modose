package httpapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/appidentity"
	"github.com/furukawa1020/modose/services/vision-api/internal/identity"
)

func TestVisionRoutesRequireAppCheckAfterIDToken(t *testing.T) {
	idVerifier := &wiredIDTokenVerifier{}
	appVerifier := &wiredAppCheckVerifier{}
	router := NewVisionRouter(
		ReadinessProbeFunc(func(context.Context) error { return nil }),
		VisionAnalyzers{
			IDTokenVerifier:  idVerifier,
			AppCheckVerifier: appVerifier,
		},
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
			request := httptest.NewRequest(test.method, test.path, nil)
			request.Header.Set("Authorization", "Bearer id-token")
			recorder := httptest.NewRecorder()

			router.ServeHTTP(recorder, request)

			if recorder.Code != http.StatusForbidden {
				t.Fatalf("status = %d, want %d", recorder.Code, http.StatusForbidden)
			}
		})
	}
	if idVerifier.calls != len(tests) {
		t.Fatalf("id verifier calls = %d, want %d", idVerifier.calls, len(tests))
	}
	if appVerifier.calls != 0 {
		t.Fatalf("app verifier calls = %d, want 0", appVerifier.calls)
	}
}

func TestVisionRouteAcceptsBothFirebaseTokens(t *testing.T) {
	idVerifier := &wiredIDTokenVerifier{}
	appVerifier := &wiredAppCheckVerifier{}
	router := NewVisionRouter(
		nil,
		VisionAnalyzers{
			IDTokenVerifier:  idVerifier,
			AppCheckVerifier: appVerifier,
		},
	)
	request := httptest.NewRequest(http.MethodPost, "/v1/vision/baseline", nil)
	request.Header.Set("Authorization", "Bearer id-token")
	request.Header.Set(appCheckHeader, "app-check-token")
	recorder := httptest.NewRecorder()

	router.ServeHTTP(recorder, request)

	if recorder.Code == http.StatusUnauthorized || recorder.Code == http.StatusForbidden {
		t.Fatalf("authenticated request status = %d", recorder.Code)
	}
	if idVerifier.calls != 1 || appVerifier.calls != 1 {
		t.Fatalf("id calls = %d, app calls = %d", idVerifier.calls, appVerifier.calls)
	}
}

func TestPartiallyConfiguredFirebaseVerificationFailsClosed(t *testing.T) {
	router := NewVisionRouter(
		nil,
		VisionAnalyzers{IDTokenVerifier: &wiredIDTokenVerifier{}},
	)
	request := httptest.NewRequest(http.MethodPost, "/v1/vision/baseline", nil)
	request.Header.Set("Authorization", "Bearer id-token")
	request.Header.Set(appCheckHeader, "app-check-token")
	recorder := httptest.NewRecorder()

	router.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusForbidden)
	}
}

type wiredIDTokenVerifier struct {
	calls int
}

func (verifier *wiredIDTokenVerifier) VerifyIDToken(
	context.Context,
	string,
) (identity.Principal, error) {
	verifier.calls++
	return identity.Principal{UID: "verified-user"}, nil
}

type wiredAppCheckVerifier struct {
	calls int
}

func (verifier *wiredAppCheckVerifier) VerifyAppCheckToken(
	context.Context,
	string,
) (appidentity.VerifiedApp, error) {
	verifier.calls++
	return appidentity.VerifiedApp{
		AppID: "1:1234567890:android:abcdef",
	}, nil
}
