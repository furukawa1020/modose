package baseline

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestPromptHasStableVersionAndSafetyInstructions(t *testing.T) {
	if PromptVersion != "baseline-v1" {
		t.Fatalf("PromptVersion = %q", PromptVersion)
	}
	for _, required := range []string{
		"JSON Schema",
		"推測しない",
		"excludedCandidates",
		"0〜1000",
		"先頭5個へ切り詰めず",
	} {
		if !strings.Contains(SystemInstruction+UserPrompt, required) {
			t.Errorf("prompt does not contain %q", required)
		}
	}
}

func TestResponseSchemaIsClosedAndStructured(t *testing.T) {
	var schema map[string]any
	if err := json.Unmarshal(ResponseSchemaJSON, &schema); err != nil {
		t.Fatalf("schema is invalid JSON: %v", err)
	}
	if schema["type"] != "object" || schema["additionalProperties"] != false {
		t.Fatalf("root schema is not a closed object: %#v", schema)
	}

	properties := schema["properties"].(map[string]any)
	objects := properties["objects"].(map[string]any)
	if objects["minItems"] != float64(1) {
		t.Errorf("objects minItems = %#v", objects["minItems"])
	}
	// Schema permits reporting overflow so Validate can reject scenes above five
	// instead of allowing the model to silently truncate them.
	if objects["maxItems"] != float64(20) {
		t.Errorf("objects maxItems = %#v", objects["maxItems"])
	}

	item := objects["items"].(map[string]any)
	itemProperties := item["properties"].(map[string]any)
	box := itemProperties["boundingBox"].(map[string]any)
	boxProperties := box["properties"].(map[string]any)
	for _, coordinate := range []string{"yMin", "xMin", "yMax", "xMax"} {
		value := boxProperties[coordinate].(map[string]any)
		if value["minimum"] != float64(0) || value["maximum"] != float64(1000) {
			t.Errorf("%s range = %#v", coordinate, value)
		}
	}
}

func TestResponseSchemaPinsSupportedEnums(t *testing.T) {
	var schema struct {
		Properties struct {
			Objects struct {
				Items struct {
					Properties struct {
						Symmetry struct {
							Enum []string `json:"enum"`
						} `json:"symmetry"`
					} `json:"properties"`
				} `json:"items"`
			} `json:"objects"`
			Excluded struct {
				Items struct {
					Properties struct {
						Reason struct {
							Enum []string `json:"enum"`
						} `json:"reason"`
					} `json:"properties"`
				} `json:"items"`
			} `json:"excludedCandidates"`
		} `json:"properties"`
	}
	if err := json.Unmarshal(ResponseSchemaJSON, &schema); err != nil {
		t.Fatalf("schema is invalid JSON: %v", err)
	}
	if len(schema.Properties.Objects.Items.Properties.Symmetry.Enum) != 3 {
		t.Errorf("symmetry enum = %#v", schema.Properties.Objects.Items.Properties.Symmetry.Enum)
	}
	if len(schema.Properties.Excluded.Items.Properties.Reason.Enum) != 6 {
		t.Errorf("reason enum = %#v", schema.Properties.Excluded.Items.Properties.Reason.Enum)
	}
}
