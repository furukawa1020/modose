package compareapi

import (
	"context"
	"errors"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/compare"
	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

type fakeGenerator struct {
	responses []vertex.GenerateResponse
	err       error
	calls     int
}

func (generator *fakeGenerator) Generate(context.Context, vertex.GenerateRequest) (vertex.GenerateResponse, error) {
	generator.calls++
	if generator.err != nil {
		return vertex.GenerateResponse{}, generator.err
	}
	return generator.responses[generator.calls-1], nil
}

func TestCompareBuildsGeneratesAndValidatesResult(t *testing.T) {
	generator := &fakeGenerator{responses: []vertex.GenerateResponse{{
		Text:       validCompareJSON(),
		ResponseID: "response-1",
		ModelID:    "model-a",
		Usage:      vertex.Usage{TotalTokens: 52},
	}}}
	service := NewService(generator)

	output, err := service.Compare(context.Background(), []byte{1}, []byte{2}, validBaseline())
	if err != nil {
		t.Fatalf("Compare() error = %v", err)
	}
	if len(output.Result.Matches) != 1 ||
		output.Result.Matches[0].State != compare.StateMoved {
		t.Errorf("Result = %#v", output.Result)
	}
	if output.PromptVersion != compare.PromptVersion ||
		output.Repaired ||
		len(output.Attempts) != 1 ||
		output.Attempts[0].ResponseID != "response-1" {
		t.Errorf("Output = %#v", output)
	}
}

func TestCompareReportsSuccessfulRepair(t *testing.T) {
	generator := &fakeGenerator{responses: []vertex.GenerateResponse{
		{Text: `{"matches":[],"addedObjects":[]}`, ResponseID: "initial"},
		{Text: validCompareJSON(), ResponseID: "repair"},
	}}
	output, err := NewService(generator).Compare(
		context.Background(),
		[]byte{1},
		[]byte{2},
		validBaseline(),
	)
	if err != nil {
		t.Fatalf("Compare() error = %v", err)
	}
	if !output.Repaired || len(output.Attempts) != 2 || generator.calls != 2 {
		t.Errorf("Output = %#v, calls = %d", output, generator.calls)
	}
}

func TestCompareClassifiesInvalidInputAsRequestFailure(t *testing.T) {
	_, err := NewService(&fakeGenerator{}).Compare(
		context.Background(),
		nil,
		[]byte{2},
		validBaseline(),
	)
	var serviceError *Error
	if !errors.As(err, &serviceError) || serviceError.Stage != StageRequest {
		t.Fatalf("error = %#v", err)
	}
}

func TestCompareClassifiesTerminalGenerationFailure(t *testing.T) {
	private := errors.New("private Vertex detail")
	generator := &fakeGenerator{err: private}

	_, err := NewService(generator).Compare(
		context.Background(),
		[]byte{1},
		[]byte{2},
		validBaseline(),
	)
	var serviceError *Error
	if !errors.As(err, &serviceError) || serviceError.Stage != StageGeneration {
		t.Fatalf("error = %#v", err)
	}
	if !errors.Is(err, private) || generator.calls != 1 {
		t.Errorf("error = %v, calls = %d", err, generator.calls)
	}
	if err.Error() == private.Error() {
		t.Errorf("service error leaked dependency detail: %v", err)
	}
}

func validBaseline() baseline.Result {
	return baseline.Result{Objects: []baseline.Object{{
		ID:                 "wallet",
		DisplayName:        "黒い財布",
		AppearanceFeatures: []string{"黒色", "長方形"},
		BoundingBox: baseline.BoundingBox{
			YMin: 100, XMin: 200, YMax: 500, XMax: 700,
		},
		Symmetry: baseline.SymmetryNone,
	}}}
}

func validCompareJSON() string {
	return `{
		"matches":[{
			"baselineObjectId":"wallet",
			"state":"moved",
			"confidence":0.95,
			"currentBox":{"yMin":120,"xMin":250,"yMax":520,"xMax":750},
			"ambiguityReason":""
		}],
		"addedObjects":[]
	}`
}
