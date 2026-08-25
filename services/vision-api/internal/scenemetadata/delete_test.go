package scenemetadata

import (
	"context"
	"errors"
	"testing"
)

type fakeDocumentStore struct {
	paths []string
	err   error
}

func (store *fakeDocumentStore) Set(context.Context, string, map[string]any) error {
	return nil
}

func (store *fakeDocumentStore) Delete(_ context.Context, path string) error {
	store.paths = append(store.paths, path)
	return store.err
}

func TestRepositoryDeleteIsIdempotentAtDocumentBoundary(t *testing.T) {
	store := &fakeDocumentStore{}
	repository := NewRepository(store)

	for range 2 {
		if err := repository.Delete(context.Background(), "user-1", "scene-1"); err != nil {
			t.Fatalf("Delete() error = %v", err)
		}
	}
	if len(store.paths) != 2 {
		t.Fatalf("delete calls = %d", len(store.paths))
	}
	for _, path := range store.paths {
		if path != "users/user-1/scenes/scene-1" {
			t.Fatalf("path = %q", path)
		}
	}
}

func TestRepositoryDeleteRejectsUnsafeOwnerBeforeStorage(t *testing.T) {
	store := &fakeDocumentStore{}
	err := NewRepository(store).Delete(
		context.Background(),
		"user-1/scenes/other-user",
		"scene-1",
	)
	var repositoryError *RepositoryError
	if !errors.As(err, &repositoryError) ||
		repositoryError.Stage != StageValidation {
		t.Fatalf("error = %#v", err)
	}
	if len(store.paths) != 0 {
		t.Fatalf("storage called with %#v", store.paths)
	}
}

func TestRepositoryDeleteClassifiesStorageFailure(t *testing.T) {
	private := errors.New("private delete detail")
	store := &fakeDocumentStore{err: private}
	err := NewRepository(store).Delete(
		context.Background(),
		"user-1",
		"scene-1",
	)
	var repositoryError *RepositoryError
	if !errors.As(err, &repositoryError) ||
		repositoryError.Stage != StageStorage ||
		!errors.Is(err, private) {
		t.Fatalf("error = %#v", err)
	}
}
