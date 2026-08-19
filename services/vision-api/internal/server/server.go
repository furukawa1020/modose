package server

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"

	"github.com/furukawa1020/modose/services/vision-api/internal/config"
)

type FailureStage string

const (
	StageListen   FailureStage = "listen"
	StageServe    FailureStage = "serve"
	StageShutdown FailureStage = "shutdown"
)

type Error struct {
	Stage FailureStage
	Err   error
}

func (e *Error) Error() string {
	return fmt.Sprintf("http server %s failed: %v", e.Stage, e.Err)
}

func (e *Error) Unwrap() error {
	return e.Err
}

func Run(ctx context.Context, serviceConfig config.Config, handler http.Handler) error {
	listener, err := net.Listen("tcp", fmt.Sprintf(":%d", serviceConfig.Port))
	if err != nil {
		return &Error{Stage: StageListen, Err: err}
	}
	return RunListener(ctx, serviceConfig, handler, listener)
}

func RunListener(
	ctx context.Context,
	serviceConfig config.Config,
	handler http.Handler,
	listener net.Listener,
) error {
	httpServer := &http.Server{
		Handler:      handler,
		ReadTimeout:  serviceConfig.ReadTimeout,
		WriteTimeout: serviceConfig.WriteTimeout,
		IdleTimeout:  serviceConfig.IdleTimeout,
	}
	serveErrors := make(chan error, 1)
	go func() {
		serveErrors <- httpServer.Serve(listener)
	}()

	select {
	case err := <-serveErrors:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return &Error{Stage: StageServe, Err: err}
	case <-ctx.Done():
		shutdownContext, cancel := context.WithTimeout(
			context.Background(),
			serviceConfig.ShutdownTimeout,
		)
		defer cancel()
		if err := httpServer.Shutdown(shutdownContext); err != nil {
			_ = httpServer.Close()
			return &Error{Stage: StageShutdown, Err: err}
		}
		if err := <-serveErrors; err != nil && !errors.Is(err, http.ErrServerClosed) {
			return &Error{Stage: StageServe, Err: err}
		}
		return nil
	}
}
