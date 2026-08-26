package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestMetadataHandlerRejectsUnsafeContextUID(t *testing.T) {
	service := &fakeMetadataService{}
	request := metadataRequestFor(t, validMetadataJSON())
	request = request.WithContext(WithVerifiedUID(
		request.Context(),
		"user-1/scenes/other-user",
	))
	recorder := httptest.NewRecorder()

	storeMetadataHandler(service).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusUnauthorized || service.calls != 0 {
		t.Fatalf("status = %d, calls = %d", recorder.Code, service.calls)
	}
}
