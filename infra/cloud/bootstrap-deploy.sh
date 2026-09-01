#!/usr/bin/env bash

set -euo pipefail

PROJECT_ID="${PROJECT_ID:-}"
REGION="${REGION:-asia-northeast1}"
ENVIRONMENT="${ENVIRONMENT:-}"
GITHUB_REPOSITORY="furukawa1020/modose"
POOL_ID="github-actions"
PROVIDER_ID="modose-${ENVIRONMENT}"
DEPLOY_SA_ID="modose-deploy-${ENVIRONMENT}"
RUNTIME_SA_ID="modose-runtime-${ENVIRONMENT}"
AR_REPOSITORY="modose-${ENVIRONMENT}"
CLOUD_RUN_SERVICE="modose-vision-${ENVIRONMENT}"

if [[ ! "$PROJECT_ID" =~ ^[a-z][a-z0-9-]{4,28}[a-z0-9]$ ]]; then
  echo "PROJECT_ID is invalid" >&2
  exit 1
fi
if [[ ! "$REGION" =~ ^[a-z]+-[a-z]+[0-9]$ ]]; then
  echo "REGION is invalid" >&2
  exit 1
fi
if [[ ! "$ENVIRONMENT" =~ ^(staging|production)$ ]]; then
  echo "ENVIRONMENT must be staging or production" >&2
  exit 1
fi

gcloud config set project "$PROJECT_ID" >/dev/null
project_number="$(
  gcloud projects describe "$PROJECT_ID" --format='value(projectNumber)'
)"
[[ "$project_number" =~ ^[0-9]+$ ]]

gcloud services enable   artifactregistry.googleapis.com   iamcredentials.googleapis.com   sts.googleapis.com   run.googleapis.com   aiplatform.googleapis.com   firestore.googleapis.com   monitoring.googleapis.com   --project "$PROJECT_ID"

deploy_sa="$DEPLOY_SA_ID@$PROJECT_ID.iam.gserviceaccount.com"
runtime_sa="$RUNTIME_SA_ID@$PROJECT_ID.iam.gserviceaccount.com"

if ! gcloud iam service-accounts describe "$deploy_sa"   --project "$PROJECT_ID" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$DEPLOY_SA_ID"     --project "$PROJECT_ID"     --display-name "MODOSE ${ENVIRONMENT} deploy"
fi

if ! gcloud iam service-accounts describe "$runtime_sa"   --project "$PROJECT_ID" >/dev/null 2>&1; then
  gcloud iam service-accounts create "$RUNTIME_SA_ID"     --project "$PROJECT_ID"     --display-name "MODOSE ${ENVIRONMENT} runtime"
fi

if ! gcloud artifacts repositories describe "$AR_REPOSITORY"   --project "$PROJECT_ID"   --location "$REGION" >/dev/null 2>&1; then
  gcloud artifacts repositories create "$AR_REPOSITORY"     --project "$PROJECT_ID"     --location "$REGION"     --repository-format docker     --description "MODOSE Vision API ${ENVIRONMENT} images"
fi

if ! gcloud iam workload-identity-pools describe "$POOL_ID"   --project "$PROJECT_ID"   --location global >/dev/null 2>&1; then
  gcloud iam workload-identity-pools create "$POOL_ID"     --project "$PROJECT_ID"     --location global     --display-name "GitHub Actions"
fi

if ! gcloud iam workload-identity-pools providers describe "$PROVIDER_ID"   --project "$PROJECT_ID"   --location global   --workload-identity-pool "$POOL_ID" >/dev/null 2>&1; then
  gcloud iam workload-identity-pools providers create-oidc "$PROVIDER_ID"     --project "$PROJECT_ID"     --location global     --workload-identity-pool "$POOL_ID"     --display-name "MODOSE ${ENVIRONMENT}"     --issuer-uri "https://token.actions.githubusercontent.com/"     --attribute-mapping "google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref"     --attribute-condition "assertion.repository=='$GITHUB_REPOSITORY' && assertion.ref=='refs/heads/main'"
fi

principal="principalSet://iam.googleapis.com/projects/$project_number/locations/global/workloadIdentityPools/$POOL_ID/attribute.repository/$GITHUB_REPOSITORY"

gcloud iam service-accounts add-iam-policy-binding "$deploy_sa"   --project "$PROJECT_ID"   --member "$principal"   --role roles/iam.workloadIdentityUser >/dev/null

gcloud artifacts repositories add-iam-policy-binding "$AR_REPOSITORY"   --project "$PROJECT_ID"   --location "$REGION"   --member "serviceAccount:$deploy_sa"   --role roles/artifactregistry.writer >/dev/null

gcloud projects add-iam-policy-binding "$PROJECT_ID"   --member "serviceAccount:$deploy_sa"   --role roles/run.admin >/dev/null

gcloud iam service-accounts add-iam-policy-binding "$runtime_sa"   --project "$PROJECT_ID"   --member "serviceAccount:$deploy_sa"   --role roles/iam.serviceAccountUser >/dev/null

for role in   roles/aiplatform.user   roles/datastore.user   roles/monitoring.metricWriter; do
  gcloud projects add-iam-policy-binding "$PROJECT_ID"     --member "serviceAccount:$runtime_sa"     --role "$role" >/dev/null
done

provider_name="$(
  gcloud iam workload-identity-pools providers describe "$PROVIDER_ID"     --project "$PROJECT_ID"     --location global     --workload-identity-pool "$POOL_ID"     --format='value(name)'
)"

cat <<EOF
GCP_PROJECT_ID=$PROJECT_ID
GCP_REGION=$REGION
AR_REPOSITORY=$AR_REPOSITORY
CLOUD_RUN_SERVICE=$CLOUD_RUN_SERVICE
WIF_PROVIDER=$provider_name
DEPLOY_SERVICE_ACCOUNT=$deploy_sa
RUNTIME_SERVICE_ACCOUNT=$runtime_sa
EOF
