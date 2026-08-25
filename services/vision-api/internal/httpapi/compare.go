package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"strings"

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
	return func(response http.ResponseWriter, request *http.Request) {
		if analyzer == nil {
			writeError(response, http.StatusServiceUnavailable, upstreamFailure, "Compare解析を利用できません")
			return
		}
		if err := validateVisionHeaders(request); err != nil {
			writeError(response, http.StatusBadRequest, invalidRequest, err.Error())
			return
		}

		request.Body = http.MaxBytesReader(response, request.Body, MaxVisionRequestBytes)
		if err := request.ParseMultipartForm(MaxVisionRequestBytes); err != nil {
			status, code := http.StatusBadRequest, invalidRequest
			if strings.Contains(err.Error(), "request body too large") {
				status, code = http.StatusRequestEntityTooLarge, payloadTooLarge
			}
			writeError(response, status, code, "multipartリクエストが不正です")
			return
		}

		if _, err := decodeMetadata(request.FormValue("metadata")); err != nil {
			writeError(response, http.StatusBadRequest, invalidRequest, "metadataが不正です")
			return
		}
		confirmed, err := decodeConfirmedObjects(request.FormValue("confirmedObjects"))
		if err != nil {
			writeError(response, http.StatusUnprocessableEntity, analysisRejected, err.Error())
			return
		}
		savedJPEG, err := readJPEG(request, "baselineImage")
		if err != nil {
			writeUploadError(response, err)
			return
		}
		currentJPEG, err := readJPEG(request, "currentImage")
		if err != nil {
			writeUploadError(response, err)
			return
		}

		output, err := analyzer.Compare(request.Context(), savedJPEG, currentJPEG, confirmed)
		if err != nil {
			var serviceError *compareapi.Error
			if errors.As(err, &serviceError) && serviceError.Stage == compareapi.StageRequest {
				writeError(response, http.StatusUnprocessableEntity, analysisRejected, "Compare解析要求を受理できません")
				return
			}
			writeError(response, http.StatusBadGateway, upstreamFailure, "Compare解析に失敗しました")
			return
		}
		if len(output.Attempts) == 0 || output.Attempts[len(output.Attempts)-1].ModelID == "" {
			writeError(response, http.StatusBadGateway, upstreamFailure, "Compare解析メタデータが不正です")
			return
		}

		writeJSON(response, http.StatusOK, compareResponse{
			SchemaVersion: "1.0",
			Status:        "ok",
			ModelID:       output.Attempts[len(output.Attempts)-1].ModelID,
			PromptVersion: output.PromptVersion,
			Repaired:      output.Repaired,
			Matches:       output.Result.Matches,
			AddedObjects:  output.Result.AddedObjects,
		})
	}
}

func validateVisionHeaders(request *http.Request) error {
	if request.Header.Get("X-Schema-Version") != "1.0" {
		return fmt.Errorf("X-Schema-Versionは1.0が必要です")
	}
	if strings.TrimSpace(request.Header.Get("X-Client-Version")) == "" {
		return fmt.Errorf("X-Client-Versionが必要です")
	}
	if strings.TrimSpace(request.Header.Get("Idempotency-Key")) == "" {
		return fmt.Errorf("Idempotency-Keyが必要です")
	}
	mediaType := request.Header.Get("Content-Type")
	if !strings.HasPrefix(strings.ToLower(mediaType), "multipart/form-data") {
		return fmt.Errorf("Content-Typeはmultipart/form-dataが必要です")
	}
	return nil
}

func decodeConfirmedObjects(raw string) (baseline.Result, error) {
	var result baseline.Result
	if strings.TrimSpace(raw) == "" {
		return result, fmt.Errorf("confirmedObjectsが必要です")
	}
	decoder := json.NewDecoder(bytes.NewBufferString(raw))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&result); err != nil {
		return result, fmt.Errorf("confirmedObjectsが不正です")
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		return result, fmt.Errorf("confirmedObjectsは単一JSONである必要があります")
	}
	if err := baseline.Validate(result); err != nil {
		return result, fmt.Errorf("confirmedObjectsが契約を満たしません")
	}
	return result, nil
}

func readJPEG(request *http.Request, field string) ([]byte, error) {
	file, header, err := request.FormFile(field)
	if err != nil {
		return nil, fmt.Errorf("%sが必要です", field)
	}
	defer file.Close()
	if !isJPEG(header) {
		return nil, fmt.Errorf("%sはimage/jpegが必要です", field)
	}
	data, err := io.ReadAll(io.LimitReader(file, MaxImageBytes+1))
	if err != nil {
		return nil, fmt.Errorf("%sを読み取れません", field)
	}
	if len(data) == 0 {
		return nil, fmt.Errorf("%sが空です", field)
	}
	if len(data) > MaxImageBytes {
		return nil, fmt.Errorf("%sが2MBを超えています", field)
	}
	return data, nil
}

func isJPEG(header *multipart.FileHeader) bool {
	return strings.EqualFold(header.Header.Get("Content-Type"), "image/jpeg")
}

func writeUploadError(response http.ResponseWriter, err error) {
	if strings.Contains(err.Error(), "2MB") {
		writeError(response, http.StatusRequestEntityTooLarge, payloadTooLarge, err.Error())
		return
	}
	if strings.Contains(err.Error(), "image/jpeg") {
		writeError(response, http.StatusUnsupportedMediaType, unsupportedMedia, err.Error())
		return
	}
	writeError(response, http.StatusBadRequest, invalidRequest, err.Error())
}
