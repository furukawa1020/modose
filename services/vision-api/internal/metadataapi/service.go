package metadataapi

import (
	"context"
	"errors"
	"fmt"

	"github.com/furukawa1020/modose/services/vision-api/internal/scenemetadata"
)

type FailureStage string

const (
	StageRequest FailureStage = "request"
	StageStorage FailureStage = "storage"
)

type Error struct {
	Stage FailureStage
	Err   error
}

func (e *Error) Error() string {
	return fmt.Sprintf("scene metadata operation failed: %s", e.Stage)
}

func (e *Error) Unwrap() error {
	return e.Err
}

type Repository interface {
	Save(context.Context, string, scenemetadata.Scene) (scenemetadata.Stored, error)
	Delete(context.Context, string, string) error
}

type Service struct {
	repository Repository
}

func NewService(repository Repository) *Service {
	return &Service{repository: repository}
}

func (service *Service) Store(
	ctx context.Context,
	uid string,
	scene scenemetadata.Scene,
) (scenemetadata.Stored, error) {
	if service == nil || service.repository == nil {
		return scenemetadata.Stored{}, &Error{
			Stage: StageStorage,
			Err:   fmt.Errorf("metadata repository is unavailable"),
		}
	}
	stored, err := service.repository.Save(ctx, uid, scene)
	if err != nil {
		return scenemetadata.Stored{}, classify(err)
	}
	return stored, nil
}

func (service *Service) Delete(
	ctx context.Context,
	uid string,
	sceneID string,
) error {
	if service == nil || service.repository == nil {
		return &Error{
			Stage: StageStorage,
			Err:   fmt.Errorf("metadata repository is unavailable"),
		}
	}
	if err := service.repository.Delete(ctx, uid, sceneID); err != nil {
		return classify(err)
	}
	return nil
}

func classify(err error) error {
	var repositoryError *scenemetadata.RepositoryError
	if errors.As(err, &repositoryError) &&
		repositoryError.Stage == scenemetadata.StageValidation {
		return &Error{Stage: StageRequest, Err: err}
	}
	return &Error{Stage: StageStorage, Err: err}
}
