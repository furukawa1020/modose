package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime"
	"net/http"
	"strings"

	"github.com/furukawa1020/modose/services/vision-api/internal/apierror"
	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/compare"
	"github.com/furukawa1020/modose/services/vision-api/internal/compareapi"
)

type CompareAnalyzer interface {
	Compare(context.Context, []byte, []byte, baseline.Result) (compareapi.Output, error)
}

type compareResponse struct {
	SchemaVersion string                `json:"schemaVersion"`
	Status        string                `json:"status"`
	ModelID       string                `json:"modelId"`
	PromptVersion string                `json:"promptVersion"`
	Repaired      bool                  `json:"repaired"`
	Matches       []compare.Match       `json:"matches"`
	AddedObjects  []compare.AddedObject `json:"addedObjects"`
}

func compareHandler(analyzer CompareAnalyzer) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		if analyzer == nil {
			apierror.Write(writer, http.StatusServiceUnavailable, upstreamFailure)
			return
		}
		if err := validateCompareHeaders(request); err != nil {
			apierror.Write(writer, http.StatusBadRequest, invalidRequest)
			return
		}

		request.Body = http.MaxBytesReader(writer, request.Body, MaxVisionRequestBytes)
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
		confirmed, err := decodeConfirmedObjects(request.FormValue("confirmedObjects"))
		if err != nil {
			apierror.Write(writer, http.StatusUnprocessableEntity, analysisRejected)
			return
		}
		savedJPEG, status, problem := readCompareJPEG(request, "baselineImage")
		if problem != nil {
			apierror.Write(writer, status, *problem)
			return
		}
		currentJPEG, status, problem := readCompareJPEG(request, "currentImage")
		if problem != nil {
			apierror.Write(writer, status, *problem)
			return
		}

		output, err := analyzer.Compare(request.Context(), savedJPEG, currentJPEG, confirmed)
		if err != nil {
			var serviceError *compareapi.Error
			if errors.As(err, &serviceError) && serviceError.Stage == compareapi.StageRequest {
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

		response := compareResponse{
			SchemaVersion: "1.0",
			Status:        "ok",
			ModelID:       output.Attempts[len(output.Attempts)-1].ModelID,
			PromptVersion: output.PromptVersion,
			Repaired:      output.Repaired,
			Matches:       output.Result.Matches,
			AddedObjects:  output.Result.AddedObjects,
		}
		writer.Header().Set("Content-Type", "application/json; charset=utf-8")
		if err := json.NewEncoder(writer).Encode(response); err != nil {
			return
		}
	}
}

func validateCompareHeaders(request *http.Request) error {
	mediaType, _, err := mime.ParseMediaType(request.Header.Get("Content-Type"))
	if err != nil || mediaType != "multipart/form-data" {
		return fmt.Errorf("unsupported media type")
	}
	if request.Header.Get("X-Schema-Version") != "1.0" ||
		strings.TrimSpace(request.Header.Get("X-Client-Version")) == "" ||
		strings.TrimSpace(request.Header.Get("Idempotency-Key")) == "" {
		return fmt.Errorf("required contract header is missing")
	}
	return nil
}

func decodeConfirmedObjects(raw string) (baseline.Result, error) {
	var result baseline.Result
	if strings.TrimSpace(raw) == "" {
		return result, fmt.Errorf("confirmedObjects is required")
	}
	decoder := json.NewDecoder(strings.NewReader(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&result); err != nil {
		return result, fmt.Errorf("decode confirmedObjects: %w", err)
	}
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) {
		return result, fmt.Errorf("confirmedObjects has trailing content")
	}
	if err := baseline.Validate(result); err != nil {
		return result, fmt.Errorf("validate confirmedObjects: %w", err)
	}
	return result, nil
}

func readCompareJPEG(request *http.Request, field string) ([]byte, int, *apierror.Error) {
	file, header, err := request.FormFile(field)
	if err != nil {
		return nil, http.StatusBadRequest, &invalidRequest
	}
	defer file.Close()
	if header.Header.Get("Content-Type") != "image/jpeg" {
		return nil, http.StatusUnsupportedMediaType, &unsupportedMedia
	}
	data, err := io.ReadAll(io.LimitReader(file, MaxImageBytes+1))
	if err != nil || len(data) == 0 {
		return nil, http.StatusBadRequest, &invalidRequest
	}
	if int64(len(data)) > MaxImageBytes {
		return nil, http.StatusRequestEntityTooLarge, &payloadTooLarge
	}
	return data, 0, nil
}
