#!/usr/bin/env bash

set -euo pipefail

go run github.com/getkin/kin-openapi/cmd/validate@v0.145.0 --ext -- api/openapi.yaml
