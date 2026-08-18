#!/usr/bin/env bash

set -euo pipefail

go run github.com/getkin/kin-openapi/cmd/validate@v0.145.0 -- api/openapi.yaml
