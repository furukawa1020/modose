package vertex

import (
	"context"
	"errors"
	"testing"
	"time"

	"google.golang.org/genai"
)

func TestNewClientUsesVertexAIAndStableAPI(t *testing.T) {
	config := Config{
		Project:  "modose-test",
		Location: "asia-northeast1",
		ModelID:  "test-model",
		Deadline: 12 * time.Second,
	}

	var received *genai.ClientConfig
	client, err := newClient(context.Background(), config, func(_ context.Context, sdkConfig *genai.ClientConfig) (*genai.Client, error) {
		received = sdkConfig
		return new(genai.Client), nil
	})
	if err != nil {
		t.Fatalf("newClient() error = %v", err)
	}
	if client == nil || client.sdk == nil {
		t.Fatal("newClient() returned no SDK client")
	}
	if received.Project != config.Project {
		t.Errorf("Project = %q, want %q", received.Project, config.Project)
	}
	if received.Location != config.Location {
		t.Errorf("Location = %q, want %q", received.Location, config.Location)
	}
	if received.Backend != genai.BackendVertexAI {
		t.Errorf("Backend = %v, want BackendVertexAI", received.Backend)
	}
	if received.HTTPOptions.APIVersion != "v1" {
		t.Errorf("APIVersion = %q, want v1", received.HTTPOptions.APIVersion)
	}
}

func TestNewClientRejectsInvalidConfigBeforeSDKCreation(t *testing.T) {
	called := false
	_, err := newClient(context.Background(), Config{}, func(context.Context, *genai.ClientConfig) (*genai.Client, error) {
		called = true
		return new(genai.Client), nil
	})
	if err == nil {
		t.Fatal("newClient() error = nil")
	}
	if called {
		t.Fatal("SDK client creator was called for invalid config")
	}
}

func TestNewClientReturnsTypedCreationError(t *testing.T) {
	want := errors.New("ADC is unavailable")
	config := Config{
		Project:  "modose-test",
		Location: "asia-northeast1",
		ModelID:  "test-model",
		Deadline: 12 * time.Second,
	}

	_, err := newClient(context.Background(), config, func(context.Context, *genai.ClientConfig) (*genai.Client, error) {
		return nil, want
	})

	var vertexError *Error
	if !errors.As(err, &vertexError) {
		t.Fatalf("error type = %T, want *Error", err)
	}
	if vertexError.Reason != FailureClientCreation {
		t.Errorf("Reason = %q, want %q", vertexError.Reason, FailureClientCreation)
	}
	if !errors.Is(err, want) {
		t.Errorf("error does not wrap creation failure: %v", err)
	}
}
