package verifyapi

import (
	"context"
	"errors"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/verify"
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

func TestVerifyBuildsGeneratesAndValidatesResult(t *testing.T) {
	generator := &fakeGenerator{responses: []vertex.GenerateResponse{{
		Text:       verifiedJSON(),
		ResponseID: "response-1",
		ModelID:    "model-a",
		Usage:      vertex.Usage{TotalTokens: 31},
	}}}

	output, err := NewService(generator).Verify(
		context.Background(),
		[]byte{1},
		[]byte{2},
		validBaseline(),
	)
	if err != nil {
		t.Fatalf("Verify() error = %v", err)
	}
	if output.Result.Status != verify.StatusVerified {
		t.Errorf("Status = %q", output.Result.Status)
	}
	if output.PromptVersion != verify.PromptVersion ||
		output.Repaired ||
		len(output.Attempts) != 1 ||
		output.Attempts[0].ResponseID != "response-1" {
		t.Errorf("Output = %#v", output)
	}
}

func TestVerifyPreservesNeedsCorrection(t *testing.T) {
	generator := &fakeGenerator{responses: []vertex.GenerateResponse{{
		Text: `{
			"status":"needs_correction",
			"corrections":[{"baselineObjectId":"wallet","reason":"位置がずれている"}],
			"uncertaintyReason":""
		}`,
	}}}

	output, err := NewService(generator).Verify(
		context.Background(),
		[]byte{1},
		[]byte{2},
		validBaseline(),
	)
	if err != nil {
		t.Fatalf("Verify() error = %v", err)
	}
	if output.Result.Status != verify.StatusNeedsCorrection ||
		len(output.Result.Corrections) != 1 {
		t.Fatalf("Result = %#v", output.Result)
	}
}

func TestVerifyReportsSuccessfulRepair(t *testing.T) {
	generator := &fakeGenerator{responses: []vertex.GenerateResponse{
		{Text: `{
			"status":"verified",
			"corrections":[{"baselineObjectId":"wallet","reason":"位置がずれている"}],
			"uncertaintyReason":""
		}`, ResponseID: "initial"},
		{Text: verifiedJSON(), ResponseID: "repair"},
	}}

	output, err := NewService(generator).Verify(
		context.Background(),
		[]byte{1},
		[]byte{2},
		validBaseline(),
	)
	if err != nil {
		t.Fatalf("Verify() error = %v", err)
	}
	if !output.Repaired || len(output.Attempts) != 2 || generator.calls != 2 {
		t.Errorf("Output = %#v, calls = %d", output, generator.calls)
	}
}

func TestVerifyClassifiesFailures(t *testing.T) {
	t.Run("要求不備", func(t *testing.T) {
		_, err := NewService(&fakeGenerator{}).Verify(
			context.Background(),
			nil,
			[]byte{2},
			validBaseline(),
		)
		var serviceError *Error
		if !errors.As(err, &serviceError) || serviceError.Stage != StageRequest {
			t.Fatalf("error = %#v", err)
		}
	})

	t.Run("生成失敗", func(t *testing.T) {
		private := errors.New("private Vertex detail")
		generator := &fakeGenerator{err: private}
		_, err := NewService(generator).Verify(
			context.Background(),
			[]byte{1},
			[]byte{2},
			validBaseline(),
		)
		var serviceError *Error
		if !errors.As(err, &serviceError) || serviceError.Stage != StageGeneration {
			t.Fatalf("error = %#v", err)
		}
		if !errors.Is(err, private) || err.Error() == private.Error() {
			t.Errorf("error = %v", err)
		}
	})
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

func verifiedJSON() string {
	return `{
		"status":"verified",
		"corrections":[],
		"uncertaintyReason":""
	}`
}
