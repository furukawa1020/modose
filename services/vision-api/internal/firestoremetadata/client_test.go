package firestoremetadata

import (
	"context"
	"errors"
	"testing"
)

func TestClientPassesPathAndDocumentToFirestoreBoundary(t *testing.T) {
	var receivedPath string
	var receivedDocument map[string]any
	client := newClientWithSet(func(
		_ context.Context,
		path string,
		document map[string]any,
	) error {
		receivedPath = path
		receivedDocument = document
		return nil
	})
	document := map[string]any{
		"schemaVersion": "1.0",
		"objectCount":   3,
	}

	if err := client.Set(
		context.Background(),
		"users/user-1/scenes/scene-1",
		document,
	); err != nil {
		t.Fatalf("Set() error = %v", err)
	}
	if receivedPath != "users/user-1/scenes/scene-1" {
		t.Fatalf("path = %q", receivedPath)
	}
	if receivedDocument["schemaVersion"] != "1.0" ||
		receivedDocument["objectCount"] != 3 {
		t.Fatalf("document = %#v", receivedDocument)
	}
}

func TestClientPreservesFirestoreCause(t *testing.T) {
	private := errors.New("private Firestore detail")
	client := newClientWithSet(func(
		context.Context,
		string,
		map[string]any,
	) error {
		return private
	})

	err := client.Set(context.Background(), "users/u/scenes/s", map[string]any{})

	if !errors.Is(err, private) {
		t.Fatalf("error = %#v", err)
	}
	if err.Error() == private.Error() {
		t.Fatalf("adapter error leaked bare dependency detail: %v", err)
	}
}

func TestClientRejectsUnavailableDependency(t *testing.T) {
	var client *Client
	if err := client.Set(context.Background(), "users/u/scenes/s", nil); err == nil {
		t.Fatal("nil client accepted")
	}
	if err := newClientWithSet(nil).Set(
		context.Background(),
		"users/u/scenes/s",
		nil,
	); err == nil {
		t.Fatal("nil setter accepted")
	}
}

func TestOpenRejectsEmptyProjectWithoutCredentialsLookup(t *testing.T) {
	if _, err := Open(context.Background(), " "); err == nil {
		t.Fatal("empty project ID accepted")
	}
}

func TestCloseIsSafeWithoutOpenedFirestoreClient(t *testing.T) {
	if err := (*Client)(nil).Close(); err != nil {
		t.Fatalf("nil Close() error = %v", err)
	}
	if err := newClientWithSet(func(context.Context, string, map[string]any) error {
		return nil
	}).Close(); err != nil {
		t.Fatalf("test client Close() error = %v", err)
	}
}
