package firebaseappcheck

import (
	"context"
	"fmt"
	"strings"

	firebasecheck "firebase.google.com/go/v4/appcheck"

	"github.com/furukawa1020/modose/services/vision-api/internal/appidentity"
)

type VerificationError struct {
	Reason string
	cause  error
}

func (e *VerificationError) Error() string {
	return fmt.Sprintf("firebase app check verification failed: %s", e.Reason)
}

func (e *VerificationError) Unwrap() error {
	return e.cause
}

type tokenVerifier func(string) (string, error)

type Verifier struct {
	verify tokenVerifier
}

var _ appidentity.TokenVerifier = (*Verifier)(nil)

func New(client *firebasecheck.Client) *Verifier {
	if client == nil {
		return &Verifier{}
	}
	return newVerifier(func(rawToken string) (string, error) {
		decoded, err := client.VerifyToken(rawToken)
		if err != nil {
			return "", err
		}
		if decoded == nil {
			return "", nil
		}
		return decoded.AppID, nil
	})
}

func (v *Verifier) VerifyAppCheckToken(
	ctx context.Context,
	rawToken string,
) (appidentity.VerifiedApp, error) {
	if strings.TrimSpace(rawToken) == "" {
		return appidentity.VerifiedApp{}, verificationError("token_required", nil)
	}
	if v == nil || v.verify == nil {
		return appidentity.VerifiedApp{}, verificationError("verifier_unavailable", nil)
	}
	select {
	case <-ctx.Done():
		return appidentity.VerifiedApp{}, verificationError("request_canceled", ctx.Err())
	default:
	}

	appID, err := v.verify(rawToken)
	if err != nil {
		return appidentity.VerifiedApp{}, verificationError("token_rejected", err)
	}
	app, err := appidentity.NewVerifiedApp(appID)
	if err != nil {
		return appidentity.VerifiedApp{}, verificationError("invalid_app_id", err)
	}
	return app, nil
}

func newVerifier(verify tokenVerifier) *Verifier {
	return &Verifier{verify: verify}
}

func verificationError(reason string, cause error) error {
	return &VerificationError{Reason: reason, cause: cause}
}
