package observability

import (
	"fmt"
	"strings"
)

type Operation string

const (
	OperationBaseline       Operation = "baseline"
	OperationCompare        Operation = "compare"
	OperationVerify         Operation = "verify"
	OperationMetadataStore  Operation = "metadata_store"
	OperationMetadataDelete Operation = "metadata_delete"
)

type AttributeKey string

const (
	AttributeLatencyMS     AttributeKey = "latency_ms"
	AttributeModelID       AttributeKey = "model_id"
	AttributeSchemaVersion AttributeKey = "schema_version"
	AttributeErrorCode     AttributeKey = "error_code"
)

const (
	maxLatencyMS     int64 = 24 * 60 * 60 * 1000
	maxModelIDBytes        = 160
	maxSchemaBytes         = 32
	maxErrorCodeBytes      = 64
)

type Event struct {
	operation     Operation
	latencyMS     int64
	modelID       string
	schemaVersion string
	errorCode     string
}

type ValidationError struct {
	Field  string
	Reason string
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("invalid observation event: %s is %s", e.Field, e.Reason)
}

func NewEvent(operation Operation, latencyMS int64) (Event, error) {
	if !operation.valid() {
		return Event{}, invalid("operation", "unsupported")
	}
	if latencyMS < 0 || latencyMS > maxLatencyMS {
		return Event{}, invalid("latency_ms", "out_of_range")
	}
	return Event{operation: operation, latencyMS: latencyMS}, nil
}

func (event Event) WithModelID(modelID string) (Event, error) {
	modelID = strings.TrimSpace(modelID)
	if err := validateOptionalValue(
		"model_id",
		modelID,
		maxModelIDBytes,
		isModelCharacter,
	); err != nil {
		return Event{}, err
	}
	event.modelID = modelID
	return event, nil
}

func (event Event) WithSchemaVersion(schemaVersion string) (Event, error) {
	schemaVersion = strings.TrimSpace(schemaVersion)
	if err := validateOptionalValue(
		"schema_version",
		schemaVersion,
		maxSchemaBytes,
		isVersionCharacter,
	); err != nil {
		return Event{}, err
	}
	event.schemaVersion = schemaVersion
	return event, nil
}

func (event Event) WithErrorCode(errorCode string) (Event, error) {
	errorCode = strings.TrimSpace(errorCode)
	if err := validateOptionalValue(
		"error_code",
		errorCode,
		maxErrorCodeBytes,
		isErrorCodeCharacter,
	); err != nil {
		return Event{}, err
	}
	event.errorCode = errorCode
	return event, nil
}

func (event Event) Operation() Operation {
	return event.operation
}

func (event Event) LatencyMS() int64 {
	return event.latencyMS
}

func (event Event) ModelID() string {
	return event.modelID
}

func (event Event) SchemaVersion() string {
	return event.schemaVersion
}

func (event Event) ErrorCode() string {
	return event.errorCode
}

func AllowedAttributeKeys() []AttributeKey {
	return []AttributeKey{
		AttributeLatencyMS,
		AttributeModelID,
		AttributeSchemaVersion,
		AttributeErrorCode,
	}
}

func ValidateAttributeKeys(keys ...AttributeKey) error {
	for _, key := range keys {
		switch key {
		case AttributeLatencyMS,
			AttributeModelID,
			AttributeSchemaVersion,
			AttributeErrorCode:
		default:
			return invalid("attribute_key", "forbidden")
		}
	}
	return nil
}

func (operation Operation) valid() bool {
	switch operation {
	case OperationBaseline,
		OperationCompare,
		OperationVerify,
		OperationMetadataStore,
		OperationMetadataDelete:
		return true
	default:
		return false
	}
}

func validateOptionalValue(
	field string,
	value string,
	maxBytes int,
	allowed func(rune) bool,
) error {
	if value == "" {
		return nil
	}
	if len(value) > maxBytes {
		return invalid(field, "too_long")
	}
	for _, character := range value {
		if !allowed(character) {
			return invalid(field, "unsafe")
		}
	}
	return nil
}

func isModelCharacter(character rune) bool {
	return isAlphaNumeric(character) ||
		strings.ContainsRune("._:/-", character)
}

func isVersionCharacter(character rune) bool {
	return isAlphaNumeric(character) ||
		strings.ContainsRune("._-", character)
}

func isErrorCodeCharacter(character rune) bool {
	return character >= 'a' && character <= 'z' ||
		character >= '0' && character <= '9' ||
		character == '_'
}

func isAlphaNumeric(character rune) bool {
	return character >= 'a' && character <= 'z' ||
		character >= 'A' && character <= 'Z' ||
		character >= '0' && character <= '9'
}

func invalid(field, reason string) error {
	return &ValidationError{Field: field, Reason: reason}
}
