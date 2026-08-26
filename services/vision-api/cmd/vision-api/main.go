package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/furukawa1020/modose/services/vision-api/internal/baselineapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/compareapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/config"
	"github.com/furukawa1020/modose/services/vision-api/internal/firestoremetadata"
	"github.com/furukawa1020/modose/services/vision-api/internal/httpapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/metadataapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/scenemetadata"
	"github.com/furukawa1020/modose/services/vision-api/internal/server"
	"github.com/furukawa1020/modose/services/vision-api/internal/verifyapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

func main() {
	serviceConfig, err := config.Load(os.LookupEnv)
	if err != nil {
		log.Printf("service configuration rejected: %v", err)
		os.Exit(2)
	}
	vertexConfig, err := vertex.LoadConfig(os.LookupEnv)
	if err != nil {
		log.Printf("Vertex configuration rejected: %v", err)
		os.Exit(2)
	}

	ctx, stop := signal.NotifyContext(
		context.Background(),
		syscall.SIGINT,
		syscall.SIGTERM,
	)
	defer stop()

	vertexClient, err := vertex.NewClient(ctx, vertexConfig)
	if err != nil {
		log.Printf("Vertex client initialization failed: %v", err)
		os.Exit(2)
	}
	firestoreClient, err := firestoremetadata.Open(ctx, vertexConfig.Project)
	if err != nil {
		log.Printf("Firestore client initialization failed: %v", err)
		os.Exit(2)
	}
	defer func() {
		if err := firestoreClient.Close(); err != nil {
			log.Printf("Firestore client close failed: %v", err)
		}
	}()

	baselineService := baselineapi.NewService(vertexClient)
	compareService := compareapi.NewService(vertexClient)
	verifyService := verifyapi.NewService(vertexClient)
	metadataRepository := scenemetadata.NewRepository(firestoreClient)
	metadataService := metadataapi.NewService(metadataRepository)
	router := httpapi.NewVisionRouter(
		httpapi.ReadinessProbeFunc(func(context.Context) error { return nil }),
		httpapi.VisionAnalyzers{
			Baseline: baselineService,
			Compare:  compareService,
			Verify:   verifyService,
			Metadata: metadataService,
		},
	)
	if err := server.Run(ctx, serviceConfig, router); err != nil {
		log.Printf("vision service stopped with error: %v", err)
		os.Exit(1)
	}
}
