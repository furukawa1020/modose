package httpapi

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/identity"
)

type fakeIDTokenVerifier struct {
	principal identity.Principal
	err       error
	calls     int
	token     string
}

func (verifier *fakeIDTokenVerifier) VerifyIDToken(
	_ context.Context,
	rawToken string,
) (identity.Principal, error) {
	verifier.calls++
	verifier.token = rawToken
	return verifier.principal, verifier.err
}

func TestRequireIDTokenForwardsVerifiedPrincipal(t *testing.T) {
	t.Parallel()

	verifier := &fakeIDTokenVerifier{principal: identity.Principal{UID: "user-123"}}
	next := http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		principal, ok := identity.FromContext(request.Context())
		if !ok || principal.UID != "user-123" {
			t.Fatalf("principal = %#v, ok = %v", principal, ok)
		}
		uid, ok := verifiedUID(request.Context())
		if !ok || uid != "user-123" {
			t.Fatalf("metadata uid = %q, ok = %v", uid, ok)
		}
		writer.WriteHeader(http.StatusNoContent)
	})
	request := httptest.NewRequest(http.MethodGet, "/protected", nil)
	request.Header.Set("Authorization", "Bearer signed-token")
	recorder := httptest.NewRecorder()

	requireIDToken(verifier, next).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusNoContent)
	}
	if verifier.calls != 1 || verifier.token != "signed-token" {
		t.Fatalf("calls = %d, token = %q", verifier.calls, verifier.token)
	}
}

func TestRequireIDTokenRejectsMalformedAuthorizationWithoutVerification(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name   string
		values []string
	}{
		{name: "missing"},
		{name: "basic", values: []string{"Basic credential"}},
		{name: "lowercase scheme", values: []string{"bearer token"}},
		{name: "empty bearer", values: []string{"Bearer "}},
		{name: "token with space", values: []string{"Bearer first second"}},
		{name: "duplicate", values: []string{"Bearer first", "Bearer second"}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			verifier := &fakeIDTokenVerifier{}
			request := httptest.NewRequest(http.MethodGet, "/protected", nil)
			for _, value := range test.values {
				request.Header.Add("Authorization", value)
			}
			recorder := httptest.NewRecorder()

			requireIDToken(verifier, http.HandlerFunc(unreachableHandler(t))).
				ServeHTTP(recorder, request)

			assertUnauthorized(t, recorder)
			if verifier.calls != 0 {
				t.Fatalf("verifier calls = %d, want 0", verifier.calls)
			}
		})
	}
}

func TestRequireIDTokenDoesNotLeakVerificationFailure(t *testing.T) {
	t.Parallel()

	privateErr := errors.New("private Firebase token detail")
	verifier := &fakeIDTokenVerifier{err: privateErr}
	request := httptest.NewRequest(http.MethodGet, "/protected", nil)
	request.Header.Set("Authorization", "Bearer secret-token")
	recorder := httptest.NewRecorder()

	requireIDToken(verifier, http.HandlerFunc(unreachableHandler(t))).
		ServeHTTP(recorder, request)

	assertUnauthorized(t, recorder)
	if strings.Contains(recorder.Body.String(), privateErr.Error()) ||
		strings.Contains(recorder.Body.String(), "secret-token") {
		t.Fatalf("response leaked authentication detail: %s", recorder.Body.String())
	}
}

func TestRequireIDTokenRejectsInvalidPrincipalAndMissingVerifier(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name     string
		verifier identity.IDTokenVerifier
	}{
		{
			name:     "unsafe uid",
			verifier: &fakeIDTokenVerifier{principal: identity.Principal{UID: "../other-user"}},
		},
		{name: "missing verifier"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			request := httptest.NewRequest(http.MethodGet, "/protected", nil)
			request.Header.Set("Authorization", "Bearer signed-token")
			recorder := httptest.NewRecorder()

			requireIDToken(test.verifier, http.HandlerFunc(unreachableHandler(t))).
				ServeHTTP(recorder, request)

			assertUnauthorized(t, recorder)
		})
	}
}

func assertUnauthorized(t *testing.T, recorder *httptest.ResponseRecorder) {
	t.Helper()

	if recorder.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusUnauthorized)
	}
	if recorder.Header().Get("WWW-Authenticate") != "Bearer" {
		t.Fatalf("WWW-Authenticate = %q", recorder.Header().Get("WWW-Authenticate"))
	}
	if !strings.Contains(recorder.Body.String(), `"code":"unauthorized"`) {
		t.Fatalf("body = %s", recorder.Body.String())
	}
}

func unreachableHandler(t *testing.T) http.HandlerFunc {
	t.Helper()
	return func(http.ResponseWriter, *http.Request) {
		t.Fatal("next handler was called")
	}
}
