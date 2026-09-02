# Cloud Run revision昇格手順

## 原則

Vision APIの新revisionは、デプロイ完了だけでは利用者trafficへ切り替えない。候補revision固有URLのhealth確認が成功した場合だけ、revision名を固定して100% trafficへ昇格する。

## 状態遷移

```text
image digest確定
-> no-trafficでcandidate revision作成
-> candidate固有URL取得
-> image digest一致確認
-> candidate /healthz確認
-> revision名固定で100% trafficへ昇格
-> traffic実状態の一致確認
-> candidate tag削除
-> デプロイ証拠を記録
```

順序を入れ替えてはならない。

## 候補revision

publish workflowは、次の条件で新revisionを作成する。

- imageはArtifact Registryのdigestで固定する
- `--no-traffic`を指定する
- GitHub Actions run IDから一意なcandidate tagを付与する
- Runtime Service Accountを明示する
- stagingとproductionの環境変数を分離する

smoke testは通常のservice URLではなく、candidate tagに対応する固有URLへ送信する。通常URLを使用すると旧revisionの成功を新revisionの成功と誤判定するため禁止する。

## 昇格条件

次のすべてが成功した場合だけ昇格する。

- Cloud RunがrevisionをReadyとして返す
- 配備revisionのimageが要求したdigestと一致する
- candidate URLがHTTPS URLとして取得できる
- `GET /healthz`がHTTP 200を返す
- 応答本文が`{"status":"ok"}`と一致する

昇格は`LATEST`ではなくrevision名を指定する。並行デプロイや将来のrevision追加によって対象が変わらないようにする。

## 昇格後の検証

traffic更新後、Cloud Runの実状態をJSONで取得する。100% trafficを受けるrevision名を重複排除し、対象revisionだけに一意に解決されることを確認する。

一致確認後にcandidate tagを削除する。デプロイ証拠はこの処理が完了してからJob Summaryへ記録する。

## 失敗時の挙動

| 失敗点 | 挙動 |
| --- | --- |
| image buildまたはpush | revisionを作成しない |
| no-traffic deploy | 既存trafficを変更しない |
| candidate URL取得 | 昇格しない |
| digest不一致 | 昇格しない |
| health失敗 | 昇格しない |
| traffic更新失敗 | workflowを失敗させる |
| traffic一致検証失敗 | 成功扱いにしない |
| tag削除失敗 | 証拠記録へ進まない |

health失敗時に既存revisionを再デプロイする必要はない。trafficを変更していないため、既存revisionがそのまま利用者リクエストを処理する。

## 手動復旧

traffic更新後の検証で失敗した場合は、Cloud Runのtraffic実状態を確認し、直前に正常だったrevision名へ明示的に戻す。`LATEST`は使用しない。

```sh
gcloud run services update-traffic "$CLOUD_RUN_SERVICE" \
  --project "$GCP_PROJECT_ID" \
  --region "$GCP_REGION" \
  --to-revisions "KNOWN_GOOD_REVISION=100"
```

復旧したrevision名、image digest、実行者、時刻、原因をIssueへ記録する。
