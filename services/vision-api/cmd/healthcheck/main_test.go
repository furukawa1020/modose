package main

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestHealthURLUsesValidatedLoopbackPort(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name string
		port string
		want string
	}{
		{
			name: "default",
			want: "http://127.0.0.1:8080/healthz",
		},
		{
			name: "configured",
			port: "9090",
			want: "http://127.0.0.1:9090/healthz",
		},
	}

	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			got, err := healthURL(test.port)
			if err != nil {
				t.Fatalf("healthURL() error = %v", err)
			}
			if got != test.want {
				t.Fatalf("healthURL() = %q, want %q", got, test.want)
			}
		})
	}
}

func TestHealthURLRejectsInvalidPort(t *testing.T) {
	t.Parallel()

	for _, port := range []string{"text", "0", "65536", "8080/path"} {
		if _, err := healthURL(port); err == nil {
			t.Fatalf("healthURL(%q) error = nil", port)
		}
	}
}

func TestCheckRequiresHTTP200(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name       string
		statusCode int
		wantError  bool
	}{
		{name: "healthy", statusCode: http.StatusOK},
		{
			name:       "not ready",
			statusCode: http.StatusServiceUnavailable,
			wantError:  true,
		},
		{
			name:       "redirect",
			statusCode: http.StatusTemporaryRedirect,
			wantError:  true,
		},
	}

	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			server := httptest.NewServer(http.HandlerFunc(
				func(writer http.ResponseWriter, _ *http.Request) {
					writer.WriteHeader(test.statusCode)
				},
			))
			defer server.Close()

			err := check(context.Background(), server.Client(), server.URL)
			if test.wantError && err == nil {
				t.Fatal("check() error = nil")
			}
			if !test.wantError && err != nil {
				t.Fatalf("check() error = %v", err)
			}
		})
	}
}
