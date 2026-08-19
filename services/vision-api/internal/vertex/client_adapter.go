package vertex

import (
	"context"
	"encoding/json"
	"errors"
	"strings"

	"google.golang.org/genai"
)

type generateContentFunc func(
	context.Context,
	string,
	[]*genai.Content,
	*genai.GenerateContentConfig,
) (*genai.GenerateContentResponse, error)

// Generate sends one validated multimodal request to the configured Vertex model.
func (c *Client) Generate(ctx context.Context, request GenerateRequest) (GenerateResponse, error) {
	return generateContent(ctx, c.config, request, c.sdk.Models.GenerateContent)
}

func generateContent(
	ctx context.Context,
	config Config,
	request GenerateRequest,
	call generateContentFunc,
) (GenerateResponse, error) {
	if err := ValidateRequest(request); err != nil {
		return GenerateResponse{}, err
	}

	var responseSchema any
	if err := json.Unmarshal(request.SchemaJSON, &responseSchema); err != nil {
		return GenerateResponse{}, &Error{Reason: FailureInvalidRequest, Err: err}
	}

	parts := []*genai.Part{{Text: request.Prompt}}
	for _, image := range request.Images {
		parts = append(parts, &genai.Part{
			InlineData: &genai.Blob{
				Data:     image.Data,
				MIMEType: image.MediaType,
			},
		})
	}

	generationConfig := &genai.GenerateContentConfig{
		ResponseMIMEType:   request.ResponseMediaType,
		ResponseJsonSchema: responseSchema,
	}
	if request.SystemInstruction != "" {
		generationConfig.SystemInstruction = &genai.Content{
			Parts: []*genai.Part{{Text: request.SystemInstruction}},
		}
	}

	callContext, cancel := context.WithTimeout(ctx, config.Deadline)
	defer cancel()

	response, err := call(
		callContext,
		config.ModelID,
		[]*genai.Content{{Role: genai.RoleUser, Parts: parts}},
		generationConfig,
	)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(callContext.Err(), context.DeadlineExceeded) {
			return GenerateResponse{}, &Error{Reason: FailureDeadlineExceeded, Err: err}
		}
		return GenerateResponse{}, &Error{Reason: FailureSDK, Err: err}
	}
	if response == nil || strings.TrimSpace(response.Text()) == "" {
		return GenerateResponse{}, &Error{Reason: FailureEmptyResponse}
	}

	result := GenerateResponse{
		Text:       response.Text(),
		ResponseID: response.ResponseID,
		ModelID:    config.ModelID,
	}
	if response.UsageMetadata != nil {
		result.Usage = Usage{
			PromptTokens:    response.UsageMetadata.PromptTokenCount,
			CandidateTokens: response.UsageMetadata.CandidatesTokenCount,
			TotalTokens:     response.UsageMetadata.TotalTokenCount,
		}
	}

	return result, nil
}
