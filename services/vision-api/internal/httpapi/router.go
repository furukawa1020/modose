package httpapi

import (
	"context"
	"encoding/json"
	"net/http"

	"github.com/furukawa1020/modose/services/vision-api/internal/apierror"
	"github.com/furukawa1020/modose/services/vision-api/internal/appidentity"
	"github.com/furukawa1020/modose/services/vision-api/internal/identity"
	"github.com/furukawa1020/modose/services/vision-api/internal/observability"
)

type ReadinessProbe interface {
	Ready(context.Context) error
}

type ReadinessProbeFunc func(context.Context) error

func (function ReadinessProbeFunc) Ready(ctx context.Context) error {
	return function(ctx)
}

type VisionAnalyzers struct {
	Baseline         BaselineAnalyzer
	Compare          CompareAnalyzer
	Verify           VerifyAnalyzer
	Metadata         MetadataService
	IDTokenVerifier  identity.IDTokenVerifier
	AppCheckVerifier appidentity.TokenVerifier
	Observation      ObservationConfig
}

type Router struct {
	mux *http.ServeMux
}

func NewRouter(probe ReadinessProbe) *Router {
	return NewVisionRouter(probe, VisionAnalyzers{})
}

func NewRouterWithBaseline(probe ReadinessProbe, analyzer BaselineAnalyzer) *Router {
	return NewVisionRouter(probe, VisionAnalyzers{Baseline: analyzer})
}

func NewVisionRouter(probe ReadinessProbe, analyzers VisionAnalyzers) *Router {
	router := &Router{mux: http.NewServeMux()}
	router.mux.HandleFunc("/healthz", getOnly(health))
	router.mux.HandleFunc("/readyz", getOnly(readiness(probe)))
	router.mux.HandleFunc(
		"/v1/vision/baseline",
		observed(
			analyzers.Observation,
			observability.OperationBaseline,
			postOnly(authenticated(analyzers, baselineHandler(analyzers.Baseline))),
		),
	)
	router.mux.HandleFunc(
		"/v1/vision/compare",
		observed(
			analyzers.Observation,
			observability.OperationCompare,
			postOnly(authenticated(analyzers, compareHandler(analyzers.Compare))),
		),
	)
	router.mux.HandleFunc(
		"/v1/vision/verify",
		observed(
			analyzers.Observation,
			observability.OperationVerify,
			postOnly(authenticated(analyzers, verifyHandler(analyzers.Verify))),
		),
	)
	router.mux.HandleFunc(
		"/v1/scenes/metadata",
		observed(
			analyzers.Observation,
			observability.OperationMetadataStore,
			postOnly(
				authenticated(
					analyzers,
					storeMetadataHandler(analyzers.Metadata),
				),
			),
		),
	)
	router.mux.HandleFunc("/v1/scenes", notFound)
	router.mux.HandleFunc(
		"/v1/scenes/",
		observed(
			analyzers.Observation,
			observability.OperationMetadataDelete,
			methodOnly(
				http.MethodDelete,
				authenticated(
					analyzers,
					deleteMetadataHandler(analyzers.Metadata),
				),
			),
		),
	)
	return router
}

func (router *Router) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	handler, pattern := router.mux.Handler(request)
	if pattern == "" {
		notFound(writer, request)
		return
	}
	handler.ServeHTTP(writer, request)
}

func notFound(writer http.ResponseWriter, _ *http.Request) {
	apierror.Write(writer, http.StatusNotFound, apierror.NotFound)
}

func authenticated(
	analyzers VisionAnalyzers,
	next http.HandlerFunc,
) http.HandlerFunc {
	if analyzers.IDTokenVerifier == nil && analyzers.AppCheckVerifier == nil {
		return next
	}
	return requireFirebaseRequest(
		analyzers.IDTokenVerifier,
		analyzers.AppCheckVerifier,
		next,
	).ServeHTTP
}

func observed(
	config ObservationConfig,
	operation observability.Operation,
	next http.HandlerFunc,
) http.HandlerFunc {
	return observeRequest(operation, config, next).ServeHTTP
}

func getOnly(next http.HandlerFunc) http.HandlerFunc {
	return methodOnly(http.MethodGet, next)
}

func postOnly(next http.HandlerFunc) http.HandlerFunc {
	return methodOnly(http.MethodPost, next)
}

func methodOnly(method string, next http.HandlerFunc) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		if request.Method != method {
			writer.Header().Set("Allow", method)
			apierror.Write(writer, http.StatusMethodNotAllowed, apierror.MethodNotAllowed)
			return
		}
		next(writer, request)
	}
}

func health(writer http.ResponseWriter, _ *http.Request) {
	writeStatus(writer, http.StatusOK, "ok")
}

func readiness(probe ReadinessProbe) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		if probe == nil || probe.Ready(request.Context()) != nil {
			apierror.Write(writer, http.StatusServiceUnavailable, apierror.NotReady)
			return
		}
		writeStatus(writer, http.StatusOK, "ready")
	}
}

func writeStatus(writer http.ResponseWriter, status int, value string) {
	writer.Header().Set("Content-Type", "application/json; charset=utf-8")
	writer.WriteHeader(status)
	if err := json.NewEncoder(writer).Encode(map[string]string{"status": value}); err != nil {
		return
	}
}
