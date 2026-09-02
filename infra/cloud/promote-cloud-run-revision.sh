#!/usr/bin/env bash
set -euo pipefail

required=(GCP_PROJECT_ID GCP_REGION CLOUD_RUN_SERVICE DEPLOYED_REVISION CANDIDATE_TAG)
for name in "${required[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    printf '%s is required\n' "$name" >&2
    exit 2
  fi
done

if [[ ! "$GCP_PROJECT_ID" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]]; then
  echo "GCP_PROJECT_ID is invalid" >&2
  exit 2
fi
if [[ ! "$GCP_REGION" =~ ^[a-z]+-[a-z]+[0-9]+$ ]]; then
  echo "GCP_REGION is invalid" >&2
  exit 2
fi
if [[ ! "$CLOUD_RUN_SERVICE" =~ ^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$ ]]; then
  echo "CLOUD_RUN_SERVICE is invalid" >&2
  exit 2
fi
if [[ ! "$DEPLOYED_REVISION" =~ ^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$ ]]; then
  echo "DEPLOYED_REVISION is invalid" >&2
  exit 2
fi
if [[ ! "$CANDIDATE_TAG" =~ ^[a-z]([a-z0-9-]{0,61}[a-z0-9])?$ ]]; then
  echo "CANDIDATE_TAG is invalid" >&2
  exit 2
fi

gcloud run services update-traffic "$CLOUD_RUN_SERVICE" \
  --project "$GCP_PROJECT_ID" \
  --region "$GCP_REGION" \
  --to-revisions "$DEPLOYED_REVISION=100" \
  --quiet

traffic_json="$(
  gcloud run services describe "$CLOUD_RUN_SERVICE" \
    --project "$GCP_PROJECT_ID" \
    --region "$GCP_REGION" \
    --format json
)"

serving_revision="$(
  jq -er '
    [.status.traffic[] | select(.percent == 100) | .revisionName]
    | unique
    | if length == 1 then .[0] else error("100 percent traffic revision is not unique") end
  ' <<<"$traffic_json"
)"

if [[ "$serving_revision" != "$DEPLOYED_REVISION" ]]; then
  printf 'traffic revision mismatch: got %s, want %s\n' \
    "$serving_revision" "$DEPLOYED_REVISION" >&2
  exit 1
fi

gcloud run services update-traffic "$CLOUD_RUN_SERVICE" \
  --project "$GCP_PROJECT_ID" \
  --region "$GCP_REGION" \
  --remove-tags "$CANDIDATE_TAG" \
  --quiet

printf 'Promoted Cloud Run revision %s to 100%% traffic\n' "$DEPLOYED_REVISION"
