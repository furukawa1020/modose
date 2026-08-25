package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/structured"
	"github.com/furukawa1020/modose/services/vision-api/internal/verify"
	"github.com/furukawa1020/modose/services/vision-api/internal/verifyapi"
)

type fakeVerifyAnalyzer struct {
	output verifyapi.Output
	err    error
	called bool
}

func (fake *fakeVerifyAnalyzer) Verify(
	_ context.Context,
	savedJPEG, finalJPEG []byte,
	saved baseline.Result,
) (verifyapi.Output, error) {
	fake.called = true
	if string(savedJPEG) != "saved" || string(finalJPEG) != "current" {
		return verifyapi.Output{}, errors.New("unexpected image")
	}
	if len(saved.Objects) != 1 || saved.Objects[0].ID != "object-1" {
		return verifyapi.Output{}, errors.New("unexpected baseline")
	}
	return fake.output, fake.err
}

func TestVerifyHandlerPreservesUncertainResult(t *testing.T) {
	analyzer := &fakeVerifyAnalyzer{output: verifyapi.Output{
		Result: verify.Result{
			Status:            verify.StatusUncertain,
			Corrections:       []verify.Correction{},
			UncertaintyReason: "一部が隠れている",
		},
		PromptVersion: "verify-v1",
		Attempts:      []structured.AttemptMetadata{{ModelID: "gemini-test"}},
		Repaired:      true,
	}}
	request := compareRequest(
		t,
		validConfirmedObjects,
		"image/jpeg",
		[]byte("saved"),
		[]byte("current"),
	)
	recorder := httptest.NewRecorder()

	verifyHandler(analyzer).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK || !analyzer.called {
		t.Fatalf("status = %d, called = %v", recorder.Code, analyzer.called)
	}
	var response verifyResponse
	if err := json.NewDecoder(recorder.Body).Decode(&response); err != nil {
		t.Fatal(err)
	}
	if response.SchemaVersion != "1.0" ||
		response.PromptVersion != "verify-v1" ||
		response.ModelID != "gemini-test" ||
		!response.Repaired ||
		response.VerificationStatus != verify.StatusUncertain ||
		response.UncertaintyReason != "一部が隠れている" {
		t.Fatalf("unexpected response: %#v", response)
	}
}

func TestVerifyHandlerRejectsInvalidConfirmedObjects(t *testing.T) {
	analyzer := &fakeVerifyAnalyzer{}
	request := compareRequest(t, "", "image/jpeg", []byte("saved"), []byte("current"))
	recorder := httptest.NewRecorder()

	verifyHandler(analyzer).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusUnprocessableEntity {
		t.Fatalf("status = %d", recorder.Code)
	}
	if analyzer.called {
		t.Fatal("analyzer must not be called")
	}
}

func TestVerifyHandlerMapsServiceFailureWithoutLeak(t *testing.T) {
	analyzer := &fakeVerifyAnalyzer{err: &verifyapi.Error{
		Stage: verifyapi.StageGeneration,
		Err:   errors.New("secret upstream detail"),
	}}
	request := compareRequest(
		t,
		validConfirmedObjects,
		"image/jpeg",
		[]byte("saved"),
		[]byte("current"),
	)
	recorder := httptest.NewRecorder()

	verifyHandler(analyzer).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusBadGateway {
		t.Fatalf("status = %d", recorder.Code)
	}
	if strings.Contains(recorder.Body.String(), "secret upstream detail") {
		t.Fatal("service detail leaked")
	}
}
