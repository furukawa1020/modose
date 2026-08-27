package firebaseappcheck

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/appidentity"
)

func TestVerifierReturnsValidatedApp(t *testing.T) {
	t.Parallel()

	verifier := newVerifier(func(rawToken string) (string, error) {
		if rawToken != "signed-app-check-token" {
			t.Fatalf("raw token = %q", rawToken)
		}
		return "1:1234567890:android:abcdef", nil
	})

	app, err := verifier.VerifyAppCheckToken(
		context.Background(),
		"signed-app-check-token",
	)
	if err != nil {
		t.Fatalf("VerifyAppCheckToken() error = %v", err)
	}
	if app.AppID != "1:1234567890:android:abcdef" {
		t.Fatalf("AppID = %q", app.AppID)
	}
}

func TestVerifierRejectsEmptyTokenBeforeCallingFirebase(t *testing.T) {
	t.Parallel()

	called := false
	verifier := newVerifier(func(string) (string, error) {
		called = true
		return "1:1234567890:android:abcdef", nil
	})

	_, err := verifier.VerifyAppCheckToken(context.Background(), "  ")
	assertVerificationReason(t, err, "token_required")
	if called {
		t.Fatal("Firebase App Check verifier was called")
	}
}

func TestVerifierSanitizesFirebaseFailureAndPreservesCause(t *testing.T) {
	t.Parallel()

	firebaseErr := errors.New("private decoded token detail")
	verifier := newVerifier(func(string) (string, error) {
		return "", firebaseErr
	})

	_, err := verifier.VerifyAppCheckToken(context.Background(), "signed-token")
	assertVerificationReason(t, err, "token_rejected")
	if !errors.Is(err, firebaseErr) {
		t.Fatal("Firebase failure was not preserved as the internal cause")
	}
	if strings.Contains(err.Error(), firebaseErr.Error()) {
		t.Fatalf("public error leaked Firebase detail: %v", err)
	}
}

func TestVerifierRejectsUnsafeVerifiedAppID(t *testing.T) {
	parallelVerifierTest(t, "../unverified-app", "invalid_app_id")
}

func TestVerifierRejectsEmptyVerifiedAppID(t *testing.T) {
	parallelVerifierTest(t, "", "invalid_app_id")
}

func TestVerifierRejectsUnavailableClient(t *testing.T) {
	t.Parallel()

	_, err := New(nil).VerifyAppCheckToken(context.Background(), "signed-token")
	assertVerificationReason(t, err, "verifier_unavailable")
}

func TestVerifierHonorsCanceledContextBeforeSDKCall(t *testing.T) {
	t.Parallel()

	called := false
	verifier := newVerifier(func(string) (string, error) {
		called = true
		return "1:1234567890:android:abcdef", nil
	})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	_, err := verifier.VerifyAppCheckToken(ctx, "signed-token")
	assertVerificationReason(t, err, "request_canceled")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("error = %v, want context.Canceled cause", err)
	}
	if called {
		t.Fatal("Firebase App Check verifier was called")
	}
}

func parallelVerifierTest(t *testing.T, appID, reason string) {
	t.Helper()
	t.Parallel()

	verifier := newVerifier(func(string) (string, error) {
		return appID, nil
	})
	_, err := verifier.VerifyAppCheckToken(context.Background(), "signed-token")
	assertVerificationReason(t, err, reason)

	var validationErr *appidentity.ValidationError
	if !errors.As(err, &validationErr) {
		t.Fatalf("error does not preserve app validation cause: %v", err)
	}
}

func assertVerificationReason(t *testing.T, err error, want string) {
	t.Helper()

	var verificationErr *VerificationError
	if !errors.As(err, &verificationErr) {
		t.Fatalf("error = %v, want VerificationError", err)
	}
	if verificationErr.Reason != want {
		t.Fatalf("reason = %q, want %q", verificationErr.Reason, want)
	}
}
