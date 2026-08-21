package structured

import (
	"context"
	"fmt"

	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

type TerminalReason string

const (
	TerminalInitialGeneration TerminalReason = "initial_generation_failed"
	TerminalRepairRequest     TerminalReason = "repair_request_failed"
	TerminalRepairGeneration TerminalReason = "repair_generation_failed"
	TerminalRepairRejected   TerminalReason = "repair_output_rejected"
)

type AttemptMetadata struct {
	ResponseID string
	ModelID    string
	Usage      vertex.Usage
}

type Outcome[T any] struct {
	Value    T
	Attempts []AttemptMetadata
	Repaired bool
}

type TerminalError struct {
	Reason   TerminalReason
	Attempts []AttemptMetadata
	Err      error
}

func (e *TerminalError) Error() string {
	return fmt.Sprintf("structured generation failed: %s", e.Reason)
}

func (e *TerminalError) Unwrap() error {
	return e.Err
}

func GenerateAndDecode[T any](
	ctx context.Context,
	generator vertex.Generator,
	request vertex.GenerateRequest,
	validate Validator[T],
) (Outcome[T], error) {
	var zero Outcome[T]
	if generator == nil {
		return zero, &TerminalError{
			Reason: TerminalInitialGeneration,
			Err:    fmt.Errorf("generator is required"),
		}
	}

	initial, err := generator.Generate(ctx, request)
	if err != nil {
		return zero, &TerminalError{Reason: TerminalInitialGeneration, Err: err}
	}
	attempts := []AttemptMetadata{metadata(initial)}

	value, decodeErr := Decode(initial.Text, validate)
	if decodeErr == nil {
		return Outcome[T]{Value: value, Attempts: attempts}, nil
	}

	repairRequest, err := BuildRepairRequest(request, initial.Text)
	if err != nil {
		return zero, &TerminalError{
			Reason:   TerminalRepairRequest,
			Attempts: attempts,
			Err:      err,
		}
	}

	repaired, err := generator.Generate(ctx, repairRequest)
	if err != nil {
		return zero, &TerminalError{
			Reason:   TerminalRepairGeneration,
			Attempts: attempts,
			Err:      err,
		}
	}
	attempts = append(attempts, metadata(repaired))

	value, err = Decode(repaired.Text, validate)
	if err != nil {
		return zero, &TerminalError{
			Reason:   TerminalRepairRejected,
			Attempts: attempts,
			Err:      err,
		}
	}
	return Outcome[T]{
		Value:    value,
		Attempts: attempts,
		Repaired: true,
	}, nil
}

func metadata(response vertex.GenerateResponse) AttemptMetadata {
	return AttemptMetadata{
		ResponseID: response.ResponseID,
		ModelID:    response.ModelID,
		Usage:      response.Usage,
	}
}
