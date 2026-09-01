#!/usr/bin/env bash

set -euo pipefail

required=(
  GCP_PROJECT_ID
  GCP_REGION
  AR_REPOSITORY
  CLOUD_RUN_SERVICE
  WIF_PROVIDER
  DEPLOY_SERVICE_ACCOUNT
  VLM_MODEL_ID
)

for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    printf 'required environment variable is missing: %s\n' "$name" >&2
    exit 1
  fi
done

[[ "$GCP_PROJECT_ID" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]]
[[ "$GCP_REGION" =~ ^[a-z]+-[a-z]+[0-9]$ ]]
[[ "$AR_REPOSITORY" =~ ^[a-z][a-z0-9-]{0,62}$ ]]
[[ "$CLOUD_RUN_SERVICE" =~ ^[a-z][a-z0-9-]{0,62}$ ]]
[[ "$WIF_PROVIDER" =~ ^projects/[0-9]+/locations/global/workloadIdentityPools/[a-z0-9-]+/providers/[a-z0-9-]+$ ]]
[[ "$DEPLOY_SERVICE_ACCOUNT" =~ ^[a-z0-9-]+@[a-z][a-z0-9-]{4,28}[a-z0-9]\.iam\.gserviceaccount\.com$ ]]
[[ "$VLM_MODEL_ID" =~ ^[A-Za-z0-9._:/-]{1,128}$ ]]
