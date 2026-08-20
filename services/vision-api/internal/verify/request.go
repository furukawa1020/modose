package verify

import (
	"encoding/json"
	"fmt"

	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

type Request struct {
	PromptVersion string
	Generation    vertex.GenerateRequest
}

type RequestError struct {
	Reason string
}

func (e *RequestError) Error() string {
	return fmt.Sprintf("verify request rejected: %s", e.Reason)
}

func BuildRequest(savedJPEG, finalJPEG []byte, saved baseline.Result) (Request, error) {
	if len(savedJPEG) == 0 {
		return Request{}, &RequestError{Reason: "saved_image_required"}
	}
	if len(finalJPEG) == 0 {
		return Request{}, &RequestError{Reason: "final_image_required"}
	}
	if err := baseline.Validate(saved); err != nil {
		return Request{}, &RequestError{Reason: "baseline_invalid"}
	}

	objectsJSON, err := json.Marshal(saved.Objects)
	if err != nil {
		return Request{}, &RequestError{Reason: "baseline_encoding_failed"}
	}
	prompt := UserPrompt + "\n\nBaseline物体一覧(JSON):\n" + string(objectsJSON)

	generation := vertex.GenerateRequest{
		SystemInstruction: SystemInstruction,
		Prompt:            prompt,
		Images: []vertex.InlineImage{
			{MediaType: vertex.JPEGMediaType, Data: append([]byte(nil), savedJPEG...)},
			{MediaType: vertex.JPEGMediaType, Data: append([]byte(nil), finalJPEG...)},
		},
		ResponseMediaType: vertex.JSONMediaType,
		SchemaJSON:        append([]byte(nil), ResponseSchemaJSON...),
	}
	if err := vertex.ValidateRequest(generation); err != nil {
		return Request{}, &RequestError{Reason: "generation_contract_invalid"}
	}

	return Request{PromptVersion: PromptVersion, Generation: generation}, nil
}
