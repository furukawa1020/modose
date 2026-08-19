package vertex

import (
	"context"
	"errors"
	"testing"
	"time"

	"google.golang.org/genai"
)

func TestGenerateContentBuildsStructuredMultimodalRequest(t *testing.T) {
	config := Config{
		Project:  "modose-test",
		Location: "asia-northeast1",
		ModelID:  "gemini-test",
		Deadline: time.Second,
	}
	request := GenerateRequest{
		SystemInstruction: "JSONだけを返す",
		Prompt:            "保存状態を解析する",
		Images: []InlineImage{{
			Data:      []byte{0xff, 0xd8, 0xff},
			MediaType: JPEGMediaType,
		}},
		ResponseMediaType: JSONMediaType,
		SchemaJSON:       []byte(`{"type":"object","properties":{"objects":{"type":"array"}}}`),
	}

	var receivedModel string
	var receivedContents []*genai.Content
	var receivedConfig *genai.GenerateContentConfig
	response, err := generateContent(context.Background(), config, request, func(
		_ context.Context,
		model string,
		contents []*genai.Content,
		sdkConfig *genai.GenerateContentConfig,
	) (*genai.GenerateContentResponse, error) {
		receivedModel = model
		receivedContents = contents
		receivedConfig = sdkConfig
		return &genai.GenerateContentResponse{
			ResponseID: "response-1",
			Candidates: []*genai.Candidate{{
				Content: &genai.Content{Parts: []*genai.Part{{Text: `{"objects":[]}`}}},
			}},
			UsageMetadata: &genai.GenerateContentResponseUsageMetadata{
				PromptTokenCount:     10,
				CandidatesTokenCount: 4,
				TotalTokenCount:      14,
			},
		}, nil
	})
	if err != nil {
		t.Fatalf("generateContent() error = %v", err)
	}

	if receivedModel != config.ModelID {
		t.Errorf("model = %q, want %q", receivedModel, config.ModelID)
	}
	if len(receivedContents) != 1 || receivedContents[0].Role != genai.RoleUser {
		t.Fatalf("contents = %#v, want one user content", receivedContents)
	}
	if len(receivedContents[0].Parts) != 2 {
		t.Fatalf("parts = %d, want text and image", len(receivedContents[0].Parts))
	}
	if receivedContents[0].Parts[0].Text != request.Prompt {
		t.Errorf("prompt = %q, want %q", receivedContents[0].Parts[0].Text, request.Prompt)
	}
	image := receivedContents[0].Parts[1].InlineData
	if image == nil || image.MIMEType != JPEGMediaType {
		t.Fatalf("image = %#v, want JPEG inline data", image)
	}
	if receivedConfig.ResponseMIMEType != JSONMediaType {
		t.Errorf("response MIME type = %q, want %q", receivedConfig.ResponseMIMEType, JSONMediaType)
	}
	if receivedConfig.ResponseJsonSchema == nil {
		t.Error("response JSON schema was not set")
	}
	if receivedConfig.SystemInstruction == nil ||
		receivedConfig.SystemInstruction.Parts[0].Text != request.SystemInstruction {
		t.Error("system instruction was not set")
	}
	if response.Text != `{"objects":[]}` {
		t.Errorf("response text = %q", response.Text)
	}
	if response.ResponseID != "response-1" || response.ModelID != config.ModelID {
		t.Errorf("response metadata = %#v", response)
	}
	if response.Usage.TotalTokens != 14 {
		t.Errorf("total tokens = %d, want 14", response.Usage.TotalTokens)
	}
}

func TestGenerateContentEnforcesDeadline(t *testing.T) {
	config := validTestConfig()
	config.Deadline = time.Millisecond
	request := validTestRequest()

	_, err := generateContent(context.Background(), config, request, func(
		ctx context.Context,
		_ string,
		_ []*genai.Content,
		_ *genai.GenerateContentConfig,
	) (*genai.GenerateContentResponse, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	})

	var vertexError *Error
	if !errors.As(err, &vertexError) || vertexError.Reason != FailureDeadlineExceeded {
		t.Fatalf("error = %v, want FailureDeadlineExceeded", err)
	}
}

func TestGenerateContentMapsSDKFailure(t *testing.T) {
	want := errors.New("Vertex AI unavailable")
	_, err := generateContent(context.Background(), validTestConfig(), validTestRequest(), func(
		context.Context,
		string,
		[]*genai.Content,
		*genai.GenerateContentConfig,
	) (*genai.GenerateContentResponse, error) {
		return nil, want
	})

	var vertexError *Error
	if !errors.As(err, &vertexError) || vertexError.Reason != FailureSDK {
		t.Fatalf("error = %v, want FailureSDK", err)
	}
	if !errors.Is(err, want) {
		t.Errorf("error does not wrap SDK failure: %v", err)
	}
}

func TestGenerateContentRejectsEmptyResponse(t *testing.T) {
	_, err := generateContent(context.Background(), validTestConfig(), validTestRequest(), func(
		context.Context,
		string,
		[]*genai.Content,
		*genai.GenerateContentConfig,
	) (*genai.GenerateContentResponse, error) {
		return &genai.GenerateContentResponse{}, nil
	})

	var vertexError *Error
	if !errors.As(err, &vertexError) || vertexError.Reason != FailureEmptyResponse {
		t.Fatalf("error = %v, want FailureEmptyResponse", err)
	}
}

func validTestConfig() Config {
	return Config{
		Project:  "modose-test",
		Location: "asia-northeast1",
		ModelID:  "gemini-test",
		Deadline: time.Second,
	}
}

func validTestRequest() GenerateRequest {
	return GenerateRequest{
		Prompt:            "compare",
		Images:            []InlineImage{{Data: []byte{0xff}, MediaType: JPEGMediaType}},
		ResponseMediaType: JSONMediaType,
		SchemaJSON:       []byte(`{"type":"object"}`),
	}
}
