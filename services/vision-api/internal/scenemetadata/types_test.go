package scenemetadata

import (
	"errors"
	"testing"
	"time"
)

func TestValidateAcceptsAllowlistedMetadata(t *testing.T) {
	if err := Validate(validScene()); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
	if err := ValidateOwnerID("anonymous-user-1"); err != nil {
		t.Fatalf("ValidateOwnerID() error = %v", err)
	}
}

func TestValidateRejectsUnsafeDocumentIDs(t *testing.T) {
	tests := []struct {
		name string
		uid  string
	}{
		{"空", ""},
		{"slash", "user/scene"},
		{"backslash", `user\scene`},
		{"相対path", ".."},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			var validationError *ValidationError
			if err := ValidateOwnerID(test.uid); !errors.As(err, &validationError) {
				t.Fatalf("error = %#v", err)
			}
		})
	}

	scene := validScene()
	scene.SceneID = "scene/child"
	if err := Validate(scene); err == nil {
		t.Fatal("unsafe sceneId accepted")
	}
}

func TestValidateRejectsInvalidSceneValues(t *testing.T) {
	tests := []struct {
		name   string
		mutate func(*Scene)
		field  string
	}{
		{"createdAtなし", func(scene *Scene) { scene.CreatedAt = time.Time{} }, "createdAt"},
		{"完了時刻逆転", func(scene *Scene) { scene.CompletedAt = scene.CreatedAt.Add(-time.Second) }, "completedAt"},
		{"物体数超過", func(scene *Scene) { scene.ObjectCount = 6 }, "objectCount"},
		{"未知result", func(scene *Scene) { scene.Result = Result("success") }, "result"},
		{"負latency", func(scene *Scene) { scene.VerifyLatencyMs = -1 }, "verifyLatencyMs"},
		{"retry超過", func(scene *Scene) { scene.RetryCount = MaxRetries + 1 }, "retryCount"},
		{"modelIdなし", func(scene *Scene) { scene.ModelID = " " }, "modelId"},
		{"promptVersionなし", func(scene *Scene) { scene.PromptVersion = "" }, "promptVersion"},
		{"appVersionなし", func(scene *Scene) { scene.AppVersion = "" }, "appVersion"},
		{"schemaVersionなし", func(scene *Scene) { scene.SchemaVersion = "" }, "schemaVersion"},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			scene := validScene()
			test.mutate(&scene)
			var validationError *ValidationError
			if err := Validate(scene); !errors.As(err, &validationError) {
				t.Fatalf("error = %#v", err)
			}
			if validationError.Field != test.field {
				t.Fatalf("field = %q, want %q", validationError.Field, test.field)
			}
		})
	}
}

func TestResultAllowlist(t *testing.T) {
	for _, result := range []Result{
		ResultVerified,
		ResultNeedsCorrection,
		ResultUncertain,
	} {
		scene := validScene()
		scene.Result = result
		if err := Validate(scene); err != nil {
			t.Fatalf("result %q rejected: %v", result, err)
		}
	}
}

func validScene() Scene {
	created := time.Date(2026, time.August, 26, 1, 0, 0, 0, time.UTC)
	return Scene{
		SceneID:           "scene-1",
		CreatedAt:         created,
		CompletedAt:       created.Add(time.Minute),
		ObjectCount:       3,
		Result:            ResultVerified,
		BaselineLatencyMs: 1200,
		CompareLatencyMs:  900,
		VerifyLatencyMs:   1100,
		ModelID:           "gemini-test",
		PromptVersion:     "verify-v1",
		RetryCount:        1,
		AppVersion:        "1.0.0",
		SchemaVersion:     "1.0",
	}
}
