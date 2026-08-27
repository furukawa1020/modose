package appidentity

import (
	"context"
	"errors"
	"strings"
	"testing"
)

type verifierStub struct{}

func (verifierStub) VerifyAppCheckToken(
	context.Context,
	string,
) (VerifiedApp, error) {
	return NewVerifiedApp("1:1234567890:android:abcdef")
}

var _ TokenVerifier = verifierStub{}

func TestNewVerifiedAppAcceptsFirebaseAppID(t *testing.T) {
	t.Parallel()

	app, err := NewVerifiedApp(" 1:1234567890:android:abc_DEF-123 ")
	if err != nil {
		t.Fatalf("NewVerifiedApp() error = %v", err)
	}
	if app.AppID != "1:1234567890:android:abc_DEF-123" {
		t.Fatalf("AppID = %q", app.AppID)
	}
}

func TestNewVerifiedAppRejectsInvalidAppID(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name   string
		appID  string
		reason string
	}{
		{name: "empty", appID: "  ", reason: "required"},
		{name: "space", appID: "1:123:android:abc def", reason: "unsafe"},
		{name: "path separator", appID: "1:123/android:abcdef", reason: "unsafe"},
		{name: "control", appID: "1:123:android:abc\ndef", reason: "unsafe"},
		{name: "too long", appID: strings.Repeat("a", maxAppIDBytes+1), reason: "too_long"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			_, err := NewVerifiedApp(test.appID)
			var validationErr *ValidationError
			if !errors.As(err, &validationErr) {
				t.Fatalf("error = %v, want ValidationError", err)
			}
			if validationErr.Field != "app_id" || validationErr.Reason != test.reason {
				t.Fatalf("validation error = %#v", validationErr)
			}
		})
	}
}

func TestVerifiedAppContextRoundTrip(t *testing.T) {
	t.Parallel()

	app, err := NewVerifiedApp("1:1234567890:android:abcdef")
	if err != nil {
		t.Fatal(err)
	}
	ctx := WithVerifiedApp(context.Background(), app)

	got, ok := FromContext(ctx)
	if !ok || got != app {
		t.Fatalf("app = %#v, ok = %v", got, ok)
	}
}

func TestVerifiedAppContextFailsClosed(t *testing.T) {
	t.Parallel()

	if app, ok := FromContext(context.Background()); ok || app != (VerifiedApp{}) {
		t.Fatalf("missing context app = %#v, ok = %v", app, ok)
	}

	ctx := WithVerifiedApp(
		context.Background(),
		VerifiedApp{AppID: "../unverified-app"},
	)
	if app, ok := FromContext(ctx); ok || app != (VerifiedApp{}) {
		t.Fatalf("unsafe context app = %#v, ok = %v", app, ok)
	}
}
