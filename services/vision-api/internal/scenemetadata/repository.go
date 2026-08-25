package scenemetadata

import (
	"context"
	"fmt"
)

type FailureStage string

const (
	StageValidation FailureStage = "validation"
	StageStorage    FailureStage = "storage"
)

type RepositoryError struct {
	Stage FailureStage
	Err   error
}

func (e *RepositoryError) Error() string {
	return fmt.Sprintf("scene metadata repository failed: %s", e.Stage)
}

func (e *RepositoryError) Unwrap() error {
	return e.Err
}

type DocumentWriter interface {
	Set(context.Context, string, map[string]any) error
}

type Stored struct {
	Path          string
	SchemaVersion string
}

type Repository struct {
	writer DocumentWriter
}

func NewRepository(writer DocumentWriter) *Repository {
	return &Repository{writer: writer}
}

func (repository *Repository) Save(
	ctx context.Context,
	uid string,
	scene Scene,
) (Stored, error) {
	if err := ValidateOwnerID(uid); err != nil {
		return Stored{}, &RepositoryError{Stage: StageValidation, Err: err}
	}
	if err := Validate(scene); err != nil {
		return Stored{}, &RepositoryError{Stage: StageValidation, Err: err}
	}
	if repository == nil || repository.writer == nil {
		return Stored{}, &RepositoryError{
			Stage: StageStorage,
			Err:   fmt.Errorf("document writer is unavailable"),
		}
	}

	path := "users/" + uid + "/scenes/" + scene.SceneID
	document := map[string]any{
		"createdAt":         scene.CreatedAt,
		"completedAt":       scene.CompletedAt,
		"objectCount":       scene.ObjectCount,
		"result":            string(scene.Result),
		"baselineLatencyMs": scene.BaselineLatencyMs,
		"compareLatencyMs":  scene.CompareLatencyMs,
		"verifyLatencyMs":   scene.VerifyLatencyMs,
		"modelId":           scene.ModelID,
		"promptVersion":     scene.PromptVersion,
		"retryCount":        scene.RetryCount,
		"appVersion":        scene.AppVersion,
		"schemaVersion":     scene.SchemaVersion,
	}
	if err := repository.writer.Set(ctx, path, document); err != nil {
		return Stored{}, &RepositoryError{Stage: StageStorage, Err: err}
	}
	return Stored{Path: path, SchemaVersion: scene.SchemaVersion}, nil
}
