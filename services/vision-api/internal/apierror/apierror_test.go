package apierror

import (
	"encoding/json"
	"net/http/httptest"
	"testing"
)

func TestWriteReturnsOnlyPublicErrorContract(t *testing.T) {
	recorder := httptest.NewRecorder()

	Write(recorder, 500, Internal)

	if recorder.Code != 500 {
		t.Fatalf("status = %d", recorder.Code)
	}
	if recorder.Header().Get("Content-Type") != "application/json; charset=utf-8" {
		t.Fatalf("content type = %q", recorder.Header().Get("Content-Type"))
	}
	var response struct {
		Error Error `json:"error"`
	}
	if err := json.Unmarshal(recorder.Body.Bytes(), &response); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if response.Error != Internal {
		t.Fatalf("error = %#v", response.Error)
	}
}
