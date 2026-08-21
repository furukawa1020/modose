package baselineapi

import (
	"context"
	"fmt"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/structured"
	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

type FailureStage string

const (
	StageRequest    FailureStage = "request"
	StageGeneration FailureStage = "generation"
)

type Error struct {
	Stage FailureStage
	Err   error
}

func (e *Error) Error() string {
	return fmt.Sprintf("baseline analysis failed: %s", e.Stage)
}

func (e *Error) Unwrap() error {
	return e.Err
}

type Output struct {
	Result        baseline.Result
	PromptVersion string
	Attempts      []structured.AttemptMetadata
	Repaired      bool
}

type Service struct {
	generator vertex.Generator
}

func NewService(generator vertex.Generator) *Service {
	return &Service{generator: generator}
}

func (service *Service) Analyze(ctx context.Context, jpeg []byte) (Output, error) {
	request, err := baseline.BuildRequest(jpeg)
	if err != nil {
		return Output{}, &Error{Stage: StageRequest, Err: err}
	}

	outcome, err := structured.GenerateAndDecode(
		ctx,
		service.generator,
		request.Generation,
		baseline.Validate,
	)
	if err != nil {
		return Output{}, &Error{Stage: StageGeneration, Err: err}
	}

	return Output{
		Result:        outcome.Value,
		PromptVersion: request.PromptVersion,
		Attempts:      outcome.Attempts,
		Repaired:      outcome.Repaired,
	}, nil
}
