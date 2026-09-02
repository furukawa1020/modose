#!/usr/bin/env bash
set -euo pipefail

: "${SERVICE_URL:?SERVICE_URL is required}"

max_attempts="${SMOKE_MAX_ATTEMPTS:-6}"
interval_seconds="${SMOKE_INTERVAL_SECONDS:-5}"
request_timeout_seconds="${SMOKE_REQUEST_TIMEOUT_SECONDS:-10}"

if [[ ! "$SERVICE_URL" =~ ^https://[^/?#]+/?$ ]]; then
  echo "SERVICE_URL must be an HTTPS origin" >&2
  exit 2
fi
if [[ ! "$max_attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "SMOKE_MAX_ATTEMPTS must be a positive integer" >&2
  exit 2
fi
if [[ ! "$interval_seconds" =~ ^[0-9]+$ ]]; then
  echo "SMOKE_INTERVAL_SECONDS must be a non-negative integer" >&2
  exit 2
fi
if [[ ! "$request_timeout_seconds" =~ ^[1-9][0-9]*$ ]]; then
  echo "SMOKE_REQUEST_TIMEOUT_SECONDS must be a positive integer" >&2
  exit 2
fi

health_url="${SERVICE_URL%/}/healthz"
response_file="$(mktemp)"
trap 'rm -f "$response_file"' EXIT

for ((attempt = 1; attempt <= max_attempts; attempt++)); do
  status="000"
  if status="$(curl     --silent     --show-error     --output "$response_file"     --write-out '%{http_code}'     --max-time "$request_timeout_seconds"     "$health_url")"; then
    :
  else
    status="000"
  fi

  body="$(tr -d '\r\n' < "$response_file")"
  if [[ "$status" == "200" && "$body" == '{"status":"ok"}' ]]; then
    echo "Cloud Run health smoke test succeeded on attempt $attempt"
    exit 0
  fi

  echo "Cloud Run health smoke test attempt $attempt/$max_attempts failed with HTTP $status" >&2
  if ((attempt < max_attempts)); then
    sleep "$interval_seconds"
  fi
done

echo "Cloud Run health smoke test failed after $max_attempts attempts" >&2
exit 1
