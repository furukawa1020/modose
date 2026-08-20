package compare

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestPromptPinsImageOrderAndDecisionBoundary(t *testing.T) {
	if PromptVersion != "compare-v1" {
		t.Fatalf("PromptVersion = %q", PromptVersion)
	}
	for _, required := range []string{
		"1枚目: 保存時",
		"2枚目: 現在画像",
		"ambiguous",
		"現実座標",
		"addedObjects",
	} {
		if !strings.Contains(SystemInstruction+UserPrompt, required) {
			t.Errorf("prompt does not contain %q", required)
		}
	}
}

func TestResponseSchemaIsClosedAndPinsSevenStates(t *testing.T) {
	var schema map[string]any
	if err := json.Unmarshal(ResponseSchemaJSON, &schema); err != nil {
		t.Fatalf("schema is invalid JSON: %v", err)
	}
	if schema["type"] != "object" || schema["additionalProperties"] != false {
		t.Fatalf("root schema is not closed: %#v", schema)
	}
	properties := schema["properties"].(map[string]any)
	matches := properties["matches"].(map[string]any)
	if matches["minItems"] != float64(1) || matches["maxItems"] != float64(5) {
		t.Errorf("matches range = %#v", matches)
	}
	item := matches["items"].(map[string]any)
	itemProperties := item["properties"].(map[string]any)
	state := itemProperties["state"].(map[string]any)
	if len(state["enum"].([]any)) != 7 {
		t.Errorf("state enum = %#v", state["enum"])
	}
}

func TestResponseSchemaConstrainsConfidenceAndCoordinates(t *testing.T) {
	var schema map[string]any
	if err := json.Unmarshal(ResponseSchemaJSON, &schema); err != nil {
		t.Fatalf("schema is invalid JSON: %v", err)
	}
	properties := schema["properties"].(map[string]any)
	matches := properties["matches"].(map[string]any)
	item := matches["items"].(map[string]any)
	itemProperties := item["properties"].(map[string]any)

	confidence := itemProperties["confidence"].(map[string]any)
	if confidence["minimum"] != float64(0) || confidence["maximum"] != float64(1) {
		t.Errorf("confidence range = %#v", confidence)
	}

	box := itemProperties["currentBox"].(map[string]any)
	boxProperties := box["properties"].(map[string]any)
	for _, coordinate := range []string{"yMin", "xMin", "yMax", "xMax"} {
		value := boxProperties[coordinate].(map[string]any)
		if value["minimum"] != float64(0) || value["maximum"] != float64(1000) {
			t.Errorf("%s range = %#v", coordinate, value)
		}
	}
}
