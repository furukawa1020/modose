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
	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/baselineapi"
)

const (
	MaxVisionRequestBytes int64 = 4_500_000
	MaxImageBytes         int64 = 2_000_000
)

var (
	invalidRequest = apierror.Error{Code: "invalid_request", Message: "The request is invalid."}
	unsupportedMedia = apierror.Error{Code: "unsupported_media_type", Message: "The media type is not supported."}
	payloadTooLarge = apierror.Error{Code: "payload_too_large", Message: "The request is too large."}
	analysisRejected = apierror.Error{Code: "analysis_rejected", Message: "The scene could not be analyzed."}
	upstreamFailure = apierror.Error{Code: "upstream_failure", Message: "The analysis service is unavailable."}
)

type BaselineAnalyzer interface {
	Analyze(context.Context, []byte) (baselineapi.Output, error)
}

type captureMetadata struct {
	SceneID    string    `json:"sceneId"`
	CapturedAt time.Time `json:"capturedAt"`
}

type baselineResponse struct {
	SchemaVersion      string                       `json:"schemaVersion"`
	Status             string                       `json:"status"`
	ModelID            string                       `json:"modelId"`
	PromptVersion      string                       `json:"promptVersion"`
	Repaired           bool                         `json:"repaired"`
	Objects            []baseline.Object            `json:"objects"`
	ExcludedCandidates []baseline.ExcludedCandidate `json:"excludedCandidates"`
}

func baselineHandler(analyzer BaselineAnalyzer) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		request.Body = http.MaxBytesReader(writer, request.Body, MaxVisionRequestBytes)
		mediaType, _, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
		if err != nil || mediaType != "multipart/form-data" {
			apierror.Write(writer, http.StatusUnsupportedMediaType, unsupportedMedia)
			return
		}
		if request.Header.Get("X-Schema-Version") != "1.0" ||
			strings.TrimSpace(request.Header.Get("X-Client-Version")) == "" ||
			strings.TrimSpace(request.Header.Get("Idempotency-Key")) == "" {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}
		if err := request.ParseMultipartForm(MaxVisionRequestBytes); err != nil {
			var tooLarge *http.MaxBytesError
			if errors.As(err, &tooLarge) {
				apierror.Write(writer, http.StatusRequestEntityTooLarge, payloadTooLarge)
				return
			}
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}
		defer request.MultipartForm.RemoveAll()

		if err := decodeMetadata(request.FormValue("metadata")); err != nil {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}
		image, header, err := request.FormFile("image")
		if err != nil {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}
		defer image.Close()
		if header.Header.Get("Content-Type") != "image/jpeg" {
			apierror.Write(writer, http.StatusUnsupportedMediaType, unsupportedMedia)
			return
		}
		jpeg, err := io.ReadAll(io.LimitReader(image, MaxImageBytes+1))
		if err != nil {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}
		if len(jpeg) == 0 {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}
		if int64(len(jpeg)) > MaxImageBytes {
			apierror.Write(writer, http.StatusRequestEntityTooLarge, payloadTooLarge)
			return
		}
		if analyzer == nil {
			apierror.Write(writer, http.StatusServiceUnavailable, upstreamFailure)
			return
		}

		output, err := analyzer.Analyze(request.Context(), jpeg)
		if err != nil {
			var serviceError *baselineapi.Error
			if errors.As(err, &serviceError) && serviceError.Stage == baselineapi.StageRequest {
				apierror.Write(writer, http.StatusUnprocessableEntity, analysisRejected)
				return
			}
			apierror.Write(writer, http.StatusBadGateway, upstreamFailure)
			return
		}
		if len(output.Attempts) == 0 || output.Attempts[len(output.Attempts)-1].ModelID == "" {
			apierror.Write(writer, http.StatusBadGateway, upstreamFailure)
			return
		}

		response := baselineResponse{
			SchemaVersion:      "1.0",
			Status:             "ok",
			ModelID:            output.Attempts[len(output.Attempts)-1].ModelID,
			PromptVersion:      output.PromptVersion,
			Repaired:           output.Repaired,
			Objects:            output.Result.Objects,
			ExcludedCandidates: output.Result.ExcludedCandidates,
		}
		writer.Header().Set("Content-Type", "application/json; charset=utf-8")
		if err := json.NewEncoder(writer).Encode(response); err != nil {
			return
		}
	}
}

func decodeMetadata(raw string) error {
	if strings.TrimSpace(raw) == "" {
		return errors.New("metadata is required")
	}
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.DisallowUnknownFields()
	var metadata captureMetadata
	if err := decoder.Decode(&metadata); err != nil {
		return err
	}
	if metadata.SceneID == "" || metadata.CapturedAt.IsZero() {
		return errors.New("metadata fields are required")
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return errors.New("metadata has trailing content")
	}
	return nil
}
