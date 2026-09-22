# Android解析ランタイムの入力

Issue #401。値を埋めるために仮のFirebaseアプリやモデルは使わない。

## 埋め込みモデル

- 公式ガイド: https://developers.google.com/edge/mediapipe/solutions/vision/image_embedder
- 固定配布URL: https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite
- ファイルサイズ: 4117670 bytes
- SHA-256: bbbb4c51a55a53905af1daec995ca1aae355046f8839bb8c9f5ce9271394bc40
- APK内のパス: models/mobilenet_v3_small.tflite
- 上流モデルのライセンス情報: https://www.kaggle.com/models/google/mobilenet-v3/tensorFlow2/small-075-224-feature-vector/1
- 再配布前にモデルライセンス通知・SBOMへの反映を完了する。コード例のライセンスをモデルのライセンスの代わりに扱わない。

prepareEmbedderModelが固定URLから取得し、サイズとハッシュが一致した場合のみ生成assetsへ出力する。
初回ビルドにはネットワークが必要。不一致・取得失敗時に別モデルへフォールバックしない。
MediaPipe実機推論と物体対応精度の検証は別途必要。

## FirebaseとAPI接続先

ビルドに -PmodoseRuntimeConfigDir=<非公開設定ディレクトリの絶対パス> を渡す。
ディレクトリにはdebug/とrelease/を置き、それぞれに次の2ファイルを配置する。

- google-services.json: Firebase Management APIから取得した実アプリの設定。
- modose-runtime.properties: apiBaseUrlに利用するHTTPS APIのoriginを指定する。

debugのpackageNameはcom.modose.app.debug、releaseはcom.modose.app。
設定ファイルは手で捏造せず、stagingプロジェクトmodose-stg-468816366072から取得する。
設定ディレクトリの指定がないビルドは可能だが、撮影解析は設定不足で停止する。

## 残る有効化条件

匿名認証の有効化、実際の署名証明書SHA-256を使ったApp Check登録、Play Integrity設定が必要。
APIの認証・App Check検証を外して動作させない。
Firebaseアプリ登録だけで認証済み・実機疎通済みとは扱わない。
