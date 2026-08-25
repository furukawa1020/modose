package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"

	"github.com/furukawa1020/modose/services/vision-api/internal/apierror"
	"github.com/furukawa1020/modose/services/vision-api/internal/baseline"
	"github.com/furukawa1020/modose/services/vision-api/internal/verify"
	"github.com/furukawa1020/modose/services/vision-api/internal/verifyapi"
)

type VerifyAnalyzer interface {
	Verify(context.Context, []byte, []byte, baseline.Result) (verifyapi.Output, error)
}

type verifyResponse struct {
	SchemaVersion      string              `json:"schemaVersion"`
	Status             string              `json:"status"`
	ModelID            string              `json:"modelId"`
	PromptVersion      string              `json:"promptVersion"`
	Repaired           bool                `json:"repaired"`
	VerificationStatus verify.Status       `json:"verificationStatus"`
	Corrections        []verify.Correction `json:"corrections"`
	UncertaintyReason  string              `json:"uncertaintyReason"`
}

func verifyHandler(analyzer VerifyAnalyzer) http.HandlerFunc {
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
		finalJPEG, status, problem := readCompareJPEG(request, "currentImage")
		if problem != nil {
			apierror.Write(writer, status, *problem)
			return
		}

		output, err := analyzer.Verify(request.Context(), savedJPEG, finalJPEG, confirmed)
		if err != nil {
			var serviceError *verifyapi.Error
			if errors.As(err, &serviceError) && serviceError.Stage == verifyapi.StageRequest {
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

		response := verifyResponse{
			SchemaVersion:      "1.0",
			Status:             "ok",
			ModelID:            output.Attempts[len(output.Attempts)-1].ModelID,
			PromptVersion:      output.PromptVersion,
			Repaired:           output.Repaired,
			VerificationStatus: output.Result.Status,
			Corrections:        output.Result.Corrections,
			UncertaintyReason:  output.Result.UncertaintyReason,
		}
		writer.Header().Set("Content-Type", "application/json; charset=utf-8")
		if err := json.NewEncoder(writer).Encode(response); err != nil {
			return
		}
	}
}
