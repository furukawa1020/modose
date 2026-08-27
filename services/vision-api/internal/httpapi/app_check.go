package httpapi

import (
	"net/http"
	"strings"

	"github.com/furukawa1020/modose/services/vision-api/internal/apierror"
	"github.com/furukawa1020/modose/services/vision-api/internal/appidentity"
	"github.com/furukawa1020/modose/services/vision-api/internal/identity"
)

const appCheckHeader = "X-Firebase-AppCheck"

var forbiddenAppCheck = apierror.Error{
	Code:    "app_check_failed",
	Message: "App attestation is required.",
}

func requireAppCheck(
	verifier appidentity.TokenVerifier,
	next http.Handler,
) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		rawToken, ok := appCheckToken(request.Header.Values(appCheckHeader))
		if !ok || verifier == nil {
			writeAppCheckForbidden(writer)
			return
		}

		app, err := verifier.VerifyAppCheckToken(request.Context(), rawToken)
		if err != nil {
			writeAppCheckForbidden(writer)
			return
		}
		app, err = appidentity.NewVerifiedApp(app.AppID)
		if err != nil {
			writeAppCheckForbidden(writer)
			return
		}

		ctx := appidentity.WithVerifiedApp(request.Context(), app)
		next.ServeHTTP(writer, request.WithContext(ctx))
	})
}

func requireFirebaseRequest(
	idTokenVerifier identity.IDTokenVerifier,
	appCheckVerifier appidentity.TokenVerifier,
	next http.Handler,
) http.Handler {
	return requireIDToken(
		idTokenVerifier,
		requireAppCheck(appCheckVerifier, next),
	)
}

func appCheckToken(values []string) (string, bool) {
	if len(values) != 1 {
		return "", false
	}
	token := values[0]
	if token == "" || strings.ContainsAny(token, " \t\r\n") {
		return "", false
	}
	return token, true
}

func writeAppCheckForbidden(writer http.ResponseWriter) {
	apierror.Write(writer, http.StatusForbidden, forbiddenAppCheck)
}
