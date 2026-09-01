# Vision API

## コンテナをbuildする

repository rootで実行します。

```sh
docker build \
  --file services/vision-api/Dockerfile \
  --tag modose-vision-api:local \
  .
```

build contextはrootの `.dockerignore` で許可リスト化されています。Vision APIのGo sourceとmodule定義以外はbuilderへ送信しません。

## ローカル認証を準備する

Application Default Credentialsを作成し、そのファイルの絶対pathを `ADC_PATH` に設定します。

```sh
gcloud auth application-default login
export ADC_PATH="$HOME/.config/gcloud/application_default_credentials.json"
```

credentialをimageへcopyしないでください。起動時にread-onlyでmountします。

## ローカルで起動する

値は利用するGoogle Cloud project、location、modelへ置き換えます。

```sh
docker run --rm \
  --publish 8080:8080 \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,size=16m \
  --mount "type=bind,src=$ADC_PATH,dst=/var/run/secrets/google/adc.json,readonly" \
  --env APP_ENV=development \
  --env PORT=8080 \
  --env GOOGLE_APPLICATION_CREDENTIALS=/var/run/secrets/google/adc.json \
  --env GOOGLE_CLOUD_PROJECT=your-project-id \
  --env GOOGLE_CLOUD_LOCATION=asia-northeast1 \
  --env VLM_MODEL_ID=your-model-id \
  modose-vision-api:local
```

既定のHTTP設定は次のとおりです。

| 環境変数 | 既定値 |
| --- | --- |
| `HTTP_READ_TIMEOUT` | `5s` |
| `HTTP_WRITE_TIMEOUT` | `15s` |
| `HTTP_IDLE_TIMEOUT` | `60s` |
| `HTTP_SHUTDOWN_TIMEOUT` | `10s` |
| `VLM_DEADLINE` | `12s` |

## 起動確認

container外のhostから確認します。

```sh
curl --fail http://localhost:8080/healthz
curl --fail http://localhost:8080/readyz
```

設定不足またはGoogle Cloud client初期化失敗時、processは起動を拒否します。runtime imageにはshellやGo toolchainを含めません。
