package verifyapi

import (
	"context"
	"fmt"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/structured"
	"github.com/furukawa1020/modose/services/vision-api/internal/verify"
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
	return fmt.Sprintf("scene verification failed: %s", e.Stage)
}

func (e *Error) Unwrap() error {
	return e.Err
}

type Output struct {
	Result        verify.Result
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

func (service *Service) Verify(
	ctx context.Context,
	savedJPEG []byte,
	finalJPEG []byte,
	saved baseline.Result,
) (Output, error) {
	request, err := verify.BuildRequest(savedJPEG, finalJPEG, saved)
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
		func(result verify.Result) error {
			return verify.Validate(result, ids)
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
