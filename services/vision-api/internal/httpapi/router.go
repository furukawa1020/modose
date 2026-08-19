package httpapi

import (
	"context"
	"encoding/json"
	"net/http"

	"github.com/furukawa1020/modose/services/vision-api/internal/apierror"
)

type ReadinessProbe interface {
	Ready(context.Context) error
}

type ReadinessProbeFunc func(context.Context) error

func (function ReadinessProbeFunc) Ready(ctx context.Context) error {
	return function(ctx)
}

type Router struct {
	mux *http.ServeMux
}

func NewRouter(probe ReadinessProbe) *Router {
	router := &Router{mux: http.NewServeMux()}
	router.mux.HandleFunc("/healthz", getOnly(health))
	router.mux.HandleFunc("/readyz", getOnly(readiness(probe)))
	return router
}

func (router *Router) ServeHTTP(writer http.ResponseWriter, request *http.Request) {
	handler, pattern := router.mux.Handler(request)
	if pattern == "" {
		apierror.Write(writer, http.StatusNotFound, apierror.NotFound)
		return
	}
	handler.ServeHTTP(writer, request)
}

func getOnly(next http.HandlerFunc) http.HandlerFunc {
	return func(writer http.ResponseWriter, request *http.Request) {
		if request.Method != http.MethodGet {
			writer.Header().Set("Allow", http.MethodGet)
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
