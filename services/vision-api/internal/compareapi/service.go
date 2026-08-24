package compareapi

import (
	"context"
	"fmt"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/compare"
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
	return fmt.Sprintf("scene comparison failed: %s", e.Stage)
}

func (e *Error) Unwrap() error {
	return e.Err
}

type Output struct {
	Result        compare.Result
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

func (service *Service) Compare(
	ctx context.Context,
	savedJPEG []byte,
	currentJPEG []byte,
	saved baseline.Result,
) (Output, error) {
	request, err := compare.BuildRequest(savedJPEG, currentJPEG, saved)
	if err != nil {
		return Output{}, &Error{Stage: StageRequest, Err: err}
	}

	ids := make([]string, len(saved.Objects))
	for index, object := range saved.Objects {
		ids[index] = object.ID
	}
	outcome, err := structured.GenerateAndDecode(
		ctx,
		service.generator,
		request.Generation,
		func(result compare.Result) error {
			return compare.Validate(result, ids)
		},
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
