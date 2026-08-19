package server

import (
	"context"
	"errors"
	"net"
	"net/http"
	"testing"
	"time"

	"github.com/furukawa1020/modose/services/vision-api/internal/config"
)

func TestRunListenerShutsDownWhenContextEnds(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() {
		done <- RunListener(ctx, testConfig(), http.HandlerFunc(func(http.ResponseWriter, *http.Request) {}), listener)
	}()

	cancel()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("RunListener() error = %v", err)
		}
	case <-time.After(time.Second):
		t.Fatal("RunListener() did not stop before the test deadline")
	}
}

func TestRunListenerReturnsTypedServeFailure(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	if err := listener.Close(); err != nil {
		t.Fatalf("close listener: %v", err)
	}

	err = RunListener(context.Background(), testConfig(), http.NewServeMux(), listener)
	var serverError *Error
	if !errors.As(err, &serverError) {
		t.Fatalf("RunListener() error = %T, want *Error", err)
	}
	if serverError.Stage != StageServe {
		t.Fatalf("RunListener() stage = %q", serverError.Stage)
	}
}

func testConfig() config.Config {
	return config.Config{
		Port:            8080,
		ReadTimeout:     time.Second,
		WriteTimeout:    time.Second,
		IdleTimeout:     time.Second,
		ShutdownTimeout: time.Second,
	}
}
