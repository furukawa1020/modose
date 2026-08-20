package compare

var ResponseSchemaJSON = []byte(`{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["matches", "addedObjects"],
  "properties": {
    "matches": {
      "type": "array",
      "minItems": 1,
      "maxItems": 5,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["baselineObjectId", "state", "confidence"],
        "properties": {
          "baselineObjectId": {"type": "string", "minLength": 1},
          "state": {
            "type": "string",
            "enum": [
              "aligned",
              "moved",
              "rotated",
              "moved_rotated",
              "missing",
              "occluded",
              "ambiguous"
            ]
          },
          "confidence": {"type": "number", "minimum": 0, "maximum": 1},
          "currentBox": {
            "type": "object",
            "additionalProperties": false,
            "required": ["yMin", "xMin", "yMax", "xMax"],
            "properties": {
              "yMin": {"type": "integer", "minimum": 0, "maximum": 1000},
              "xMin": {"type": "integer", "minimum": 0, "maximum": 1000},
              "yMax": {"type": "integer", "minimum": 0, "maximum": 1000},
              "xMax": {"type": "integer", "minimum": 0, "maximum": 1000}
            }
          },
          "ambiguityReason": {"type": "string", "minLength": 1}
        }
      }
    },
    "addedObjects": {
      "type": "array",
      "maxItems": 20,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["displayName", "currentBox", "confidence"],
        "properties": {
          "displayName": {"type": "string", "minLength": 1},
          "confidence": {"type": "number", "minimum": 0, "maximum": 1},
          "currentBox": {
            "type": "object",
            "additionalProperties": false,
            "required": ["yMin", "xMin", "yMax", "xMax"],
            "properties": {
              "yMin": {"type": "integer", "minimum": 0, "maximum": 1000},
              "xMin": {"type": "integer", "minimum": 0, "maximum": 1000},
              "yMax": {"type": "integer", "minimum": 0, "maximum": 1000},
              "xMax": {"type": "integer", "minimum": 0, "maximum": 1000}
            }
          }
        }
      }
    }
  }
}`)
