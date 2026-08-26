package identity

import (
	"context"
	"fmt"
	"strings"
)

type Principal struct {
	UID string
}

type ValidationError struct {
	Field  string
	Reason string
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("invalid identity principal: %s is %s", e.Field, e.Reason)
}

type IDTokenVerifier interface {
	VerifyIDToken(context.Context, string) (Principal, error)
}

type principalContextKey struct{}

func NewPrincipal(uid string) (Principal, error) {
	uid = strings.TrimSpace(uid)
	if uid == "" {
		return Principal{}, invalid("uid", "required")
	}
	if strings.ContainsAny(uid, "/\\") || uid == "." || uid == ".." {
		return Principal{}, invalid("uid", "unsafe")
	}
	return Principal{UID: uid}, nil
}

func WithPrincipal(ctx context.Context, principal Principal) context.Context {
	return context.WithValue(ctx, principalContextKey{}, principal)
}

func FromContext(ctx context.Context) (Principal, bool) {
	principal, ok := ctx.Value(principalContextKey{}).(Principal)
	if !ok {
		return Principal{}, false
	}
	valid, err := NewPrincipal(principal.UID)
	if err != nil {
		return Principal{}, false
	}
	return valid, true
}

func invalid(field, reason string) error {
	return &ValidationError{Field: field, Reason: reason}
}
