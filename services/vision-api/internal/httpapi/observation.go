package httpapi

import (
	"net/http"
	"time"

	"github.com/furukawa1020/modose/services/vision-api/internal/observability"
)

type ObservationConfig struct {
	Recorder      observability.Recorder
	ModelID       string
	SchemaVersion string
	Now           func() time.Time
}

type observationWriter struct {
	http.ResponseWriter
	publicErrorCode string
}

func (writer *observationWriter) ObservePublicError(code string) {
	if writer.publicErrorCode == "" {
		writer.publicErrorCode = code
	}
}

func (writer *observationWriter) Unwrap() http.ResponseWriter {
	return writer.ResponseWriter
}

func observeRequest(
	operation observability.Operation,
	config ObservationConfig,
	next http.Handler,
) http.Handler {
	return http.HandlerFunc(func(writer http.ResponseWriter, request *http.Request) {
		now := config.Now
		if now == nil {
			now = time.Now
		}
		startedAt := now()
		observedWriter := &observationWriter{ResponseWriter: writer}
		defer func() {
			recordRequestObservation(
				operation,
				config,
				observedWriter.publicErrorCode,
				now().Sub(startedAt),
			)
		}()
		next.ServeHTTP(observedWriter, request)
	})
}

func recordRequestObservation(
	operation observability.Operation,
	config ObservationConfig,
	publicErrorCode string,
	duration time.Duration,
) {
	if config.Recorder == nil {
		return
	}
	event, err := observability.NewEvent(operation, duration.Milliseconds())
	if err != nil {
		return
	}
	event, err = event.WithModelID(config.ModelID)
	if err != nil {
		return
	}
	event, err = event.WithSchemaVersion(config.SchemaVersion)
	if err != nil {
		return
	}
	event, err = event.WithErrorCode(publicErrorCode)
	if err != nil {
		return
	}
	_ = config.Recorder.Record(event)
}
