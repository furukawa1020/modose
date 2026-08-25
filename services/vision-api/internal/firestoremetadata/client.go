package firestoremetadata

import (
	"context"
	"fmt"
	"strings"

	"cloud.google.com/go/firestore"

	"github.com/furukawa1020/modose/services/vision-api/internal/scenemetadata"
)

type setDocumentFunc func(context.Context, string, map[string]any) error
type deleteDocumentFunc func(context.Context, string) error

type Client struct {
	firestore *firestore.Client
	set       setDocumentFunc
	delete    deleteDocumentFunc
}

var (
	_ scenemetadata.DocumentWriter  = (*Client)(nil)
	_ scenemetadata.DocumentDeleter = (*Client)(nil)
)

func Open(ctx context.Context, projectID string) (*Client, error) {
	if strings.TrimSpace(projectID) == "" {
		return nil, fmt.Errorf("Firestore project ID is required")
	}
	client, err := firestore.NewClient(ctx, projectID)
	if err != nil {
		return nil, fmt.Errorf("initialize Firestore client: %w", err)
	}
	return &Client{
		firestore: client,
		set: func(ctx context.Context, path string, document map[string]any) error {
			_, err := client.Doc(path).Set(ctx, document)
			return err
		},
		delete: func(ctx context.Context, path string) error {
			_, err := client.Doc(path).Delete(ctx)
			return err
		},
	}, nil
}

func (client *Client) Set(
	ctx context.Context,
	path string,
	document map[string]any,
) error {
	if client == nil || client.set == nil {
		return fmt.Errorf("Firestore client is unavailable")
	}
	if err := client.set(ctx, path, document); err != nil {
		return fmt.Errorf("write Firestore metadata: %w", err)
	}
	return nil
}

func (client *Client) Delete(ctx context.Context, path string) error {
	if client == nil || client.delete == nil {
		return fmt.Errorf("Firestore client is unavailable")
	}
	if err := client.delete(ctx, path); err != nil {
		return fmt.Errorf("delete Firestore metadata: %w", err)
	}
	return nil
}

func (client *Client) Close() error {
	if client == nil || client.firestore == nil {
		return nil
	}
	if err := client.firestore.Close(); err != nil {
		return fmt.Errorf("close Firestore client: %w", err)
	}
	return nil
}

func newClientWithSet(set setDocumentFunc) *Client {
	return &Client{set: set}
}

func newClientWithOperations(
	set setDocumentFunc,
	delete deleteDocumentFunc,
) *Client {
	return &Client{set: set, delete: delete}
}
