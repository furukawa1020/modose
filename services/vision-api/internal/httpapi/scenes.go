package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strings"
	"time"

	"github.com/furukawa1020/modose/services/vision-api/internal/apierror"
	"github.com/furukawa1020/modose/services/vision-api/internal/metadataapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/scenemetadata"
)

const maxMetadataRequestBytes int64 = 64 * 1024

var (
	unauthorizedMetadata = apierror.Error{Code: "unauthorized", Message: "Authentication is required."}
	metadataUnavailable  = apierror.Error{Code: "metadata_unavailable", Message: "The metadata service is unavailable."}
)

type principalContextKey struct{}

func WithVerifiedUID(ctx context.Context, uid string) context.Context {
	return context.WithValue(ctx, principalContextKey{}, uid)
}

type MetadataService interface {
	Store(context.Context, string, scenemetadata.Scene) (scenemetadata.Stored, error)
	Delete(context.Context, string, string) error
}

type metadataRequest struct {
	SceneID           string               `json:"sceneId"`
	CreatedAt         time.Time            `json:"createdAt"`
	CompletedAt       time.Time            `json:"completedAt"`
	ObjectCount       int                  `json:"objectCount"`
	Result            scenemetadata.Result `json:"result"`
	BaselineLatencyMs int64                `json:"baselineLatencyMs"`
	CompareLatencyMs  int64                `json:"compareLatencyMs"`
	VerifyLatencyMs   int64                `json:"verifyLatencyMs"`
	ModelID           string               `json:"modelId"`
	PromptVersion     string               `json:"promptVersion"`
	RetryCount        int                  `json:"retryCount"`
	AppVersion        string               `json:"appVersion"`
	SchemaVersion     string               `json:"schemaVersion"`
}

type storedMetadataResponse struct {
	SceneID string `json:"sceneId"`
	Version int    `json:"version"`
}

func storeMetadataHandler(service MetadataService) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		uid, ok := verifiedUID(request.Context())
		if !ok {
			apierror.Write(writer, http.StatusUnauthorized, unauthorizedMetadata)
			return
		}
		if service == nil {
			apierror.Write(writer, http.StatusServiceUnavailable, metadataUnavailable)
			return
		}
		if !validSceneHeaders(request, true) {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}

		request.Body = http.MaxBytesReader(writer, request.Body, maxMetadataRequestBytes)
		var input metadataRequest
		decoder := json.NewDecoder(request.Body)
		decoder.DisallowUnknownFields()
		if err := decoder.Decode(&input); err != nil {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}
		var trailing any
		if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}

		_, err := service.Store(request.Context(), uid, input.scene())
		if err != nil {
			writeMetadataServiceError(writer, err)
			return
		}
		writer.Header().Set("Content-Type", "application/json; charset=utf-8")
		if err := json.NewEncoder(writer).Encode(storedMetadataResponse{
			SceneID: input.SceneID,
			Version: 1,
		}); err != nil {
			return
		}
	}
}

func deleteMetadataHandler(service MetadataService) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		uid, ok := verifiedUID(request.Context())
		if !ok {
			apierror.Write(writer, http.StatusUnauthorized, unauthorizedMetadata)
			return
		}
		if service == nil {
			apierror.Write(writer, http.StatusServiceUnavailable, metadataUnavailable)
			return
		}
		if !validSceneHeaders(request, false) {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}
		sceneID := strings.TrimPrefix(request.URL.Path, "/v1/scenes/")
		if sceneID == "" || strings.Contains(sceneID, "/") {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}
		if err := service.Delete(request.Context(), uid, sceneID); err != nil {
			writeMetadataServiceError(writer, err)
			return
		}
		writer.WriteHeader(http.StatusNoContent)
	}
}

func verifiedUID(ctx context.Context) (string, bool) {
	uid, ok := ctx.Value(principalContextKey{}).(string)
	if !ok || scenemetadata.ValidateOwnerID(uid) != nil {
		return "", false
	}
	return uid, true
}

func validSceneHeaders(request *http.Request, requireJSON bool) bool {
	if request.Header.Get("X-Schema-Version") != "1.0" ||
		strings.TrimSpace(request.Header.Get("X-Client-Version")) == "" ||
		strings.TrimSpace(request.Header.Get("Idempotency-Key")) == "" {
		return false
	}
	if !requireJSON {
		return true
	}
	mediaType, _, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
	return err == nil && mediaType == "application/json"
}

func writeMetadataServiceError(writer http.ResponseWriter, err error) {
	var serviceError *metadataapi.Error
	if errors.As(err, &serviceError) && serviceError.Stage == metadataapi.StageRequest {
		apierror.Write(writer, http.StatusBadRequest, invalidRequest)
		return
	}
	apierror.Write(writer, http.StatusServiceUnavailable, metadataUnavailable)
}

func (input metadataRequest) scene() scenemetadata.Scene {
	return scenemetadata.Scene{
		SceneID: input.SceneID, CreatedAt: input.CreatedAt, CompletedAt: input.CompletedAt,
		ObjectCount: input.ObjectCount, Result: input.Result,
		BaselineLatencyMs: input.BaselineLatencyMs, CompareLatencyMs: input.CompareLatencyMs,
		VerifyLatencyMs: input.VerifyLatencyMs, ModelID: input.ModelID,
		PromptVersion: input.PromptVersion, RetryCount: input.RetryCount,
		AppVersion: input.AppVersion, SchemaVersion: input.SchemaVersion,
	}
}
