package firestoremetadata

import (
	"context"
	"errors"
	"testing"
)

func TestClientPassesDeletePathToFirestoreBoundary(t *testing.T) {
	var paths []string
	client := newClientWithOperations(
		func(context.Context, string, map[string]any) error { return nil },
		func(_ context.Context, path string) error {
			paths = append(paths, path)
			return nil
		},
	)

	for range 2 {
		if err := client.Delete(
			context.Background(),
			"users/user-1/scenes/scene-1",
		); err != nil {
			t.Fatalf("Delete() error = %v", err)
		}
	}
	if len(paths) != 2 ||
		paths[0] != "users/user-1/scenes/scene-1" ||
		paths[1] != paths[0] {
		t.Fatalf("paths = %#v", paths)
	}
}

func TestClientPreservesFirestoreDeleteCause(t *testing.T) {
	private := errors.New("private Firestore delete detail")
	client := newClientWithOperations(
		nil,
		func(context.Context, string) error { return private },
	)

	err := client.Delete(context.Background(), "users/u/scenes/s")

	if !errors.Is(err, private) {
		t.Fatalf("error = %#v", err)
	}
	if err.Error() == private.Error() {
		t.Fatalf("adapter error leaked bare dependency detail: %v", err)
	}
}

func TestClientRejectsUnavailableDeleteDependency(t *testing.T) {
	if err := (*Client)(nil).Delete(
		context.Background(),
		"users/u/scenes/s",
	); err == nil {
		t.Fatal("nil client accepted")
	}
	if err := newClientWithOperations(nil, nil).Delete(
		context.Background(),
		"users/u/scenes/s",
	); err == nil {
		t.Fatal("nil delete dependency accepted")
	}
}
