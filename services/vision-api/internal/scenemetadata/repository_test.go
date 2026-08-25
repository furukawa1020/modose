package scenemetadata

import (
	"context"
	"errors"
	"testing"
)

type fakeDocumentWriter struct {
	path     string
	document map[string]any
	err      error
	calls    int
}

func (writer *fakeDocumentWriter) Set(
	_ context.Context,
	path string,
	document map[string]any,
) error {
	writer.calls++
	writer.path = path
	writer.document = document
	return writer.err
}

func TestRepositoryWritesOnlyAllowlistedDocument(t *testing.T) {
	writer := &fakeDocumentWriter{}
	scene := validScene()

	stored, err := NewRepository(writer).Save(context.Background(), "user-1", scene)
	if err != nil {
		t.Fatalf("Save() error = %v", err)
	}
	if stored.Path != "users/user-1/scenes/scene-1" ||
		stored.SchemaVersion != "1.0" ||
		writer.path != stored.Path {
		t.Fatalf("stored = %#v, path = %q", stored, writer.path)
	}

	allowed := []string{
		"createdAt",
		"completedAt",
		"objectCount",
		"result",
		"baselineLatencyMs",
		"compareLatencyMs",
		"verifyLatencyMs",
		"modelId",
		"promptVersion",
		"retryCount",
		"appVersion",
		"schemaVersion",
	}
	if len(writer.document) != len(allowed) {
		t.Fatalf("document keys = %d, want %d: %#v", len(writer.document), len(allowed), writer.document)
	}
	for _, key := range allowed {
		if _, exists := writer.document[key]; !exists {
			t.Errorf("allowlisted key %q is missing", key)
		}
	}
	for _, forbidden := range []string{
		"uid",
		"sceneId",
		"label",
		"image",
		"imageUri",
		"visualSignature",
		"embedding",
		"prompt",
		"thumbnail",
	} {
		if _, exists := writer.document[forbidden]; exists {
			t.Errorf("forbidden key %q was written", forbidden)
		}
	}
}

func TestRepositoryRejectsInputBeforeWriting(t *testing.T) {
	tests := []struct {
		name  string
		uid   string
		scene Scene
	}{
		{"unsafe uid", "user/child", validScene()},
		{"invalid scene", "user-1", Scene{}},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			writer := &fakeDocumentWriter{}
			_, err := NewRepository(writer).Save(
				context.Background(),
				test.uid,
				test.scene,
			)
			var repositoryError *RepositoryError
			if !errors.As(err, &repositoryError) ||
				repositoryError.Stage != StageValidation {
				t.Fatalf("error = %#v", err)
			}
			if writer.calls != 0 {
				t.Fatalf("writer calls = %d", writer.calls)
			}
		})
	}
}

func TestRepositoryClassifiesStorageFailureWithoutLeak(t *testing.T) {
	private := errors.New("private Firestore detail")
	writer := &fakeDocumentWriter{err: private}

	_, err := NewRepository(writer).Save(
		context.Background(),
		"user-1",
		validScene(),
	)

	var repositoryError *RepositoryError
	if !errors.As(err, &repositoryError) ||
		repositoryError.Stage != StageStorage {
		t.Fatalf("error = %#v", err)
	}
	if !errors.Is(err, private) {
		t.Fatal("storage cause is not available to internal callers")
	}
	if err.Error() == private.Error() {
		t.Fatalf("repository error leaked dependency detail: %v", err)
	}
}

func TestRepositoryRejectsMissingWriter(t *testing.T) {
	_, err := NewRepository(nil).Save(
		context.Background(),
		"user-1",
		validScene(),
	)
	var repositoryError *RepositoryError
	if !errors.As(err, &repositoryError) ||
		repositoryError.Stage != StageStorage {
		t.Fatalf("error = %#v", err)
	}
}
