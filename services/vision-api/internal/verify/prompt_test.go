package verify

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestPromptRequiresConservativeWholeSceneVerification(t *testing.T) {
	if PromptVersion != "verify-v1" {
		t.Fatalf("PromptVersion = %q", PromptVersion)
	}
	for _, required := range []string{
		"1枚目: 保存時",
		"2枚目: 局所復元完了後",
		"確信できない結果をverifiedへ昇格させず",
		"全Baseline物体",
		"一部だけ確認できたことをverifiedの根拠にしない",
	} {
		if !strings.Contains(SystemInstruction+UserPrompt, required) {
			t.Errorf("prompt does not contain %q", required)
		}
	}
}

func TestResponseSchemaIsClosedAndPinsThreeStatuses(t *testing.T) {
	var schema map[string]any
	if err := json.Unmarshal(ResponseSchemaJSON, &schema); err != nil {
		t.Fatalf("schema is invalid JSON: %v", err)
	}
	if schema["type"] != "object" || schema["additionalProperties"] != false {
		t.Fatalf("root schema is not closed: %#v", schema)
	}
	properties := schema["properties"].(map[string]any)
	status := properties["status"].(map[string]any)
	values := status["enum"].([]any)
	if len(values) != 3 ||
		values[0] != "verified" ||
		values[1] != "needs_correction" ||
		values[2] != "uncertain" {
		t.Errorf("status enum = %#v", values)
	}
}

func TestResponseSchemaClosesCorrectionItems(t *testing.T) {
	var schema map[string]any
	if err := json.Unmarshal(ResponseSchemaJSON, &schema); err != nil {
		t.Fatalf("schema is invalid JSON: %v", err)
	}
	properties := schema["properties"].(map[string]any)
	corrections := properties["corrections"].(map[string]any)
	if corrections["maxItems"] != float64(5) {
		t.Errorf("corrections maxItems = %#v", corrections["maxItems"])
	}
	item := corrections["items"].(map[string]any)
	if item["additionalProperties"] != false {
		t.Errorf("correction item is not closed: %#v", item)
	}
	required := item["required"].([]any)
	if len(required) != 2 {
		t.Errorf("correction required = %#v", required)
	}
}
