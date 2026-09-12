package httpapi

import (
	"bytes"
	"context"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/baselineapi"
)

func TestBaselineHandlerRejectsMalformedMetadataBeforeAnalysis(
	t *testing.T,
) {
	called := false
	analyzer := baselineAnalyzerFunc(
		func(context.Context, []byte) (baselineapi.Output, error) {
			called = true
			return baselineapi.Output{}, nil
		},
	)
	request := baselineRequestWithMetadata(t, "{\"sceneId\":")
	recorder := httptest.NewRecorder()

	baselineHandler(analyzer).ServeHTTP(recorder, request)

	assertPublicError(
		t,
		recorder,
		http.StatusBadRequest,
		"invalid_request",
	)
	if called {
		t.Fatal("analyzer must not be called")
	}
}

func TestCompareHandlerRejectsUnknownDomainEnumBeforeAnalysis(
	t *testing.T,
) {
	analyzer := &fakeCompareAnalyzer{}
	unknownEnum := strings.Replace(
		validConfirmedObjects,
		"\"symmetry\":\"none\"",
		"\"symmetry\":\"unknown_symmetry\"",
		1,
	)
	if unknownEnum == validConfirmedObjects {
		t.Fatal("test fixture did not replace symmetry")
	}
	request := compareRequest(
		t,
		unknownEnum,
		"image/jpeg",
		[]byte("saved"),
		[]byte("current"),
	)
	recorder := httptest.NewRecorder()

	compareHandler(analyzer).ServeHTTP(recorder, request)

	assertPublicError(
		t,
		recorder,
		http.StatusUnprocessableEntity,
		"analysis_rejected",
	)
	if analyzer.called {
		t.Fatal("analyzer must not be called")
	}
}

func baselineRequestWithMetadata(
	t *testing.T,
	metadata string,
) *http.Request {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	writeField(t, writer, "metadata", metadata)
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

func assertPublicError(
	t *testing.T,
	recorder *httptest.ResponseRecorder,
	status int,
	code string,
) {
	t.Helper()
	if recorder.Code != status {
		t.Fatalf(
			"status = %d, want %d, body = %s",
			recorder.Code,
			status,
			recorder.Body.String(),
		)
	}
	want := "\"code\":\"" + code + "\""
	if !strings.Contains(recorder.Body.String(), want) {
		t.Fatalf("body = %s, want %s", recorder.Body.String(), want)
	}
}
