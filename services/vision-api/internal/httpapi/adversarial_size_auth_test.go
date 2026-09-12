package httpapi

import (
	"bytes"
	"context"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/appidentity"
	"github.com/furukawa1020/modose/services/vision-api/internal/baselineapi"
)

func TestProtectedBaselineRejectsAuthBeforeParsingOversizedBody(
	t *testing.T,
) {
	tests := []struct {
		name          string
		withIdentity  bool
		withAppCheck  bool
		wantStatus    int
		wantCode      string
		wantIDCalls   int
		wantAppCalls  int
	}{
		{
			name:       "ID tokenなし",
			wantStatus: http.StatusUnauthorized,
			wantCode:   "unauthorized",
		},
		{
			name:         "App Checkなし",
			withIdentity: true,
			wantStatus:   http.StatusForbidden,
			wantCode:     "app_check_failed",
			wantIDCalls:  1,
		},
		{
			name:         "認証後にrequest上限超過",
			withIdentity: true,
			withAppCheck: true,
			wantStatus:   http.StatusRequestEntityTooLarge,
			wantCode:     "payload_too_large",
			wantIDCalls:  1,
			wantAppCalls: 1,
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			idVerifier := &chainIDVerifier{}
			appVerifier := &fakeAppCheckVerifier{
				app: appidentity.VerifiedApp{
					AppID: "1:1234567890:android:abcdef",
				},
			}
			analyzerCalled := false
			protected := requireFirebaseRequest(
				idVerifier,
				appVerifier,
				baselineHandler(
					baselineAnalyzerFunc(
						func(
							context.Context,
							[]byte,
						) (baselineapi.Output, error) {
							analyzerCalled = true
							return baselineapi.Output{}, nil
						},
					),
				),
			)
			request := oversizedBaselineRequest(t)
			if test.withIdentity {
				request.Header.Set(
					"Authorization",
					"Bearer valid-id-token",
				)
			}
			if test.withAppCheck {
				request.Header.Set(
					appCheckHeader,
					"valid-app-check-token",
				)
			}
			recorder := httptest.NewRecorder()

			protected.ServeHTTP(recorder, request)

			assertPublicError(
				t,
				recorder,
				test.wantStatus,
				test.wantCode,
			)
			if idVerifier.calls != test.wantIDCalls {
				t.Fatalf(
					"ID verifier calls = %d, want %d",
					idVerifier.calls,
					test.wantIDCalls,
				)
			}
			if appVerifier.calls != test.wantAppCalls {
				t.Fatalf(
					"App Check verifier calls = %d, want %d",
					appVerifier.calls,
					test.wantAppCalls,
				)
			}
			if analyzerCalled {
				t.Fatal("analyzer must not be called")
			}
		})
	}
}

func oversizedBaselineRequest(t *testing.T) *http.Request {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	writeField(
		t,
		writer,
		"padding",
		strings.Repeat("x", int(MaxVisionRequestBytes)),
	)
	writeField(
		t,
		writer,
		"metadata",
		"{\"sceneId\":\"scene-1\",\"capturedAt\":\"2026-08-26T00:00:00Z\"}",
	)
	writeFile(
		t,
		writer,
		"image",
		"scene.jpg",
		"image/jpeg",
		[]byte{1},
	)
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	request := httptest.NewRequest(
		http.MethodPost,
		"/v1/vision/baseline",
		&body,
	)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	setBaselineHeaders(request)
	return request
}
