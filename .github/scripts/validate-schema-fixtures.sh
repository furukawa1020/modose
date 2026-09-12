#!/usr/bin/env bash

set -euo pipefail
shopt -s nullglob

validator_dir="$(mktemp -d)"
trap 'rm -rf "$validator_dir"' EXIT

GOBIN="$validator_dir" go install github.com/santhosh-tekuri/jsonschema/cmd/jv@v0.7.0

total=0
for category in baseline compare verify http-adversarial; do
  case "$category" in
    baseline)
      schema="api/schemas/baseline-analysis.schema.json"
      expected_total=3
      ;;
    compare)
      schema="api/schemas/scene-comparison.schema.json"
      expected_total=3
      ;;
    verify)
      schema="api/schemas/scene-verification.schema.json"
      expected_total=3
      ;;
    http-adversarial)
      schema="api/schemas/adversarial-http-cases.schema.json"
      expected_total=1
      ;;
  esac

  category_total=0
  for fixture in "fixtures/$category"/*.json; do
    name="$(basename "$fixture")"
    case "$name" in
      valid-*) expected=valid ;;
      invalid-*|old-version-*) expected=invalid ;;
      *)
        echo "Unknown fixture expectation: $fixture" >&2
        exit 1
        ;;
    esac

    if "$validator_dir/jv" --quiet "$schema" "$fixture" >/dev/null 2>&1; then
      actual=valid
    else
      actual=invalid
    fi

    if [[ "$actual" != "$expected" ]]; then
      echo "Fixture $fixture: expected $expected, got $actual" >&2
      "$validator_dir/jv" "$schema" "$fixture" || true
      exit 1
    fi

    printf '%s: %s\n' "$fixture" "$actual"
    ((category_total += 1))
    ((total += 1))
  done

  if [[ "$category_total" -ne "$expected_total" ]]; then
    echo "Expected $expected_total $category fixtures, found $category_total" >&2
    exit 1
  fi
done

if [[ "$total" -ne 10 ]]; then
  echo "Expected 10 schema fixtures, found $total" >&2
  exit 1
fi

echo "Validated 10 schema fixtures"
