package vertex

import (
	"context"
	"encoding/json"
	"errors"
)

const (
	JPEGMediaType = "image/jpeg"
	JSONMediaType = "application/json"
)

type InlineImage struct {
	MediaType string
	Data      []byte
}

type GenerateRequest struct {
	SystemInstruction string
	Prompt            string
	Images            []InlineImage
	ResponseMediaType string
	SchemaJSON        []byte
}

type Usage struct {
	PromptTokens     int32
	CandidateTokens  int32
	TotalTokens      int32
}

type GenerateResponse struct {
	Text       string
	ResponseID string
	ModelID    string
	Usage      Usage
}

type FailureReason string

const (
	FailureInvalidRequest   FailureReason = "invalid_request"
	FailureClientCreation   FailureReason = "client_creation_failed"
	FailureDeadlineExceeded FailureReason = "deadline_exceeded"
	FailureSDK              FailureReason = "sdk_failed"
	FailureEmptyResponse    FailureReason = "empty_response"
)

type Error struct {
	Reason FailureReason
	Err    error
}

func (e *Error) Error() string {
	return "Vertex generation failed: " + string(e.Reason)
}

func (e *Error) Unwrap() error {
	return e.Err
}

type Generator interface {
	Generate(context.Context, GenerateRequest) (GenerateResponse, error)
}

func ValidateRequest(request GenerateRequest) error {
	if request.Prompt == "" || request.ResponseMediaType != JSONMediaType {
		return invalidRequest()
	}
	for _, image := range request.Images {
		if image.MediaType != JPEGMediaType || len(image.Data) == 0 {
			return invalidRequest()
		}
	}
	if len(request.SchemaJSON) == 0 || !json.Valid(request.SchemaJSON) {
		return invalidRequest()
	}
	var schema map[string]any
	if err := json.Unmarshal(request.SchemaJSON, &schema); err != nil || schema == nil {
		return invalidRequest()
	}
	return nil
}

func invalidRequest() error {
	return &Error{Reason: FailureInvalidRequest, Err: errors.New("request contract rejected")}
}
