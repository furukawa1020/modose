package main

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"os"
	"strconv"
	"time"
)

const (
	defaultPort    = 8080
	requestTimeout = 2 * time.Second
)

type httpDoer interface {
	Do(*http.Request) (*http.Response, error)
}

func main() {
	url, err := healthURL(os.Getenv("PORT"))
	if err != nil {
		os.Exit(1)
	}

	ctx, cancel := context.WithTimeout(context.Background(), requestTimeout)
	defer cancel()
	if err := check(ctx, http.DefaultClient, url); err != nil {
		os.Exit(1)
	}
}

func healthURL(rawPort string) (string, error) {
	port := defaultPort
	if rawPort != "" {
		parsed, err := strconv.Atoi(rawPort)
		if err != nil || parsed < 1 || parsed > 65535 {
			return "", fmt.Errorf("invalid healthcheck port")
		}
		port = parsed
	}
	return "http://" + net.JoinHostPort(
		"127.0.0.1",
		strconv.Itoa(port),
	) + "/healthz", nil
}

func check(ctx context.Context, client httpDoer, url string) error {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return fmt.Errorf("create healthcheck request: %w", err)
	}
	response, err := client.Do(request)
	if err != nil {
		return fmt.Errorf("execute healthcheck request: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("healthcheck status: %d", response.StatusCode)
	}
	return nil
}
