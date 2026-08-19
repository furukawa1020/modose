package vertex

import (
	"fmt"
	"strings"
	"time"
)

type LookupEnv func(string) (string, bool)

type Config struct {
	Project  string
	Location string
	ModelID  string
	Deadline time.Duration
}

type ConfigError struct {
	Field  string
	Reason string
}

func (e *ConfigError) Error() string {
	return fmt.Sprintf("invalid Vertex configuration: %s is %s", e.Field, e.Reason)
}

func LoadConfig(lookup LookupEnv) (Config, error) {
	project, err := required(lookup, "GOOGLE_CLOUD_PROJECT")
	if err != nil {
		return Config{}, err
	}
	location, err := required(lookup, "GOOGLE_CLOUD_LOCATION")
	if err != nil {
		return Config{}, err
	}
	modelID, err := required(lookup, "VLM_MODEL_ID")
	if err != nil {
		return Config{}, err
	}

	deadline := 12 * time.Second
	if raw, ok := lookup("VLM_DEADLINE"); ok && strings.TrimSpace(raw) != "" {
		deadline, err = time.ParseDuration(raw)
		if err != nil {
			return Config{}, &ConfigError{Field: "VLM_DEADLINE", Reason: "invalid"}
		}
	}
	if deadline <= 0 || deadline > 60*time.Second {
		return Config{}, &ConfigError{Field: "VLM_DEADLINE", Reason: "out_of_range"}
	}

	return Config{
		Project:  project,
		Location: location,
		ModelID:  modelID,
		Deadline: deadline,
	}, nil
}

func required(lookup LookupEnv, key string) (string, error) {
	value, ok := lookup(key)
	value = strings.TrimSpace(value)
	if !ok || value == "" {
		return "", &ConfigError{Field: key, Reason: "required"}
	}
	return value, nil
}
