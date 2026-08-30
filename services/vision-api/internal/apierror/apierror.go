package apierror

import (
	"encoding/json"
	"net/http"
)

type Error struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

var (
	NotFound         = Error{Code: "not_found", Message: "The requested resource was not found."}
	MethodNotAllowed = Error{Code: "method_not_allowed", Message: "The request method is not allowed."}
	NotReady         = Error{Code: "service_not_ready", Message: "The service is not ready."}
	Internal         = Error{Code: "internal_error", Message: "The request could not be completed."}
)

type envelope struct {
	Error Error `json:"error"`
}

type publicErrorObserver interface {
	ObservePublicError(string)
}

func Write(writer http.ResponseWriter, status int, public Error) {
	if observer, ok := writer.(publicErrorObserver); ok {
		observer.ObservePublicError(public.Code)
	}
	writer.Header().Set("Content-Type", "application/json; charset=utf-8")
	writer.WriteHeader(status)
	if err := json.NewEncoder(writer).Encode(envelope{Error: public}); err != nil {
		return
	}
}
