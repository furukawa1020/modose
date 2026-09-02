# Cloud Runの公開境界

## 結論

MODOSE Vision APIのCloud Runサービスは、`allUsers`へ`roles/run.invoker`を付与してHTTPリクエストの到達を許可する。

これはAPIを無認証にする設定ではない。Cloud Run IAMとアプリケーション認証の責務を分離するための設定である。

## 認証境界

Cloud Run IAMは、Androidアプリが送信するFirebase ID TokenやApp Check Tokenを検証しない。Cloud Run IAMで未認証呼び出しを拒否すると、リクエストはVision APIへ到達せず、アプリケーション側の検証を実行できない。

Vision APIへ到達したリクエストは、次の両方をアプリケーション内で検証する。

- Firebase ID Token: 利用者セッションの検証
- Firebase App Check Token: 正規アプリ・端末由来であることの検証

いずれかが欠落、不正、期限切れの場合は、保護対象APIを成功させない。

## デプロイ契約

`.github/workflows/publish-vision-api.yml`は、`gcloud run deploy`へ`--allow-unauthenticated`を指定する。

stagingとproductionで同じ公開境界を使用し、環境ごとのFirebase/App Check設定とサービスアカウントはGitHub Environmentで分離する。

## 疎通結果の判別

| 応答 | 意味 |
| --- | --- |
| Cloud Run由来の`403 Forbidden` | Cloud Run IAMで拒否され、APIへ到達していない |
| API形式の`401`または`403` | APIへ到達し、アプリケーション認証で拒否された |
| API形式の`404 not_found` | APIへ到達したが、指定ルートが存在しない |
| `2xx` | 対象APIの認証と処理が成功した |

ルート`/`はヘルスチェックではない。未定義の場合の型付き`404`は異常ではない。

## IAM確認

```sh
gcloud run services get-iam-policy "$CLOUD_RUN_SERVICE" \
  --project "$GCP_PROJECT_ID" \
  --region "$GCP_REGION"
```

出力に次のbindingが含まれることを確認する。

```yaml
members:
  - allUsers
role: roles/run.invoker
```

## 失敗時の対応

デプロイ後にCloud Run由来の`403`へ戻った場合、手動変更だけで完了扱いにしない。デプロイworkflowの実行結果とIAMポリシーを確認し、再現可能な設定として修正する。

Firebase AuthenticationまたはApp Checkの検証失敗を回避するために、検証処理を無効化してはならない。
