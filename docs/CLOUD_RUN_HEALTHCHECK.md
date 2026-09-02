# Cloud Run health判定

## 目的

Vision APIのデプロイ完了は、Cloud Runがrevisionを作成したことだけでは判定しない。デプロイされたサービスへHTTPで到達し、プロセスが規定のhealth応答を返したことまで確認する。

## エンドポイント

### `GET /healthz`

プロセスのlivenessを確認する。外部サービスへ接続せず、次を返す。

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8

{"status":"ok"}
```

Firebase ID TokenとApp Check Tokenは要求しない。監視とデプロイ検証から利用できる一方、利用者情報、設定値、依存サービス情報は返さない。

### `GET /readyz`

依存関係を含むreadinessを確認する。probeが利用不能な場合は、型付きの`503 Service Unavailable`を返す。

デプロイ直後のHTTP到達確認には、外部依存の一時障害とプロセス障害を混同しないため`/healthz`を使用する。

## デプロイ時の判定

`.github/scripts/smoke-cloud-run.sh`は、publish workflowが取得したservice URLだけを対象にする。

成功条件は次の両方である。

- HTTP statusが`200`
- 改行を除いた応答本文が`{"status":"ok"}`と一致する

404、401、403、5xx、timeout、接続失敗、不正な本文は成功扱いにしない。

## 再試行

Cloud Run revisionの起動直後を考慮し、有限回だけ再試行する。試行回数、待機秒数、通信timeoutは正の整数として検証し、不正値では通信を開始しない。

最後の試行にも失敗した場合、scriptは非ゼロで終了し、publish workflowを失敗させる。無限再試行や失敗の握りつぶしは禁止する。

## 証拠

成功時は、成功した試行番号をGitHub Actionsログへ残す。応答本文はログへ出さず、失敗時はHTTP statusと試行番号だけを記録する。

revision名、image digest、service URLのデプロイ証拠は、health確認が成功した後にのみJob Summaryへ記録する。

## 手動確認

```sh
curl --silent --show-error \
  --max-time 10 \
  "https://SERVICE_URL/healthz"
```

期待値は次の1行である。

```json
{"status":"ok"}
```
