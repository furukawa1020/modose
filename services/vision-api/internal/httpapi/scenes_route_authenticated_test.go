package httpapi

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAuthenticatedMetadataRouteReachesService(t *testing.T) {
	service := &fakeMetadataService{}
	router := NewVisionRouter(nil, VisionAnalyzers{Metadata: service})
	request := metadataRequestFor(t, validMetadataJSON())
	request = request.WithContext(WithVerifiedUID(request.Context(), "verified-user"))
	recorder := httptest.NewRecorder()

	router.ServeHTTP(recorder, request)

	if recorder.Code != http.StatusOK ||
		service.calls != 1 ||
		service.storeUID != "verified-user" {
		t.Fatalf(
			"status = %d, calls = %d, uid = %q",
			recorder.Code,
			service.calls,
			service.storeUID,
		)
	}
}
