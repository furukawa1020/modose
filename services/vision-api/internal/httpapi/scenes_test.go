package httpapi

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/furukawa1020/modose/services/vision-api/internal/metadataapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/scenemetadata"
)

type fakeMetadataService struct {
	storeUID   string
	stored     scenemetadata.Scene
	deleteUID  string
	deleteIDs  []string
	err        error
	calls      int
}

func (service *fakeMetadataService) Store(
	_ context.Context,
	uid string,
	scene scenemetadata.Scene,
) (scenemetadata.Stored, error) {
	service.calls++
	service.storeUID = uid
	service.stored = scene
	return scenemetadata.Stored{SchemaVersion: scene.SchemaVersion}, service.err
}

func (service *fakeMetadataService) Delete(
	_ context.Context,
	uid string,
	sceneID string,
) error {
	service.calls++
	service.deleteUID = uid
	service.deleteIDs = append(service.deleteIDs, sceneID)
	return service.err
}

func TestStoreMetadataUsesOnlyVerifiedContextUID(t *testing.T) {
	service := &fakeMetadataService{}
	request := metadataRequestFor(t, validMetadataJSON())
	request = request.WithContext(WithVerifiedUID(request.Context(), "verified-user"))
	recorder := httptest.NewRecorder()

	storeMetadataHandler(service).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK ||
		service.storeUID != "verified-user" ||
		service.stored.SceneID != "scene-1" {
		t.Fatalf("status = %d, uid = %q, scene = %#v", recorder.Code, service.storeUID, service.stored)
	}
	if !strings.Contains(recorder.Body.String(), `"version":1`) {
		t.Fatalf("body = %s", recorder.Body.String())
	}
}

func TestStoreMetadataRejectsMissingPrincipalAndUnknownField(t *testing.T) {
	t.Run("principalなし", func(t *testing.T) {
		service := &fakeMetadataService{}
		recorder := httptest.NewRecorder()
		storeMetadataHandler(service).ServeHTTP(
			recorder,
			metadataRequestFor(t, validMetadataJSON()),
		)
		if recorder.Code != http.StatusUnauthorized || service.calls != 0 {
			t.Fatalf("status = %d, calls = %d", recorder.Code, service.calls)
		}
	})

	t.Run("unknown field", func(t *testing.T) {
		service := &fakeMetadataService{}
		body := strings.TrimSuffix(validMetadataJSON(), "}") + `,"uid":"attacker"}`
		request := metadataRequestFor(t, body)
		request = request.WithContext(WithVerifiedUID(request.Context(), "verified-user"))
		recorder := httptest.NewRecorder()
		storeMetadataHandler(service).ServeHTTP(recorder, request)
		if recorder.Code != http.StatusBadRequest || service.calls != 0 {
			t.Fatalf("status = %d, calls = %d", recorder.Code, service.calls)
		}
	})
}

func TestDeleteMetadataIsIdempotentForVerifiedOwner(t *testing.T) {
	service := &fakeMetadataService{}
	for range 2 {
		request := httptest.NewRequest(http.MethodDelete, "/v1/scenes/scene-1", nil)
		setSceneHeaders(request)
		request = request.WithContext(WithVerifiedUID(request.Context(), "verified-user"))
		recorder := httptest.NewRecorder()

		deleteMetadataHandler(service).ServeHTTP(recorder, request)

		if recorder.Code != http.StatusNoContent {
			t.Fatalf("status = %d", recorder.Code)
		}
	}
	if service.deleteUID != "verified-user" ||
		len(service.deleteIDs) != 2 ||
		service.deleteIDs[0] != "scene-1" ||
		service.deleteIDs[1] != "scene-1" {
		t.Fatalf("uid = %q, ids = %#v", service.deleteUID, service.deleteIDs)
	}
}

func TestMetadataHandlerDoesNotLeakStorageFailure(t *testing.T) {
	service := &fakeMetadataService{err: &metadataapi.Error{
		Stage: metadataapi.StageStorage,
		Err:   errors.New("private Firestore detail"),
	}}
	request := metadataRequestFor(t, validMetadataJSON())
	request = request.WithContext(WithVerifiedUID(request.Context(), "verified-user"))
	recorder := httptest.NewRecorder()

	storeMetadataHandler(service).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d", recorder.Code)
	}
	if strings.Contains(recorder.Body.String(), "private Firestore detail") {
		t.Fatal("storage detail leaked")
	}
}

func metadataRequestFor(t *testing.T, body string) *http.Request {
	t.Helper()
	request := httptest.NewRequest(http.MethodPost, "/v1/scenes/metadata", strings.NewReader(body))
	request.Header.Set("Content-Type", "application/json")
	setSceneHeaders(request)
	return request
}

func setSceneHeaders(request *http.Request) {
	request.Header.Set("X-Schema-Version", "1.0")
	request.Header.Set("X-Client-Version", "test")
	request.Header.Set("Idempotency-Key", "metadata-test")
}

func validMetadataJSON() string {
	return `{
		"sceneId":"scene-1",
		"createdAt":"2026-08-26T01:00:00Z",
		"completedAt":"2026-08-26T01:01:00Z",
		"objectCount":3,
		"result":"verified",
		"baselineLatencyMs":1200,
		"compareLatencyMs":900,
		"verifyLatencyMs":1100,
		"modelId":"gemini-test",
		"promptVersion":"verify-v1",
		"retryCount":1,
		"appVersion":"1.0.0",
		"schemaVersion":"1.0"
	}`
}
