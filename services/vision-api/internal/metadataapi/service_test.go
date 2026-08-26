package metadataapi

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/furukawa1020/modose/services/vision-api/internal/scenemetadata"
)

type fakeRepository struct {
	saveUID    string
	deleteUID  string
	deleteIDs  []string
	stored     scenemetadata.Stored
	saveErr    error
	deleteErr  error
}

func (repository *fakeRepository) Save(
	_ context.Context,
	uid string,
	_ scenemetadata.Scene,
) (scenemetadata.Stored, error) {
	repository.saveUID = uid
	return repository.stored, repository.saveErr
}

func (repository *fakeRepository) Delete(
	_ context.Context,
	uid string,
	sceneID string,
) error {
	repository.deleteUID = uid
	repository.deleteIDs = append(repository.deleteIDs, sceneID)
	return repository.deleteErr
}

func TestServiceUsesCallerUIDForStoreAndDelete(t *testing.T) {
	repository := &fakeRepository{stored: scenemetadata.Stored{
		Path:          "users/user-1/scenes/scene-1",
		SchemaVersion: "1.0",
	}}
	service := NewService(repository)

	stored, err := service.Store(context.Background(), "user-1", validScene())
	if err != nil {
		t.Fatalf("Store() error = %v", err)
	}
	if repository.saveUID != "user-1" ||
		stored.SchemaVersion != "1.0" {
		t.Fatalf("uid = %q, stored = %#v", repository.saveUID, stored)
	}

	for range 2 {
		if err := service.Delete(context.Background(), "user-1", "scene-1"); err != nil {
			t.Fatalf("Delete() error = %v", err)
		}
	}
	if repository.deleteUID != "user-1" ||
		len(repository.deleteIDs) != 2 ||
		repository.deleteIDs[0] != "scene-1" ||
		repository.deleteIDs[1] != "scene-1" {
		t.Fatalf("uid = %q, ids = %#v", repository.deleteUID, repository.deleteIDs)
	}
}

func TestServiceClassifiesRepositoryFailures(t *testing.T) {
	private := errors.New("private Firestore detail")
	tests := []struct {
		name       string
		causeStage scenemetadata.FailureStage
		wantStage  FailureStage
	}{
		{"validation", scenemetadata.StageValidation, StageRequest},
		{"storage", scenemetadata.StageStorage, StageStorage},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			repository := &fakeRepository{saveErr: &scenemetadata.RepositoryError{
				Stage: test.causeStage,
				Err:   private,
			}}
			_, err := NewService(repository).Store(
				context.Background(),
				"user-1",
				validScene(),
			)
			var serviceError *Error
			if !errors.As(err, &serviceError) ||
				serviceError.Stage != test.wantStage ||
				!errors.Is(err, private) {
				t.Fatalf("error = %#v", err)
			}
			if err.Error() == private.Error() {
				t.Fatalf("service error leaked dependency detail: %v", err)
			}
		})
	}
}

func TestServiceRejectsMissingRepository(t *testing.T) {
	if _, err := NewService(nil).Store(
		context.Background(),
		"user-1",
		validScene(),
	); err == nil {
		t.Fatal("missing repository accepted")
	}
	if err := NewService(nil).Delete(
		context.Background(),
		"user-1",
		"scene-1",
	); err == nil {
		t.Fatal("missing repository accepted")
	}
}

func validScene() scenemetadata.Scene {
	created := time.Date(2026, time.August, 26, 1, 0, 0, 0, time.UTC)
	return scenemetadata.Scene{
		SceneID:           "scene-1",
		CreatedAt:         created,
		CompletedAt:       created.Add(time.Minute),
		ObjectCount:       3,
		Result:            scenemetadata.ResultVerified,
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
