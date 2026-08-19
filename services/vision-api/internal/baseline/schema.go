package baseline

var ResponseSchemaJSON = []byte(`{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["objects", "excludedCandidates"],
  "properties": {
    "objects": {
      "type": "array",
      "minItems": 1,
      "maxItems": 20,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": [
          "id",
          "displayName",
          "appearanceFeatures",
          "boundingBox",
          "orientationImportant",
          "symmetry"
        ],
        "properties": {
          "id": {
            "type": "string",
            "minLength": 1
          },
          "displayName": {
            "type": "string",
            "minLength": 1
          },
          "appearanceFeatures": {
            "type": "array",
            "minItems": 1,
            "maxItems": 8,
            "items": {
              "type": "string",
              "minLength": 1
            }
          },
          "boundingBox": {
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
          "orientationImportant": {
            "type": "boolean"
          },
          "symmetry": {
            "type": "string",
            "enum": ["none", "bilateral", "rotational"]
          }
        }
      }
    },
    "excludedCandidates": {
      "type": "array",
      "maxItems": 20,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["displayName", "reason"],
        "properties": {
          "displayName": {
            "type": "string",
            "minLength": 1
          },
          "reason": {
            "type": "string",
            "enum": [
              "transparent",
              "reflective",
              "deformable",
              "unsupported_shape",
              "fixed",
              "duplicate_appearance"
            ]
          }
        }
      }
    }
  }
}`)
