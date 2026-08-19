package config

import (
	"fmt"
	"strconv"
	"time"
)

type LookupEnv func(string) (string, bool)

type Config struct {
	Environment     string
	Port            int
	ReadTimeout     time.Duration
	WriteTimeout    time.Duration
	IdleTimeout     time.Duration
	ShutdownTimeout time.Duration
}

type ErrorReason string

const (
	ReasonInvalid    ErrorReason = "invalid"
	ReasonOutOfRange ErrorReason = "out_of_range"
)

type Error struct {
	Field  string
	Reason ErrorReason
}

func (e *Error) Error() string {
	return fmt.Sprintf("invalid service configuration: %s is %s", e.Field, e.Reason)
}

func Load(lookup LookupEnv) (Config, error) {
	environment := valueOrDefault(lookup, "APP_ENV", "development")
	if !isEnvironmentAllowed(environment) {
		return Config{}, &Error{Field: "APP_ENV", Reason: ReasonInvalid}
	}

	port, err := integer(lookup, "PORT", 8080, 1, 65535)
	if err != nil {
		return Config{}, err
	}
	readTimeout, err := duration(lookup, "HTTP_READ_TIMEOUT", 5*time.Second)
	if err != nil {
		return Config{}, err
	}
	writeTimeout, err := duration(lookup, "HTTP_WRITE_TIMEOUT", 15*time.Second)
	if err != nil {
		return Config{}, err
	}
	idleTimeout, err := duration(lookup, "HTTP_IDLE_TIMEOUT", 60*time.Second)
	if err != nil {
		return Config{}, err
	}
	shutdownTimeout, err := duration(lookup, "HTTP_SHUTDOWN_TIMEOUT", 10*time.Second)
	if err != nil {
		return Config{}, err
	}

	return Config{
		Environment:     environment,
		Port:            port,
		ReadTimeout:     readTimeout,
		WriteTimeout:    writeTimeout,
		IdleTimeout:     idleTimeout,
		ShutdownTimeout: shutdownTimeout,
	}, nil
}

func valueOrDefault(lookup LookupEnv, key, fallback string) string {
	if value, ok := lookup(key); ok && value != "" {
		return value
	}
	return fallback
}

func integer(lookup LookupEnv, key string, fallback, minimum, maximum int) (int, error) {
	raw := valueOrDefault(lookup, key, strconv.Itoa(fallback))
	value, err := strconv.Atoi(raw)
	if err != nil {
		return 0, &Error{Field: key, Reason: ReasonInvalid}
	}
	if value < minimum || value > maximum {
		return 0, &Error{Field: key, Reason: ReasonOutOfRange}
	}
	return value, nil
}

func duration(lookup LookupEnv, key string, fallback time.Duration) (time.Duration, error) {
	raw := valueOrDefault(lookup, key, fallback.String())
	value, err := time.ParseDuration(raw)
	if err != nil {
		return 0, &Error{Field: key, Reason: ReasonInvalid}
	}
	if value <= 0 {
		return 0, &Error{Field: key, Reason: ReasonOutOfRange}
	}
	return value, nil
}

func isEnvironmentAllowed(value string) bool {
	switch value {
	case "development", "test", "staging", "production":
		return true
	default:
		return false
	}
}
