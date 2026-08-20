package structured

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"strings"
)

type FailureReason string

const (
	FailureEmptyResponse   FailureReason = "empty_response"
	FailureInvalidJSON     FailureReason = "invalid_json"
	FailureUnknownField    FailureReason = "unknown_field"
	FailureTrailingContent FailureReason = "trailing_content"
	FailureDomainRejected  FailureReason = "domain_rejected"
	FailureValidatorMissing FailureReason = "validator_missing"
)

type DecodeError struct {
	Reason FailureReason
	Err    error
}

func (e *DecodeError) Error() string {
	return fmt.Sprintf("structured output rejected: %s", e.Reason)
}

func (e *DecodeError) Unwrap() error {
	return e.Err
}

type Validator[T any] func(T) error

func Decode[T any](text string, validate Validator[T]) (T, error) {
	var zero T
	if strings.TrimSpace(text) == "" {
		return zero, &DecodeError{Reason: FailureEmptyResponse}
	}
	if validate == nil {
		return zero, &DecodeError{Reason: FailureValidatorMissing}
	}

	decoder := json.NewDecoder(strings.NewReader(text))
	decoder.DisallowUnknownFields()

	var value T
	if err := decoder.Decode(&value); err != nil {
		reason := FailureInvalidJSON
		if strings.Contains(err.Error(), "unknown field") {
			reason = FailureUnknownField
		}
		return zero, &DecodeError{Reason: reason, Err: err}
	}

	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		if err == nil {
			err = errors.New("multiple JSON values")
		}
		return zero, &DecodeError{Reason: FailureTrailingContent, Err: err}
	}

	if err := validate(value); err != nil {
		return zero, &DecodeError{Reason: FailureDomainRejected, Err: err}
	}
	return value, nil
}
