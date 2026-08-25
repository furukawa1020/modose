package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"mime/multipart"
	"net/http"
	"net/http/httptest"
	"net/textproto"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/compare"
	"github.com/furukawa1020/modose/services/vision-api/internal/compareapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/structured"
)

const validConfirmedObjects = `{
  "objects":[{
    "id":"object-1",
    "displayName":"鍵",
    "appearanceFeatures":["銀色"],
    "boundingBox":{"yMin":100,"xMin":100,"yMax":300,"xMax":300},
    "orientationImportant":false,
    "symmetry":"none"
  }],
  "excludedCandidates":[]
}`

type fakeCompareAnalyzer struct {
	output compareapi.Output
	err    error
	called bool
}

func (fake *fakeCompareAnalyzer) Compare(
	_ context.Context,
	savedJPEG, currentJPEG []byte,
	saved baseline.Result,
) (compareapi.Output, error) {
	fake.called = true
	if string(savedJPEG) != "saved" || string(currentJPEG) != "current" {
		return compareapi.Output{}, errors.New("unexpected image")
	}
	if len(saved.Objects) != 1 || saved.Objects[0].ID != "object-1" {
		return compareapi.Output{}, errors.New("unexpected baseline")
	}
	return fake.output, fake.err
}

func TestCompareHandlerReturnsContractEnvelope(t *testing.T) {
	analyzer := &fakeCompareAnalyzer{output: compareapi.Output{
		Result: compare.Result{Matches: []compare.Match{{
			BaselineObjectID: "object-1",
			State:            compare.State("ambiguous"),
			Confidence:       0.71,
			AmbiguityReason:  "候補が競合",
		}}, AddedObjects: []compare.AddedObject{}},
		PromptVersion: "compare-v1",
		Attempts:      []structured.AttemptMetadata{{ModelID: "gemini-test"}},
		Repaired:      true,
	}}
	request := compareRequest(t, validConfirmedObjects, "image/jpeg", []byte("saved"), []byte("current"))
	recorder := httptest.NewRecorder()

	compareHandler(analyzer).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK || !analyzer.called {
		t.Fatalf("status = %d, called = %v", recorder.Code, analyzer.called)
	}
	var response compareResponse
	if err := json.NewDecoder(recorder.Body).Decode(&response); err != nil {
		t.Fatal(err)
	}
	if response.SchemaVersion != "1.0" || response.PromptVersion != "compare-v1" ||
		response.ModelID != "gemini-test" || !response.Repaired {
		t.Fatalf("unexpected response: %#v", response)
	}
	if len(response.Matches) != 1 || response.Matches[0].State != compare.State("ambiguous") {
		t.Fatalf("unexpected matches: %#v", response.Matches)
	}
}

func TestCompareHandlerRejectsInvalidBoundaryInputs(t *testing.T) {
	tests := []struct {
		name      string
		confirmed string
		imageType string
		saved     []byte
		want      int
	}{
		{"confirmedObjectsなし", "", "image/jpeg", []byte("saved"), http.StatusUnprocessableEntity},
		{"未知フィールド", validConfirmedObjects[:len(validConfirmedObjects)-2] + `,"unknown":true}`, "image/jpeg", []byte("saved"), http.StatusUnprocessableEntity},
		{"JPEG以外", validConfirmedObjects, "image/png", []byte("saved"), http.StatusUnsupportedMediaType},
		{"画像上限超過", validConfirmedObjects, "image/jpeg", bytes.Repeat([]byte{1}, int(MaxImageBytes+1)), http.StatusRequestEntityTooLarge},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			analyzer := &fakeCompareAnalyzer{}
			request := compareRequest(t, test.confirmed, test.imageType, test.saved, []byte("current"))
			recorder := httptest.NewRecorder()

			compareHandler(analyzer).ServeHTTP(recorder, request)

			if recorder.Code != test.want {
				t.Fatalf("status = %d, want %d", recorder.Code, test.want)
			}
			if analyzer.called {
				t.Fatal("analyzer must not be called")
			}
		})
	}
}

func TestCompareHandlerDoesNotLeakServiceFailure(t *testing.T) {
	analyzer := &fakeCompareAnalyzer{err: &compareapi.Error{
		Stage: compareapi.StageGeneration,
		Err:   errors.New("secret upstream detail"),
	}}
	request := compareRequest(t, validConfirmedObjects, "image/jpeg", []byte("saved"), []byte("current"))
	recorder := httptest.NewRecorder()

	compareHandler(analyzer).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusBadGateway {
		t.Fatalf("status = %d", recorder.Code)
	}
	if strings.Contains(recorder.Body.String(), "secret upstream detail") {
		t.Fatal("service detail leaked")
	}
}

func compareRequest(
	t *testing.T,
	confirmed, imageType string,
	saved, current []byte,
) *http.Request {
	t.Helper()
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	writeField(t, writer, "metadata", `{"sceneId":"scene-1","capturedAt":"2026-08-26T00:00:00Z"}`)
	if confirmed != "" {
		writeField(t, writer, "confirmedObjects", confirmed)
	}
	writeFile(t, writer, "baselineImage", "baseline.jpg", imageType, saved)
	writeFile(t, writer, "currentImage", "current.jpg", "image/jpeg", current)
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/vision/compare", &body)
	request.Header.Set("Content-Type", writer.FormDataContentType())
	request.Header.Set("X-Schema-Version", "1.0")
	request.Header.Set("X-Client-Version", "test")
	request.Header.Set("Idempotency-Key", "compare-test")
	return request
}

func writeField(t *testing.T, writer *multipart.Writer, name, value string) {
	t.Helper()
	if err := writer.WriteField(name, value); err != nil {
		t.Fatal(err)
	}
}

func writeFile(
	t *testing.T,
	writer *multipart.Writer,
	field, filename, contentType string,
	content []byte,
) {
	t.Helper()
	header := make(textproto.MIMEHeader)
	header.Set("Content-Disposition", fmt.Sprintf(`form-data; name="%s"; filename="%s"`, field, filename))
	header.Set("Content-Type", contentType)
	part, err := writer.CreatePart(header)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := part.Write(content); err != nil {
		t.Fatal(err)
	}
}
