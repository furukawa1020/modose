package structured

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

type queuedGenerator struct {
	responses []vertex.GenerateResponse
	errors    []error
	requests  []vertex.GenerateRequest
}

func (g *queuedGenerator) Generate(_ context.Context, request vertex.GenerateRequest) (vertex.GenerateResponse, error) {
	g.requests = append(g.requests, request)
	index := len(g.requests) - 1
	if index < len(g.errors) && g.errors[index] != nil {
		return vertex.GenerateResponse{}, g.errors[index]
	}
	return g.responses[index], nil
}

func TestGenerateAndDecodeReturnsValidInitialResponse(t *testing.T) {
	generator := &queuedGenerator{responses: []vertex.GenerateResponse{{
		Text:       `{"status":"verified"}`,
		ResponseID: "initial",
		ModelID:    "model-a",
		Usage:      vertex.Usage{TotalTokens: 12},
	}}}

	outcome, err := GenerateAndDecode(context.Background(), generator, validVertexRequest(), validStatus)
	if err != nil {
		t.Fatalf("GenerateAndDecode() error = %v", err)
	}
	if outcome.Repaired || len(outcome.Attempts) != 1 || len(generator.requests) != 1 {
		t.Errorf("outcome = %#v, calls = %d", outcome, len(generator.requests))
	}
	if outcome.Attempts[0].ResponseID != "initial" ||
		outcome.Attempts[0].Usage.TotalTokens != 12 {
		t.Errorf("metadata = %#v", outcome.Attempts)
	}
}

func TestGenerateAndDecodeRepairsOneInvalidResponse(t *testing.T) {
	generator := &queuedGenerator{responses: []vertex.GenerateResponse{
		{Text: `{"status":"verified","extra":true}`, ResponseID: "initial", ModelID: "model-a"},
		{Text: `{"status":"verified"}`, ResponseID: "repair", ModelID: "model-a"},
	}}

	outcome, err := GenerateAndDecode(context.Background(), generator, validVertexRequest(), validStatus)
	if err != nil {
		t.Fatalf("GenerateAndDecode() error = %v", err)
	}
	if !outcome.Repaired || len(outcome.Attempts) != 2 || len(generator.requests) != 2 {
		t.Errorf("outcome = %#v, calls = %d", outcome, len(generator.requests))
	}
	if outcome.Attempts[1].ResponseID != "repair" {
		t.Errorf("attempt metadata = %#v", outcome.Attempts)
	}
	if !strings.Contains(generator.requests[1].Prompt, "<untrusted_previous_output>") {
		t.Error("second call was not a repair request")
	}
}

func TestGenerateAndDecodeDoesNotRepairGenerationFailure(t *testing.T) {
	want := errors.New("Vertex unavailable")
	generator := &queuedGenerator{
		responses: []vertex.GenerateResponse{{}},
		errors:    []error{want},
	}

	_, err := GenerateAndDecode(context.Background(), generator, validVertexRequest(), validStatus)
	var terminal *TerminalError
	if !errors.As(err, &terminal) || terminal.Reason != TerminalInitialGeneration {
		t.Fatalf("error = %#v", err)
	}
	if !errors.Is(err, want) || len(generator.requests) != 1 {
		t.Errorf("error = %v, calls = %d", err, len(generator.requests))
	}
}

func TestGenerateAndDecodeStopsAfterRejectedRepair(t *testing.T) {
	generator := &queuedGenerator{responses: []vertex.GenerateResponse{
		{Text: "{", ResponseID: "initial"},
		{Text: "{", ResponseID: "repair"},
	}}

	_, err := GenerateAndDecode(context.Background(), generator, validVertexRequest(), validStatus)
	var terminal *TerminalError
	if !errors.As(err, &terminal) || terminal.Reason != TerminalRepairRejected {
		t.Fatalf("error = %#v", err)
	}
	if len(generator.requests) != 2 || len(terminal.Attempts) != 2 {
		t.Errorf("calls = %d, attempts = %#v", len(generator.requests), terminal.Attempts)
	}
}

func TestGenerateAndDecodeDoesNotSendOversizedRepair(t *testing.T) {
	generator := &queuedGenerator{responses: []vertex.GenerateResponse{{
		Text: strings.Repeat("x", MaxRepairResponseBytes+1),
	}}}

	_, err := GenerateAndDecode(context.Background(), generator, validVertexRequest(), validStatus)
	var terminal *TerminalError
	if !errors.As(err, &terminal) || terminal.Reason != TerminalRepairRequest {
		t.Fatalf("error = %#v", err)
	}
	if len(generator.requests) != 1 {
		t.Errorf("calls = %d, want 1", len(generator.requests))
	}
}

func validStatus(result testResult) error {
	if result.Status != "verified" {
		return errors.New("status rejected")
	}
	return nil
}
