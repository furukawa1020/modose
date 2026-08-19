package vertex

import (
	"errors"
	"testing"
	"time"
)

func TestLoadConfigRequiresExplicitProjectLocationAndModel(t *testing.T) {
	config, err := LoadConfig(mapLookup(map[string]string{
		"GOOGLE_CLOUD_PROJECT":  "project-a",
		"GOOGLE_CLOUD_LOCATION": "global",
		"VLM_MODEL_ID":          "model-from-env",
	}))
	if err != nil {
		t.Fatalf("LoadConfig() error = %v", err)
	}
	if config.ModelID != "model-from-env" || config.Deadline != 12*time.Second {
		t.Fatalf("LoadConfig() = %#v", config)
	}

	for _, missing := range []string{"GOOGLE_CLOUD_PROJECT", "GOOGLE_CLOUD_LOCATION", "VLM_MODEL_ID"} {
		values := map[string]string{
			"GOOGLE_CLOUD_PROJECT":  "project-a",
			"GOOGLE_CLOUD_LOCATION": "global",
			"VLM_MODEL_ID":          "model-from-env",
		}
		delete(values, missing)
		if _, err := LoadConfig(mapLookup(values)); err == nil {
			t.Fatalf("LoadConfig() with missing %s error = nil", missing)
		}
	}
}

func TestLoadConfigRejectsInvalidDeadlineWithoutEchoingValue(t *testing.T) {
	_, err := LoadConfig(mapLookup(map[string]string{
		"GOOGLE_CLOUD_PROJECT":  "project-a",
		"GOOGLE_CLOUD_LOCATION": "global",
		"VLM_MODEL_ID":          "model-from-env",
		"VLM_DEADLINE":          "private-invalid-value",
	}))
	var configError *ConfigError
	if !errors.As(err, &configError) || configError.Field != "VLM_DEADLINE" {
		t.Fatalf("LoadConfig() error = %#v", err)
	}
	if err.Error() == "private-invalid-value" {
		t.Fatalf("LoadConfig() leaked value: %q", err)
	}
}

func TestValidateRequestAcceptsJSONSchemaAndJPEG(t *testing.T) {
	request := validRequest()
	if err := ValidateRequest(request); err != nil {
		t.Fatalf("ValidateRequest() error = %v", err)
	}
}

func TestValidateRequestRejectsFreeTextFallbackAndInvalidImages(t *testing.T) {
	tests := []GenerateRequest{
		func() GenerateRequest { value := validRequest(); value.Prompt = ""; return value }(),
		func() GenerateRequest { value := validRequest(); value.ResponseMediaType = "text/plain"; return value }(),
		func() GenerateRequest { value := validRequest(); value.SchemaJSON = []byte("[]"); return value }(),
		func() GenerateRequest { value := validRequest(); value.Images[0].Data = nil; return value }(),
		func() GenerateRequest { value := validRequest(); value.Images[0].MediaType = "image/png"; return value }(),
	}
	for _, request := range tests {
		var vertexError *Error
		if err := ValidateRequest(request); !errors.As(err, &vertexError) || vertexError.Reason != FailureInvalidRequest {
			t.Fatalf("ValidateRequest() error = %#v", err)
		}
	}
}

func validRequest() GenerateRequest {
	return GenerateRequest{
		Prompt:            "analyze",
		Images:            []InlineImage{{MediaType: JPEGMediaType, Data: []byte{1}}},
		ResponseMediaType: JSONMediaType,
		SchemaJSON:        []byte(`{"type":"object"}`),
	}
}

func mapLookup(values map[string]string) LookupEnv {
	return func(key string) (string, bool) {
		value, ok := values[key]
		return value, ok
	}
}
