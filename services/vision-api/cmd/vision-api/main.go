package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/furukawa1020/modose/services/vision-api/internal/config"
	"github.com/furukawa1020/modose/services/vision-api/internal/httpapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/server"
)

func main() {
	serviceConfig, err := config.Load(os.LookupEnv)
	if err != nil {
		log.Printf("service configuration rejected: %v", err)
		os.Exit(2)
	}

	ctx, stop := signal.NotifyContext(
		context.Background(),
		syscall.SIGINT,
		syscall.SIGTERM,
	)
	defer stop()

	router := httpapi.NewRouter(
		httpapi.ReadinessProbeFunc(func(context.Context) error { return nil }),
	)
	if err := server.Run(ctx, serviceConfig, router); err != nil {
		log.Printf("vision service stopped with error: %v", err)
		os.Exit(1)
	}
}
