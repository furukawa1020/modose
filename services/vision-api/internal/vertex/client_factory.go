package vertex

import (
	"context"
	"strings"
	"time"

	"google.golang.org/genai"
)

type genAIClientCreator func(context.Context, *genai.ClientConfig) (*genai.Client, error)

// Client owns the Google Gen AI SDK client and the validated Vertex settings.
// Authentication is intentionally delegated to Application Default Credentials.
type Client struct {
	sdk    *genai.Client
	config Config
}

// NewClient creates a Google Gen AI client configured for the Vertex AI backend.
func NewClient(ctx context.Context, config Config) (*Client, error) {
	return newClient(ctx, config, genai.NewClient)
}

func newClient(ctx context.Context, config Config, create genAIClientCreator) (*Client, error) {
	if err := validateClientConfig(config); err != nil {
		return nil, err
	}

	sdk, err := create(ctx, &genai.ClientConfig{
		Project:  config.Project,
		Location: config.Location,
		Backend:  genai.BackendVertexAI,
		HTTPOptions: genai.HTTPOptions{
			APIVersion: "v1",
		},
	})
	if err != nil {
		return nil, &Error{Reason: FailureClientCreation, Err: err}
	}

	return &Client{sdk: sdk, config: config}, nil
}

func validateClientConfig(config Config) error {
	if strings.TrimSpace(config.Project) == "" {
		return &ConfigError{Field: "GOOGLE_CLOUD_PROJECT", Reason: "required"}
	}
	if strings.TrimSpace(config.Location) == "" {
		return &ConfigError{Field: "GOOGLE_CLOUD_LOCATION", Reason: "required"}
	}
	if strings.TrimSpace(config.ModelID) == "" {
		return &ConfigError{Field: "VLM_MODEL_ID", Reason: "required"}
	}
	if config.Deadline <= 0 || config.Deadline > 60*time.Second {
		return &ConfigError{Field: "VLM_DEADLINE", Reason: "out_of_range"}
	}
	return nil
}
