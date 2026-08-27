package appidentity

import (
	"context"
	"fmt"
	"strings"
)

const maxAppIDBytes = 256

type VerifiedApp struct {
	AppID string
}

type ValidationError struct {
	Field  string
	Reason string
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("invalid app check identity: %s is %s", e.Field, e.Reason)
}

type TokenVerifier interface {
	VerifyAppCheckToken(context.Context, string) (VerifiedApp, error)
}

type verifiedAppContextKey struct{}

func NewVerifiedApp(appID string) (VerifiedApp, error) {
	appID = strings.TrimSpace(appID)
	if appID == "" {
		return VerifiedApp{}, invalid("app_id", "required")
	}
	if len(appID) > maxAppIDBytes {
		return VerifiedApp{}, invalid("app_id", "too_long")
	}
	for _, character := range appID {
		if !allowedAppIDCharacter(character) {
			return VerifiedApp{}, invalid("app_id", "unsafe")
		}
	}
	return VerifiedApp{AppID: appID}, nil
}

func WithVerifiedApp(ctx context.Context, app VerifiedApp) context.Context {
	return context.WithValue(ctx, verifiedAppContextKey{}, app)
}

func FromContext(ctx context.Context) (VerifiedApp, bool) {
	app, ok := ctx.Value(verifiedAppContextKey{}).(VerifiedApp)
	if !ok {
		return VerifiedApp{}, false
	}
	validated, err := NewVerifiedApp(app.AppID)
	if err != nil {
		return VerifiedApp{}, false
	}
	return validated, true
}

func allowedAppIDCharacter(character rune) bool {
	return character >= 'a' && character <= 'z' ||
		character >= 'A' && character <= 'Z' ||
		character >= '0' && character <= '9' ||
		character == ':' ||
		character == '_' ||
		character == '-'
}

func invalid(field, reason string) error {
	return &ValidationError{Field: field, Reason: reason}
}
