package httpapi

import (
	"context"
	"net/http"
	"strings"

	"github.com/furukawa1020/modose/services/vision-api/internal/apierror"
	"github.com/furukawa1020/modose/services/vision-api/internal/identity"
)

var unauthorizedRequest = apierror.Error{
	Code:    "unauthorized",
	Message: "Authentication is required.",
}

func requireIDToken(verifier identity.IDTokenVerifier, next http.Handler) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		rawToken, ok := bearerToken(request.Header.Values("Authorization"))
		if !ok || verifier == nil {
			writeUnauthorized(writer)
			return
		}

		principal, err := verifier.VerifyIDToken(request.Context(), rawToken)
		if err != nil {
			writeUnauthorized(writer)
			return
		}
		principal, err = identity.NewPrincipal(principal.UID)
		if err != nil {
			writeUnauthorized(writer)
			return
		}

		ctx := identity.WithPrincipal(request.Context(), principal)
		ctx = WithVerifiedUID(ctx, principal.UID)
		next.ServeHTTP(writer, request.WithContext(ctx))
	})
}

func bearerToken(values []string) (string, bool) {
	if len(values) != 1 {
		return "", false
	}
	const prefix = "Bearer "
	value := values[0]
	if !strings.HasPrefix(value, prefix) {
		return "", false
	}
	token := strings.TrimPrefix(value, prefix)
	if token == "" || strings.ContainsAny(token, " \t\r\n") {
		return "", false
	}
	return token, true
}

func writeUnauthorized(writer http.ResponseWriter) {
	writer.Header().Set("WWW-Authenticate", "Bearer")
	apierror.Write(writer, http.StatusUnauthorized, unauthorizedRequest)
}

// Compile-time documentation of the context dependency used by handlers.
var _ context.Context = context.Background()
