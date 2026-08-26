package firebaseidentity

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/identity"
)

func TestVerifierReturnsValidatedPrincipal(t *testing.T) {
	t.Parallel()

	type contextKey string
	const key contextKey = "request"
	ctx := context.WithValue(context.Background(), key, "expected")
	verifier := newVerifier(func(got context.Context, rawToken string) (string, error) {
		if got.Value(key) != "expected" {
			t.Fatal("context was not forwarded")
		}
		if rawToken != "signed-token" {
			t.Fatalf("raw token = %q, want signed-token", rawToken)
		}
		return "user-123", nil
	})

	principal, err := verifier.VerifyIDToken(ctx, "signed-token")
	if err != nil {
		t.Fatalf("VerifyIDToken() error = %v", err)
	}
	if principal.UID != "user-123" {
		t.Fatalf("UID = %q, want user-123", principal.UID)
	}
}

func TestVerifierRejectsEmptyTokenBeforeCallingFirebase(t *testing.T) {
	t.Parallel()

	called := false
	verifier := newVerifier(func(context.Context, string) (string, error) {
		called = true
		return "user-123", nil
	})

	_, err := verifier.VerifyIDToken(context.Background(), "  ")
	assertVerificationReason(t, err, "token_required")
	if called {
		t.Fatal("Firebase verifier was called")
	}
}

func TestVerifierSanitizesFirebaseFailureAndPreservesCause(t *testing.T) {
	t.Parallel()

	firebaseErr := errors.New("secret token fragment")
	verifier := newVerifier(func(context.Context, string) (string, error) {
		return "", firebaseErr
	})

	_, err := verifier.VerifyIDToken(context.Background(), "signed-token")
	assertVerificationReason(t, err, "token_rejected")
	if !errors.Is(err, firebaseErr) {
		t.Fatal("Firebase failure was not preserved as the internal cause")
	}
	if strings.Contains(err.Error(), firebaseErr.Error()) {
		t.Fatalf("public error leaked Firebase detail: %v", err)
	}
}

func TestVerifierRejectsUnsafeVerifiedUID(t *testing.T) {
	t.Parallel()

	verifier := newVerifier(func(context.Context, string) (string, error) {
		return "../other-user", nil
	})

	_, err := verifier.VerifyIDToken(context.Background(), "signed-token")
	assertVerificationReason(t, err, "invalid_uid")

	var validationErr *identity.ValidationError
	if !errors.As(err, &validationErr) {
		t.Fatalf("error does not preserve identity validation cause: %v", err)
	}
}

func TestVerifierRejectsUnavailableClient(t *testing.T) {
	t.Parallel()

	_, err := New(nil).VerifyIDToken(context.Background(), "signed-token")
	assertVerificationReason(t, err, "verifier_unavailable")
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
