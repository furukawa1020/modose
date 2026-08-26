package identity

import (
	"context"
	"errors"
	"testing"
)

func TestNewPrincipalAcceptsSafeUID(t *testing.T) {
	principal, err := NewPrincipal(" anonymous-user-1 ")
	if err != nil {
		t.Fatalf("NewPrincipal() error = %v", err)
	}
	if principal.UID != "anonymous-user-1" {
		t.Fatalf("UID = %q", principal.UID)
	}
}

func TestNewPrincipalRejectsUnsafeUID(t *testing.T) {
	for _, uid := range []string{"", " ", ".", "..", "user/child", `user\child`} {
		t.Run(uid, func(t *testing.T) {
			var validationError *ValidationError
			_, err := NewPrincipal(uid)
			if !errors.As(err, &validationError) {
				t.Fatalf("error = %#v", err)
			}
		})
	}
}

func TestContextRoundTripRequiresValidPrincipal(t *testing.T) {
	principal, err := NewPrincipal("user-1")
	if err != nil {
		t.Fatal(err)
	}
	ctx := WithPrincipal(context.Background(), principal)

	got, ok := FromContext(ctx)

	if !ok || got != principal {
		t.Fatalf("principal = %#v, ok = %v", got, ok)
	}
}

func TestContextFailsClosedForMissingOrInvalidPrincipal(t *testing.T) {
	tests := []context.Context{
		context.Background(),
		WithPrincipal(context.Background(), Principal{}),
		WithPrincipal(context.Background(), Principal{UID: "user/child"}),
	}
	for index, ctx := range tests {
		if principal, ok := FromContext(ctx); ok {
			t.Fatalf("case %d returned %#v", index, principal)
		}
	}
}

type fakeVerifier struct{}

func (fakeVerifier) VerifyIDToken(
	context.Context,
	string,
) (Principal, error) {
	return Principal{UID: "user-1"}, nil
}

func TestVerifierPortAcceptsAdapter(t *testing.T) {
	var verifier IDTokenVerifier = fakeVerifier{}
	principal, err := verifier.VerifyIDToken(context.Background(), "token")
	if err != nil || principal.UID != "user-1" {
		t.Fatalf("principal = %#v, error = %v", principal, err)
	}
}
