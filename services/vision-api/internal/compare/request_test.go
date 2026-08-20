package compare

import (
	"bytes"
	"errors"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

func TestBuildRequestMatchesCompareV1GoldenContract(t *testing.T) {
	savedImage := []byte{1, 2, 3}
	currentImage := []byte{4, 5, 6}
	saved := validBaseline()

	request, err := BuildRequest(savedImage, currentImage, saved)
	if err != nil {
		t.Fatalf("BuildRequest() error = %v", err)
	}

	if request.PromptVersion != "compare-v1" {
		t.Errorf("PromptVersion = %q", request.PromptVersion)
	}
	if request.Generation.SystemInstruction != SystemInstruction {
		t.Error("system instruction differs from compare-v1")
	}
	if !strings.HasPrefix(request.Generation.Prompt, UserPrompt) ||
		!strings.Contains(request.Generation.Prompt, `"id":"wallet"`) {
		t.Errorf("prompt does not contain validated Baseline objects: %q", request.Generation.Prompt)
	}
	if len(request.Generation.Images) != 2 {
		t.Fatalf("Images = %d, want 2", len(request.Generation.Images))
	}
	if !bytes.Equal(request.Generation.Images[0].Data, savedImage) {
		t.Error("first image is not the saved image")
	}
	if !bytes.Equal(request.Generation.Images[1].Data, currentImage) {
		t.Error("second image is not the current image")
	}
	if request.Generation.ResponseMediaType != vertex.JSONMediaType ||
		!bytes.Equal(request.Generation.SchemaJSON, ResponseSchemaJSON) {
		t.Error("structured response contract differs from compare-v1")
	}
	if err := vertex.ValidateRequest(request.Generation); err != nil {
		t.Errorf("generated Vertex contract is invalid: %v", err)
	}
}

func TestBuildRequestRejectsMissingImages(t *testing.T) {
	tests := []struct {
		saved  []byte
		current []byte
		reason string
	}{
		{current: []byte{1}, reason: "saved_image_required"},
		{saved: []byte{1}, reason: "current_image_required"},
	}
	for _, test := range tests {
		_, err := BuildRequest(test.saved, test.current, validBaseline())
		var requestError *RequestError
		if !errors.As(err, &requestError) || requestError.Reason != test.reason {
			t.Fatalf("error = %#v, want %s", err, test.reason)
		}
	}
}

func TestBuildRequestRejectsInvalidBaseline(t *testing.T) {
	_, err := BuildRequest([]byte{1}, []byte{2}, baseline.Result{})

	var requestError *RequestError
	if !errors.As(err, &requestError) || requestError.Reason != "baseline_invalid" {
		t.Fatalf("error = %#v", err)
	}
}

func TestBuildRequestOwnsImageBytes(t *testing.T) {
	savedImage := []byte{1}
	currentImage := []byte{2}
	request, err := BuildRequest(savedImage, currentImage, validBaseline())
	if err != nil {
		t.Fatalf("BuildRequest() error = %v", err)
	}
	savedImage[0] = 9
	currentImage[0] = 9
	if request.Generation.Images[0].Data[0] != 1 ||
		request.Generation.Images[1].Data[0] != 2 {
		t.Error("request images changed with caller buffers")
	}
}

func validBaseline() baseline.Result {
	return baseline.Result{Objects: []baseline.Object{{
		ID:                 "wallet",
		DisplayName:        "黒い財布",
		AppearanceFeatures: []string{"黒色", "長方形"},
		BoundingBox: baseline.BoundingBox{
			YMin: 100,
			XMin: 200,
			YMax: 500,
			XMax: 700,
		},
		Symmetry: baseline.SymmetryNone,
	}}}
}
