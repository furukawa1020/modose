package firebaseidentity

import (
	"context"
	"fmt"
	"strings"

	firebaseauth "firebase.google.com/go/v4/auth"

	"github.com/furukawa1020/modose/services/vision-api/internal/identity"
)

type VerificationError struct {
	Reason string
	cause  error
}

func (e *VerificationError) Error() string {
	return fmt.Sprintf("firebase id token verification failed: %s", e.Reason)
}

func (e *VerificationError) Unwrap() error {
	return e.cause
}

type tokenVerifier func(context.Context, string) (string, error)

type Verifier struct {
	verify tokenVerifier
}

var _ identity.IDTokenVerifier = (*Verifier)(nil)

func New(client *firebaseauth.Client) *Verifier {
	if client == nil {
		return &Verifier{}
	}
	return newVerifier(func(ctx context.Context, rawToken string) (string, error) {
		token, err := client.VerifyIDToken(ctx, rawToken)
		if err != nil {
			return "", err
		}
		if token == nil {
			return "", nil
		}
		return token.UID, nil
	})
}

func (v *Verifier) VerifyIDToken(ctx context.Context, rawToken string) (identity.Principal, error) {
	if strings.TrimSpace(rawToken) == "" {
		return identity.Principal{}, verificationError("token_required", nil)
	}
	if v == nil || v.verify == nil {
		return identity.Principal{}, verificationError("verifier_unavailable", nil)
	}

	uid, err := v.verify(ctx, rawToken)
	if err != nil {
		return identity.Principal{}, verificationError("token_rejected", err)
	}

	principal, err := identity.NewPrincipal(uid)
	if err != nil {
		return identity.Principal{}, verificationError("invalid_uid", err)
	}
	return principal, nil
}

func newVerifier(verify tokenVerifier) *Verifier {
	return &Verifier{verify: verify}
}

func verificationError(reason string, cause error) error {
	return &VerificationError{Reason: reason, cause: cause}
}
