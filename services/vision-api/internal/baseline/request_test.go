package baseline

import (
	"bytes"
	"errors"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

func TestBuildRequestMatchesBaselineV1GoldenContract(t *testing.T) {
	jpeg := []byte{0xff, 0xd8, 0xff, 0xd9}

	request, err := BuildRequest(jpeg)
	if err != nil {
		t.Fatalf("BuildRequest() error = %v", err)
	}

	if request.PromptVersion != "baseline-v1" {
		t.Errorf("PromptVersion = %q", request.PromptVersion)
	}
	if request.Generation.SystemInstruction != SystemInstruction {
		t.Error("system instruction differs from baseline-v1")
	}
	if request.Generation.Prompt != UserPrompt {
		t.Error("user prompt differs from baseline-v1")
	}
	if request.Generation.ResponseMediaType != vertex.JSONMediaType {
		t.Errorf("ResponseMediaType = %q", request.Generation.ResponseMediaType)
	}
	if !bytes.Equal(request.Generation.SchemaJSON, ResponseSchemaJSON) {
		t.Error("response schema differs from baseline-v1")
	}
	if len(request.Generation.Images) != 1 {
		t.Fatalf("Images = %d, want 1", len(request.Generation.Images))
	}
	if request.Generation.Images[0].MediaType != vertex.JPEGMediaType ||
		!bytes.Equal(request.Generation.Images[0].Data, jpeg) {
		t.Errorf("image = %#v", request.Generation.Images[0])
	}
	if err := vertex.ValidateRequest(request.Generation); err != nil {
		t.Errorf("generated Vertex contract is invalid: %v", err)
	}
}

func TestBuildRequestRejectsMissingImage(t *testing.T) {
	_, err := BuildRequest(nil)

	var requestError *RequestError
	if !errors.As(err, &requestError) {
		t.Fatalf("error type = %T, want *RequestError", err)
	}
	if requestError.Reason != "image_required" {
		t.Errorf("Reason = %q", requestError.Reason)
	}
}

func TestBuildRequestOwnsMutableInputBytes(t *testing.T) {
	jpeg := []byte{1, 2, 3}
	request, err := BuildRequest(jpeg)
	if err != nil {
		t.Fatalf("BuildRequest() error = %v", err)
	}

	jpeg[0] = 9
	ResponseSchemaJSON[0] = 'X'
	t.Cleanup(func() {
		ResponseSchemaJSON[0] = '{'
	})

	if request.Generation.Images[0].Data[0] != 1 {
		t.Error("request image changed with caller buffer")
	}
	if request.Generation.SchemaJSON[0] != '{' {
		t.Error("request schema changed with package buffer")
	}
}
