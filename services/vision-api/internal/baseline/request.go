package baseline

import (
	"fmt"

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
	return fmt.Sprintf("baseline request rejected: %s", e.Reason)
}

func BuildRequest(jpeg []byte) (Request, error) {
	if len(jpeg) == 0 {
		return Request{}, &RequestError{Reason: "image_required"}
	}

	generation := vertex.GenerateRequest{
		SystemInstruction: SystemInstruction,
		Prompt:            UserPrompt,
		Images: []vertex.InlineImage{{
			MediaType: vertex.JPEGMediaType,
			Data:      append([]byte(nil), jpeg...),
		}},
		ResponseMediaType: vertex.JSONMediaType,
		SchemaJSON:        append([]byte(nil), ResponseSchemaJSON...),
	}
	if err := vertex.ValidateRequest(generation); err != nil {
		return Request{}, &RequestError{Reason: "generation_contract_invalid"}
	}

	return Request{
		PromptVersion: PromptVersion,
		Generation:    generation,
	}, nil
}
