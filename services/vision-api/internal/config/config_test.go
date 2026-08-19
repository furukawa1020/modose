package config

import (
	"errors"
	"testing"
	"time"
)

func TestLoadDefaults(t *testing.T) {
	config, err := Load(mapLookup(nil))
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if config.Environment != "development" || config.Port != 8080 {
		t.Fatalf("Load() = %#v", config)
	}
	if config.ReadTimeout != 5*time.Second || config.ShutdownTimeout != 10*time.Second {
		t.Fatalf("Load() timeouts = %#v", config)
	}
}

func TestLoadOverrides(t *testing.T) {
	config, err := Load(mapLookup(map[string]string{
		"APP_ENV":              "production",
		"PORT":                 "9090",
		"HTTP_READ_TIMEOUT":    "3s",
		"HTTP_SHUTDOWN_TIMEOUT": "20s",
	}))
	if err != nil {
		t.Fatalf("Load() error = %v", err)
	}
	if config.Environment != "production" || config.Port != 9090 {
		t.Fatalf("Load() = %#v", config)
	}
	if config.ReadTimeout != 3*time.Second || config.ShutdownTimeout != 20*time.Second {
		t.Fatalf("Load() timeouts = %#v", config)
	}
}

func TestLoadRejectsInvalidValuesWithoutEchoingThem(t *testing.T) {
	_, err := Load(mapLookup(map[string]string{"PORT": "secret-invalid-value"}))
	var configError *Error
	if !errors.As(err, &configError) {
		t.Fatalf("Load() error = %T, want *Error", err)
	}
	if configError.Field != "PORT" || configError.Reason != ReasonInvalid {
		t.Fatalf("Load() error = %#v", configError)
	}
	if err.Error() == "" || err.Error() == "secret-invalid-value" {
		t.Fatalf("Load() leaked or omitted safe error: %q", err)
	}
}

func TestLoadRejectsOutOfRangeAndUnknownEnvironment(t *testing.T) {
	tests := []map[string]string{
		{"PORT": "0"},
		{"HTTP_IDLE_TIMEOUT": "0s"},
		{"APP_ENV": "unknown"},
	}
	for _, values := range tests {
		if _, err := Load(mapLookup(values)); err == nil {
			t.Fatalf("Load(%v) error = nil", values)
		}
	}
}

func mapLookup(values map[string]string) LookupEnv {
	return func(key string) (string, bool) {
		value, ok := values[key]
		return value, ok
	}
}
