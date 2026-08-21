package httpapi

import (
	"bytes"
	"context"
	"errors"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"strings"
	"testing"
	"time"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/baselineapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/structured"
)

type baselineAnalyzerFunc func(context.Context, []byte) (baselineapi.Output, error)

func (function baselineAnalyzerFunc) Analyze(ctx context.Context, jpeg []byte) (baselineapi.Output, error) {
	return function(ctx, jpeg)
}

func TestBaselineHandlerReturnsContractResponse(t *testing.T) {
	handler := baselineHandler(baselineAnalyzerFunc(func(_ context.Context, jpeg []byte) (baselineapi.Output, error) {
		if !bytes.Equal(jpeg, []byte{1, 2, 3}) {
			t.Fatalf("jpeg = %v", jpeg)
		}
		return validBaselineOutput(), nil
	}))

	recorder := serveBaseline(t, handler, []byte{1, 2, 3}, "image/jpeg", true)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %s", recorder.Code, recorder.Body.String())
	}
	for _, value := range []string{
		`"schemaVersion":"1.0"`,
		`"modelId":"model-a"`,
		`"promptVersion":"baseline-v1"`,
		`"id":"wallet"`,
		`"repaired":false`,
	} {
		if !strings.Contains(recorder.Body.String(), value) {
			t.Errorf("body = %s, want %s", recorder.Body.String(), value)
		}
	}
}

func TestBaselineHandlerRejectsHeadersAndMediaTypes(t *testing.T) {
	analyzer := baselineAnalyzerFunc(func(context.Context, []byte) (baselineapi.Output, error) {
		t.Fatal("analyzer must not be called")
		return baselineapi.Output{}, nil
	})
	request := httptest.NewRequest(http.MethodPost, "/v1/vision/baseline", strings.NewReader("{}"))
	request.Header.Set("Content-Type", "application/json")
	setBaselineHeaders(request)
	recorder := httptest.NewRecorder()
	baselineHandler(analyzer).ServeHTTP(recorder, request)
	if recorder.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("non-multipart status = %d", recorder.Code)
	}

	recorder = serveBaseline(t, baselineHandler(analyzer), []byte{1}, "image/jpeg", false)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("missing headers status = %d", recorder.Code)
	}

	recorder = serveBaseline(t, baselineHandler(analyzer), []byte{1}, "image/png", true)
	if recorder.Code != http.StatusUnsupportedMediaType {
		t.Fatalf("PNG status = %d", recorder.Code)
	}
}

func TestBaselineHandlerRejectsOversizedImage(t *testing.T) {
	called := false
	handler := baselineHandler(baselineAnalyzerFunc(func(context.Context, []byte) (baselineapi.Output, error) {
		called = true
		return baselineapi.Output{}, nil
	}))
	recorder := serveBaseline(t, handler, bytes.Repeat([]byte{1}, int(MaxImageBytes)+1), "image/jpeg", true)
	if recorder.Code != http.StatusRequestEntityTooLarge || called {
		t.Fatalf("status = %d, called = %v", recorder.Code, called)
	}
}

func TestBaselineHandlerMapsAnalysisFailuresWithoutLeakingDetails(t *testing.T) {
	private := errors.New("private object and prompt detail")
	handler := baselineHandler(baselineAnalyzerFunc(func(context.Context, []byte) (baselineapi.Output, error) {
		return baselineapi.Output{}, &baselineapi.Error{Stage: baselineapi.StageGeneration, Err: private}
	}))
	recorder := serveBaseline(t, handler, []byte{1}, "image/jpeg", true)
	if recorder.Code != http.StatusBadGateway {
		t.Fatalf("status = %d", recorder.Code)
	}
	if strings.Contains(recorder.Body.String(), private.Error()) {
		t.Fatalf("response leaked internal detail: %s", recorder.Body.String())
	}
}

func serveBaseline(
	t *testing.T,
	handler http.Handler,
	image []byte,
	imageType string,
	headers bool,
) *httptest.ResponseRecorder {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	_ = writer.WriteField("metadata", `{"sceneId":"018f0f90-1234-7abc-8def-123456789abc","capturedAt":"`+time.Now().UTC().Format(time.RFC3339)+`"}`)
	partHeader := make(textproto.MIMEHeader)
	partHeader.Set("Content-Disposition", `form-data; name="image"; filename="scene.jpg"`)
	partHeader.Set("Content-Type", imageType)
	part, err := writer.CreatePart(partHeader)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(image); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}

	request := httptest.NewRequest(http.MethodPost, "/v1/vision/baseline", &body)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	if headers {
		setBaselineHeaders(request)
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, request)
	return recorder
}

func setBaselineHeaders(request *http.Request) {
	request.Header.Set("X-Schema-Version", "1.0")
	request.Header.Set("X-Client-Version", "test")
	request.Header.Set("Idempotency-Key", "018f0f90-1234-7abc-8def-123456789abc")
}

func validBaselineOutput() baselineapi.Output {
	return baselineapi.Output{
		Result: baseline.Result{Objects: []baseline.Object{{
			ID: "wallet", DisplayName: "黒い財布",
			AppearanceFeatures: []string{"黒色"},
			BoundingBox: baseline.BoundingBox{YMin: 100, XMin: 200, YMax: 500, XMax: 700},
			Symmetry: baseline.SymmetryNone,
		}}},
		PromptVersion: baseline.PromptVersion,
		Attempts: []structured.AttemptMetadata{{ResponseID: "response-1", ModelID: "model-a"}},
	}
}
