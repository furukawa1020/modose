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

type DocumentDeleter interface {
	Delete(context.Context, string) error
}

type Stored struct {
	Path          string
	SchemaVersion string
}

type Repository struct {
	writer  DocumentWriter
	deleter DocumentDeleter
}

func NewRepository(writer DocumentWriter) *Repository {
	deleter, _ := writer.(DocumentDeleter)
	return &Repository{writer: writer, deleter: deleter}
}

func NewRepositoryWithDelete(
	writer DocumentWriter,
	deleter DocumentDeleter,
) *Repository {
	return &Repository{writer: writer, deleter: deleter}
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

	path := scenePath(uid, scene.SceneID)
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

func (repository *Repository) Delete(
	ctx context.Context,
	uid string,
	sceneID string,
) error {
	if err := ValidateOwnerID(uid); err != nil {
		return &RepositoryError{Stage: StageValidation, Err: err}
	}
	if err := validateDocumentID("sceneId", sceneID); err != nil {
		return &RepositoryError{Stage: StageValidation, Err: err}
	}
	if repository == nil || repository.deleter == nil {
		return &RepositoryError{
			Stage: StageStorage,
			Err:   fmt.Errorf("document deleter is unavailable"),
		}
	}
	if err := repository.deleter.Delete(ctx, scenePath(uid, sceneID)); err != nil {
		return &RepositoryError{Stage: StageStorage, Err: err}
	}
	return nil
}

func scenePath(uid, sceneID string) string {
	return "users/" + uid + "/scenes/" + sceneID
}
