# Google Cloud bootstrap

## Cloud Shellを開く

[Google Cloud Shellでrepositoryを開く](https://shell.cloud.google.com/?show=terminal&cloudshell_git_repo=https%3A%2F%2Fgithub.com%2Ffurukawa1020%2Fmodose&cloudshell_working_dir=modose)

Cloud Consoleで対象projectを選び、Cloud Shellを起動します。service account keyやGitHub tokenは入力しません。

## Staging

値を実projectへ置き換えて実行します。

```sh
PROJECT_ID=your-staging-project \
REGION=asia-northeast1 \
ENVIRONMENT=staging \
bash infra/cloud/bootstrap-deploy.sh
```

## Production

productionは別projectを選び、同じscriptを実行します。

```sh
PROJECT_ID=your-production-project \
REGION=asia-northeast1 \
ENVIRONMENT=production \
bash infra/cloud/bootstrap-deploy.sh
```

## GitHub Environmentへ設定する

script末尾に表示される次の値を、対応する `staging` または `production` EnvironmentのVariablesへ設定します。

- `GCP_PROJECT_ID`
- `GCP_REGION`
- `AR_REPOSITORY`
- `CLOUD_RUN_SERVICE`
- `WIF_PROVIDER`
- `DEPLOY_SERVICE_ACCOUNT`
- `RUNTIME_SERVICE_ACCOUNT`

利用するmodel IDを `VLM_MODEL_ID` として追加します。credentialやtokenをGitHub Secretへ保存する必要はありません。

production Environmentにはrequired reviewerを設定します。その後、`Cloud identity preflight`、`Publish and deploy Vision API` の順でActionsから実行します。
