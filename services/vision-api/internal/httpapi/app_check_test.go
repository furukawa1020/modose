package httpapi

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/appidentity"
	"github.com/furukawa1020/modose/services/vision-api/internal/identity"
)

type fakeAppCheckVerifier struct {
	app   appidentity.VerifiedApp
	err   error
	calls int
	token string
}

func (verifier *fakeAppCheckVerifier) VerifyAppCheckToken(
	_ context.Context,
	rawToken string,
) (appidentity.VerifiedApp, error) {
	verifier.calls++
	verifier.token = rawToken
	return verifier.app, verifier.err
}

func TestRequireAppCheckForwardsVerifiedApp(t *testing.T) {
	t.Parallel()

	verifier := &fakeAppCheckVerifier{
		app: appidentity.VerifiedApp{AppID: "1:1234567890:android:abcdef"},
	}
	next := http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		app, ok := appidentity.FromContext(request.Context())
		if !ok || app.AppID != "1:1234567890:android:abcdef" {
			t.Fatalf("app = %#v, ok = %v", app, ok)
		}
		writer.WriteHeader(http.StatusNoContent)
	})
	request := httptest.NewRequest(http.MethodPost, "/protected", nil)
	request.Header.Set(appCheckHeader, "signed-app-check-token")
	recorder := httptest.NewRecorder()

	requireAppCheck(verifier, next).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusNoContent {
		t.Fatalf("status = %d", recorder.Code)
	}
	if verifier.calls != 1 || verifier.token != "signed-app-check-token" {
		t.Fatalf("calls = %d, token = %q", verifier.calls, verifier.token)
	}
}

func TestRequireAppCheckRejectsMalformedHeaderWithoutVerification(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name   string
		values []string
	}{
		{name: "missing"},
		{name: "empty", values: []string{""}},
		{name: "space", values: []string{"first second"}},
		{name: "tab", values: []string{"first\tsecond"}},
		{name: "duplicate", values: []string{"first", "second"}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			verifier := &fakeAppCheckVerifier{}
			request := httptest.NewRequest(http.MethodPost, "/protected", nil)
			for _, value := range test.values {
				request.Header.Add(appCheckHeader, value)
			}
			recorder := httptest.NewRecorder()

			requireAppCheck(verifier, http.HandlerFunc(unreachableAppHandler(t))).
				ServeHTTP(recorder, request)

			assertAppCheckForbidden(t, recorder)
			if verifier.calls != 0 {
				t.Fatalf("verifier calls = %d", verifier.calls)
			}
		})
	}
}

func TestRequireAppCheckDoesNotLeakVerificationFailure(t *testing.T) {
	t.Parallel()

	privateErr := errors.New("private App Check detail")
	verifier := &fakeAppCheckVerifier{err: privateErr}
	request := httptest.NewRequest(http.MethodPost, "/protected", nil)
	request.Header.Set(appCheckHeader, "secret-app-token")
	recorder := httptest.NewRecorder()

	requireAppCheck(verifier, http.HandlerFunc(unreachableAppHandler(t))).
		ServeHTTP(recorder, request)

	assertAppCheckForbidden(t, recorder)
	if strings.Contains(recorder.Body.String(), privateErr.Error()) ||
		strings.Contains(recorder.Body.String(), "secret-app-token") {
		t.Fatalf("response leaked App Check detail: %s", recorder.Body.String())
	}
}

func TestRequireAppCheckRejectsInvalidAppAndMissingVerifier(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name     string
		verifier appidentity.TokenVerifier
	}{
		{
			name: "unsafe app id",
			verifier: &fakeAppCheckVerifier{
				app: appidentity.VerifiedApp{AppID: "../unverified-app"},
			},
		},
		{name: "missing verifier"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			request := httptest.NewRequest(http.MethodPost, "/protected", nil)
			request.Header.Set(appCheckHeader, "signed-token")
			recorder := httptest.NewRecorder()

			requireAppCheck(test.verifier, http.HandlerFunc(unreachableAppHandler(t))).
				ServeHTTP(recorder, request)

			assertAppCheckForbidden(t, recorder)
		})
	}
}

func TestFirebaseRequestChecksIdentityBeforeAppAttestation(t *testing.T) {
	t.Parallel()

	idVerifier := &chainIDVerifier{}
	appVerifier := &fakeAppCheckVerifier{
		app: appidentity.VerifiedApp{AppID: "1:1234567890:android:abcdef"},
	}
	handler := requireFirebaseRequest(
		idVerifier,
		appVerifier,
		http.HandlerFunc(func(writer http.ResponseWriter, _ *http.Request) {
			writer.WriteHeader(http.StatusNoContent)
		}),
	)

	t.Run("identity missing", func(t *testing.T) {
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(
			recorder,
			httptest.NewRequest(http.MethodPost, "/protected", nil),
		)
		if recorder.Code != http.StatusUnauthorized || appVerifier.calls != 0 {
			t.Fatalf("status = %d, app calls = %d", recorder.Code, appVerifier.calls)
		}
	})

	t.Run("app check missing", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodPost, "/protected", nil)
		request.Header.Set("Authorization", "Bearer id-token")
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusForbidden || idVerifier.calls != 1 {
			t.Fatalf("status = %d, id calls = %d", recorder.Code, idVerifier.calls)
		}
	})

	t.Run("both verified", func(t *testing.T) {
		request := httptest.NewRequest(http.MethodPost, "/protected", nil)
		request.Header.Set("Authorization", "Bearer id-token")
		request.Header.Set(appCheckHeader, "app-token")
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, request)
		if recorder.Code != http.StatusNoContent || appVerifier.calls != 1 {
			t.Fatalf("status = %d, app calls = %d", recorder.Code, appVerifier.calls)
		}
	})
}

type chainIDVerifier struct {
	calls int
}

func (verifier *chainIDVerifier) VerifyIDToken(
	context.Context,
	string,
) (identity.Principal, error) {
	verifier.calls++
	return identity.Principal{UID: "verified-user"}, nil
}

func assertAppCheckForbidden(t *testing.T, recorder *httptest.ResponseRecorder) {
	t.Helper()

	if recorder.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want %d", recorder.Code, http.StatusForbidden)
	}
	if !strings.Contains(recorder.Body.String(), `"code":"app_check_failed"`) {
		t.Fatalf("body = %s", recorder.Body.String())
	}
}

func unreachableAppHandler(t *testing.T) http.HandlerFunc {
	t.Helper()
	return func(http.ResponseWriter, *http.Request) {
		t.Fatal("next handler was called")
	}
}
