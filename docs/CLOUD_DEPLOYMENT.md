# Vision API cloud deployment

## GitHub Environments

repository settingsで `staging` と `production` の2つを作成します。各Environmentへ次のVariablesを設定します。

| Variable | 内容 |
| --- | --- |
| `GCP_PROJECT_ID` | 対象Google Cloud project ID |
| `GCP_REGION` | Artifact RegistryとCloud Runのregion |
| `AR_REPOSITORY` | Artifact Registry repository名 |
| `CLOUD_RUN_SERVICE` | Cloud Run service名 |
| `WIF_PROVIDER` | Workload Identity Providerの完全resource名 |
| `DEPLOY_SERVICE_ACCOUNT` | deploy用service account email |
| `VLM_MODEL_ID` | runtimeで利用するVertex AI model ID |

値は `.github/scripts/validate-cloud-environment.sh` の許可形式を満たす必要があります。credential、access token、service account keyはVariableやSecretへ保存しません。

## Production protection

`production` Environmentにはrequired reviewerを設定します。workflow jobはEnvironment承認後に開始されるため、承認前にはcheckout、OIDC token発行、image push、Cloud Run deployのいずれも実行されません。

`staging` と `production` でproject、registry、service account、Cloud Run serviceを共有しないでください。

## Google Cloud IAM

GitHub repositoryのOIDC principalからdeploy service accountへの `roles/iam.workloadIdentityUser` を許可します。attribute conditionで対象repositoryとbranchを制限します。

deploy service accountには必要なresourceだけを対象として次を付与します。

- Artifact Registry repositoryへの `roles/artifactregistry.writer`
- 対象Cloud Run serviceへのdeployに必要な `roles/run.admin`
- Cloud Run runtime service accountへの `roles/iam.serviceAccountUser`

project全体のOwner、Editor、service account key作成権限は付与しません。

## Identity preflight

Environment設定後、Actionsの `Cloud identity preflight` を手動実行します。指定service accountとの一致と短期access token発行が成功するまでdeploy workflowを実行しません。

## Deploy

Actionsの `Publish and deploy Vision API` を手動実行し、対象Environmentを選択します。

workflowは次の順序で処理します。

1. Environment contract検証
2. WIF短期認証
3. commit SHA tagでimage buildとpush
4. registry digest取得
5. digest固定でCloud Run deploy
6. latest ready revisionのimage digest再検証
7. job summaryへdeploy証拠を記録

失敗したrunを再実行する場合も同じcommitから開始します。既存revisionの削除やtraffic rollbackはこのworkflowでは行いません。
