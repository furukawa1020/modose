package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	monitoring "cloud.google.com/go/monitoring/apiv3"
	firebase "firebase.google.com/go/v4"

	"github.com/furukawa1020/modose/services/vision-api/internal/baselineapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/cloudmetrics"
	"github.com/furukawa1020/modose/services/vision-api/internal/compareapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/config"
	"github.com/furukawa1020/modose/services/vision-api/internal/firebaseappcheck"
	"github.com/furukawa1020/modose/services/vision-api/internal/firebaseidentity"
	"github.com/furukawa1020/modose/services/vision-api/internal/firestoremetadata"
	"github.com/furukawa1020/modose/services/vision-api/internal/httpapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/metadataapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/observability"
	"github.com/furukawa1020/modose/services/vision-api/internal/scenemetadata"
	"github.com/furukawa1020/modose/services/vision-api/internal/server"
	"github.com/furukawa1020/modose/services/vision-api/internal/verifyapi"
	"github.com/furukawa1020/modose/services/vision-api/internal/vertex"
)

const (
	metricFlushInterval     = 10 * time.Second
	metricFlushDeadline     = 5 * time.Second
	finalMetricFlushDeadline = 5 * time.Second
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

	monitoringClient, err := monitoring.NewMetricClient(ctx)
	if err != nil {
		log.Printf("Cloud Monitoring client initialization failed: %v", err)
		os.Exit(2)
	}
	defer func() {
		if err := monitoringClient.Close(); err != nil {
			log.Printf("Cloud Monitoring client close failed: %v", err)
		}
	}()
	metricSink, err := cloudmetrics.New(monitoringClient, vertexConfig.Project)
	if err != nil {
		log.Printf("Cloud Monitoring sink initialization failed: %v", err)
		os.Exit(2)
	}
	defer func() {
		flushContext, cancel := context.WithTimeout(
			context.Background(),
			finalMetricFlushDeadline,
		)
		defer cancel()
		if err := metricSink.Flush(flushContext); err != nil {
			log.Printf("Cloud Monitoring final flush failed: %v", err)
		}
	}()
	observationRecorder := observability.NewMultiRecorder(
		observability.NewJSONLogger(os.Stdout),
		observability.NewMetricsRecorder(metricSink),
	)

	firebaseApp, err := firebase.NewApp(ctx, &firebase.Config{ProjectID: vertexConfig.Project})
	if err != nil {
		log.Printf("Firebase application initialization failed: %v", err)
		os.Exit(2)
	}
	authClient, err := firebaseApp.Auth(ctx)
	if err != nil {
		log.Printf("Firebase Auth client initialization failed: %v", err)
		os.Exit(2)
	}
	appCheckClient, err := firebaseApp.AppCheck(ctx)
	if err != nil {
		log.Printf("Firebase App Check client initialization failed: %v", err)
		os.Exit(2)
	}
	idTokenVerifier := firebaseidentity.New(authClient)
	appCheckVerifier := firebaseappcheck.New(appCheckClient)

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
			Baseline:         baselineService,
			Compare:          compareService,
			Verify:           verifyService,
			Metadata:         metadataService,
			IDTokenVerifier:  idTokenVerifier,
			AppCheckVerifier: appCheckVerifier,
			Observation: httpapi.ObservationConfig{
				Recorder: observationRecorder,
				ModelID:  vertexConfig.ModelID,
			},
		},
	)

	metricFlushContext, stopMetricFlush := context.WithCancel(ctx)
	metricFlushDone := make(chan struct{})
	go func() {
		defer close(metricFlushDone)
		if err := cloudmetrics.RunPeriodic(
			metricFlushContext,
			metricSink,
			metricFlushInterval,
			metricFlushDeadline,
			func(err error) {
				log.Printf("Cloud Monitoring periodic flush failed: %v", err)
			},
		); err != nil {
			log.Printf("Cloud Monitoring periodic flush stopped: %v", err)
		}
	}()

	runErr := server.Run(ctx, serviceConfig, router)
	stopMetricFlush()
	<-metricFlushDone
	if runErr != nil {
		log.Printf("vision service stopped with error: %v", runErr)
		os.Exit(1)
	}
}
