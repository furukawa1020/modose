package verify

var ResponseSchemaJSON = []byte(`{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "type": "object",
  "additionalProperties": false,
  "required": ["status", "corrections"],
  "properties": {
    "status": {
      "type": "string",
      "enum": ["verified", "needs_correction", "uncertain"]
    },
    "corrections": {
      "type": "array",
      "maxItems": 5,
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["baselineObjectId", "reason"],
        "properties": {
          "baselineObjectId": {"type": "string", "minLength": 1},
          "reason": {"type": "string", "minLength": 1}
        }
      }
    },
    "uncertaintyReason": {
      "type": "string",
      "minLength": 1
    }
  }
}`)
