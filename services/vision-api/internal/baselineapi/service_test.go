package baselineapi

import (
	"context"
	"errors"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

type fakeGenerator struct {
	responses []vertex.GenerateResponse
	err       error
	calls     int
}

func (generator *fakeGenerator) Generate(
	context.Context,
	vertex.GenerateRequest,
) (vertex.GenerateResponse, error) {
	generator.calls++
	if generator.err != nil {
		return vertex.GenerateResponse{}, generator.err
	}
	return generator.responses[generator.calls-1], nil
}

func TestAnalyzeBuildsGeneratesAndValidatesBaseline(t *testing.T) {
	generator := &fakeGenerator{responses: []vertex.GenerateResponse{{
		Text:       validBaselineJSON(),
		ResponseID: "response-1",
		ModelID:    "model-a",
		Usage:      vertex.Usage{TotalTokens: 42},
	}}}
	service := NewService(generator)

	output, err := service.Analyze(context.Background(), []byte{0xff, 0xd8})
	if err != nil {
		t.Fatalf("Analyze() error = %v", err)
	}
	if len(output.Result.Objects) != 1 ||
		output.Result.Objects[0].ID != "wallet" {
		t.Errorf("Result = %#v", output.Result)
	}
	if output.PromptVersion != baseline.PromptVersion {
		t.Errorf("PromptVersion = %q", output.PromptVersion)
	}
	if output.Repaired || len(output.Attempts) != 1 ||
		output.Attempts[0].ResponseID != "response-1" ||
		output.Attempts[0].Usage.TotalTokens != 42 {
		t.Errorf("Output metadata = %#v", output)
	}
}

func TestAnalyzeReportsSuccessfulRepair(t *testing.T) {
	generator := &fakeGenerator{responses: []vertex.GenerateResponse{
		{Text: `{"objects":[],"unknown":true}`, ResponseID: "initial"},
		{Text: validBaselineJSON(), ResponseID: "repair"},
	}}
	output, err := NewService(generator).Analyze(context.Background(), []byte{1})
	if err != nil {
		t.Fatalf("Analyze() error = %v", err)
	}
	if !output.Repaired || len(output.Attempts) != 2 || generator.calls != 2 {
		t.Errorf("Output = %#v, calls = %d", output, generator.calls)
	}
}

func TestAnalyzeClassifiesRequestFailure(t *testing.T) {
	_, err := NewService(&fakeGenerator{}).Analyze(context.Background(), nil)

	var serviceError *Error
	if !errors.As(err, &serviceError) || serviceError.Stage != StageRequest {
		t.Fatalf("error = %#v", err)
	}
}

func TestAnalyzeClassifiesTerminalGenerationFailure(t *testing.T) {
	private := errors.New("private Vertex detail")
	generator := &fakeGenerator{err: private}

	_, err := NewService(generator).Analyze(context.Background(), []byte{1})

	var serviceError *Error
	if !errors.As(err, &serviceError) || serviceError.Stage != StageGeneration {
		t.Fatalf("error = %#v", err)
	}
	if !errors.Is(err, private) || generator.calls != 1 {
		t.Errorf("error = %v, calls = %d", err, generator.calls)
	}
	if err.Error() == private.Error() {
		t.Errorf("public service error leaked dependency detail: %v", err)
	}
}

func validBaselineJSON() string {
	return `{
		"objects":[{
			"id":"wallet",
			"displayName":"黒い財布",
			"appearanceFeatures":["黒色","長方形"],
			"boundingBox":{"yMin":100,"xMin":200,"yMax":500,"xMax":700},
			"orientationImportant":true,
			"symmetry":"none"
		}],
		"excludedCandidates":[]
	}`
}
