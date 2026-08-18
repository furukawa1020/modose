# MODOSE Issue / PR Backlog

## 運用規則

- 下表の1行が1 Issueかつ1 PR
- Issue番号はタイトル先頭ではなくPR末尾へ `[M-###]` と付ける
- branch: `m-###-short-slug`
- PR title: `<type>(<area>): <imperative> [M-###]`
- squash merge
- PRは原則として生成物・ロックファイルを除き400変更行以下
- 400行を超える場合は、Issue本文に分割不能理由を書く
- 1 PRでユーザー可視挙動を2つ以上変更しない
- リファクタリングを機能PRへ混ぜない
- 依存Issueが未mergeのまま実装する場合はstacked PRとしてbase branchを明記する
- `main`へ未使用コード、仮画面、無効化テスト、未追跡TODOを入れない
- 各PRは正常系、失敗系、証拠の3点を持つ

## ラベル

- `area:android`
- `area:ar`
- `area:rust`
- `area:vision`
- `area:api`
- `area:vertex`
- `area:data`
- `area:infra`
- `area:security`
- `area:test`
- `area:docs`
- `type:feature`
- `type:test`
- `type:chore`
- `type:docs`
- `risk:low`
- `risk:medium`
- `risk:high`
- `blocked`

## Definition of Done

- IssueのAcceptanceをすべて満たす
- 失敗経路が実装される
- テストが追加される
- PII、画像、物体ラベル、prompt、tokenをログしない
- 公開API・保存形式変更はschemaとfixtureを更新する
- Resource close/dispose経路をレビューする
- Release相当buildで必要証拠を取得する
- PR descriptionにrollbackを記載する
- 会話・review threadをすべて解決する

## Backlog

| ID | PR title | Depends on | Risk | Acceptance |
|---|---|---|---|---|
| M-001 | `docs(product): freeze supported scene contract` | none | low | 対応環境・対応物体・非対応・同一セッション境界をPRDへ固定し、レビューで曖昧語が残っていない。 |
| M-002 | `docs(architecture): record core ADRs` | M-001 | low | Android/Kotlin、Rust/JNI、Go/Cloud Run、VLM境界、local-first、no TypeScriptのADRを追加する。 |
| M-003 | `chore(repo): initialize monorepo toolchains` | M-002 | low | Android、Rust、Go、api、fixturesのディレクトリと固定ツールチェーンファイルを追加し、空ビルドが通る。 |
| M-004 | `chore(github): add issue and PR governance` | M-003 | low | Issue template、PR template、CODEOWNERS、branch命名、squash merge規則を追加する。 |
| M-005 | `ci(repo): add path-scoped baseline checks` | M-003 | medium | Android/Rust/Go/OpenAPIの変更パスごとに必要なチェックだけが起動し、main保護に使える。 |
| M-006 | `contract(api): add OpenAPI 3.1 skeleton` | M-003 | medium | healthz、readyz、baseline、compare、verify、metadata、deleteを定義し、lintが通る。 |
| M-007 | `contract(schema): add vision JSON schemas` | M-006 | medium | BaselineAnalysis、SceneComparison、SceneVerificationのenum・null・versionを厳密に定義する。 |
| M-008 | `test(fixtures): add golden fixture harness` | M-007 | medium | valid/invalid/old-version fixtureを同じコマンドで検証でき、CIで破壊的変更を検出する。 |
| M-009 | `feat(android): create app shell and dependency graph` | M-003 | medium | Compose起動、アプリ内DI、build variants、空のsingle-activityがReleaseで起動する。 |
| M-010 | `feat(firebase): bootstrap anonymous auth` | M-009 | medium | 初回匿名認証、再起動時再利用、失敗時の明示状態を実装し、tokenをログしない。 |
| M-011 | `feat(firebase): attach App Check tokens` | M-010 | high | Debug providerとPlay Integrity providerをbuild variantで分離し、HTTP clientへtokenを付与する。 |
| M-012 | `feat(android): enforce camera permission flow` | M-009 | medium | 許可・拒否・恒久拒否・設定復帰を処理し、権限なしでAR sessionを作らない。 |
| M-013 | `feat(ar): verify ARCore availability and install` | M-012 | medium | ARCore対応、未導入、古い、利用不能を区別し、正しい画面遷移を行う。 |
| M-014 | `feat(ar): implement session lifecycle adapter` | M-013 | high | resume/pause/close、Activity lifecycle、例外、session一重所有を実装する。 |
| M-015 | `feat(ar): render camera background` | M-014 | high | ARCore GPU textureをOpenGL ESで縦画面へ正しいアスペクトで描画する。 |
| M-016 | `feat(ui): add camera overlay host` | M-015 | medium | GLSurfaceView上にCompose controlsを重ね、タッチとライフサイクルが競合しない。 |
| M-017 | `feat(ar): expose tracking diagnostics` | M-014 | medium | TrackingStateとFailureReasonを型付き状態へ変換し、insufficient light/features/motion/cameraを識別する。 |
| M-018 | `feat(ar): detect and select horizontal plane` | M-014 | high | 水平Planeだけを候補にし、中央ROIに対応する面を選び、見失いを通知する。 |
| M-019 | `feat(ar): create scene anchor coordinate frame` | M-018 | high | ROI中心へAnchorを作り、TableM↔WorldM変換を提供し、detach責務をテストする。 |
| M-020 | `feat(ar): acquire CPU camera image safely` | M-014 | high | NotYetAvailableとresource exhaustionを処理し、全経路でImageをcloseする。 |
| M-021 | `feat(ar): select middle-resolution camera config` | M-014 | medium | 背面・30FPS優先・中解像度CPU streamを選び、未対応時VGAへフォールバックする。 |
| M-022 | `feat(image): implement image/view coordinate transform` | M-020 | high | IMAGE_PIXELS↔VIEWの変換を端末回転込みで実装し、fixture座標で往復誤差を検証する。 |
| M-023 | `feat(image): crop and encode VLM image` | M-020,M-022 | high | ROI crop、正立、EXIF除去、長辺1600、JPEG82、2MB制限を実装する。 |
| M-024 | `feat(render): add world-space marker primitive` | M-019 | high | TableM上の点・リングをWorldへ描画し、カメラ移動後も同じ場所に留まる。 |
| M-025 | `feat(render): add ghost target primitive` | M-024 | high | 保存cropを28%不透明度でAnchor上へ描画し、tracking停止時に隠す。 |
| M-026 | `feat(render): add move arrow primitive` | M-024 | high | from/to TableMから矢印を描画し、距離更新で長さが変わり、3cm以内でリングになる。 |
| M-027 | `feat(render): add rotation guidance primitive` | M-024 | medium | 時計・反時計回りの弧と角度ラベルを表示し、対称物体では非表示にできる。 |
| M-028 | `feat(android): add haptic and success sound service` | M-009 | low | 局所完了と全体完了のパターンを分離し、同一イベントで多重発火しない。 |
| M-029 | `feat(rust): initialize scene-core crate` | M-003 | low | Android非依存crate、strict clippy、serde feature、unit test skeletonを追加する。 |
| M-030 | `feat(rust): define scene domain models` | M-029,M-007 | medium | SceneSnapshot、SceneObject、Observation、RestoreState、ReasonCodeをschemaと対応させる。 |
| M-031 | `feat(rust): define coordinate and unit types` | M-029 | medium | ImagePx/ViewPx/WorldM/TableMをnewtype化し、単位混同をコンパイル時に防ぐ。 |
| M-032 | `feat(rust): implement ray-plane intersection` | M-031 | high | 平行・背面・面外・正常交点を処理し、数値誤差テストを通す。 |
| M-033 | `feat(rust): implement pose transform utilities` | M-031 | high | Anchor poseとCamera poseの合成・逆変換・round tripテストを実装する。 |
| M-034 | `feat(rust): implement pair scoring` | M-030 | medium | VLM/embedding/signature重み、欠損値、スコア正規化を純粋関数で実装する。 |
| M-035 | `feat(rust): implement Hungarian assignment` | M-034 | high | dummy missing nodeを含む一対一割当てを実装し、greedy失敗fixtureを通す。 |
| M-036 | `feat(rust): classify ambiguous matches` | M-035 | medium | 0.78/0.62/0.08閾値を適用し、accepted/ambiguous/rejectedを返す。 |
| M-037 | `feat(rust): classify object restore state` | M-030,M-033 | medium | 距離・tracking・match・VLM stateからObjectRestoreStateを決定する。 |
| M-038 | `feat(rust): implement guidance priority planner` | M-037 | medium | 遮蔽、確信度、距離、missing/ambiguousを固定ルールで順序付けする。 |
| M-039 | `feat(rust): implement restoration state machine` | M-037 | high | 全状態遷移と禁止遷移を実装し、成功状態へ抜け道がない。 |
| M-040 | `feat(rust): add alignment hysteresis` | M-037 | medium | 3cm enter/5cm exit、800ms stable、tracking loss resetを実装する。 |
| M-041 | `feat(rust): add versioned serialization` | M-030 | medium | schemaVersion付きJSON、未知version拒否、既知minor互換を実装する。 |
| M-042 | `feat(jni): expose narrow scene-core ABI` | M-030,M-039 | high | byte array/primitive中心のJNI関数、panic捕捉、型付きerror codeを提供する。 |
| M-043 | `feat(android): wrap scene-core JNI` | M-042,M-009 | high | Kotlin wrapperがnative lifecycleとerror mappingを一箇所で管理し、instrumentation testが通る。 |
| M-044 | `test(rust): add property and replay tests` | M-032,M-035,M-039,M-041 | medium | proptestとfixture replayで変換、割当て、状態機械の不変条件を検証する。 |
| M-045 | `feat(vision): implement capture quality assessor` | M-020 | medium | 暗さ、白飛び、blur、motion、tracking、ROIを統合しCaptureQualityを返す。 |
| M-046 | `feat(vision): add ML Kit static multi-object detector` | M-020 | medium | 1枚画像で最大5枠を取得し、結果なし・モデル未DL・例外を型付き処理する。 |
| M-047 | `feat(vision): add ML Kit stream tracker` | M-020 | high | stream modeでtracking IDとbboxを供給し、backpressureで古いframeを捨てる。 |
| M-048 | `feat(vision): associate VLM boxes with trackers` | M-046,M-047,M-034 | high | IoU+embedding入力をRust scoringへ渡し、sceneObjectIdとtracking IDを結ぶ。 |
| M-049 | `feat(vision): add MediaPipe image embedder` | M-020 | high | 保存cropとcurrent cropを埋め込みへ変換し、cosine similarityを取得する。 |
| M-050 | `feat(vision): normalize and cache object crops` | M-023,M-049 | medium | crop余白、resize、背景影響を統一し、sceneObjectId単位で端末内cacheする。 |
| M-051 | `feat(vision): reassociate lost tracking IDs` | M-047,M-049,M-036 | high | ID喪失後にembedding 0.82以上のみ再同定し、競合時はambiguousへ戻す。 |
| M-052 | `feat(vision): detect motion stop` | M-047 | medium | bbox速度と端末角速度から800ms静止を検出し、検証トリガーを一度だけ出す。 |
| M-053 | `feat(vision): project tracked boxes to table` | M-022,M-032,M-043,M-047 | high | bbox代表点をTableMへ変換し、面外・平行・tracking lossを除外する。 |
| M-054 | `feat(vision): aggregate current scene observation` | M-048,M-051,M-053 | high | tracking、embedding、TableM、confidenceを1フレームのObservationへまとめる。 |
| M-055 | `feat(api): scaffold Go vision service` | M-003,M-006 | medium | 設定、router、graceful shutdown、healthz、readyz、typed errorsを実装する。 |
| M-056 | `feat(api): verify Firebase ID tokens` | M-055,M-010 | high | Bearer token検証、uid抽出、期限・audience不正を401へ変換する。 |
| M-057 | `feat(api): verify Firebase App Check tokens` | M-055,M-011 | high | App Check tokenを検証し、未登録・期限切れ・debug誤用を403にする。 |
| M-058 | `feat(api): enforce upload limits` | M-055 | high | Content-Length、MIME、magic bytes、decode後画素数、part数を検証する。 |
| M-059 | `feat(api): add request id and idempotency` | M-055 | high | UUIDv7 requestId、Idempotency-Key cache、同一key異payloadの409を実装する。 |
| M-060 | `feat(vertex): create Gen AI client adapter` | M-055 | high | ADC、project/location/model env、deadline、model response metadataを隠蔽する。 |
| M-061 | `feat(vertex): implement baseline prompt and schema` | M-060,M-007 | high | 移動物体1〜5、hands、tooMany、0〜1000 bboxをschema拘束で返す。 |
| M-062 | `feat(vertex): implement compare prompt and schema` | M-060,M-007 | high | baseline/current/confirmed objectsからstate、match、extraをschema拘束で返す。 |
| M-063 | `feat(vertex): implement final verify prompt` | M-060,M-007 | high | 物体単位verified/needs_correction/uncertainとreasonCodesを返す。 |
| M-064 | `feat(vertex): validate and retry structured output` | M-061,M-062,M-063 | high | JSON/schema/version検証、1回だけ修復再試行、自由文fallback禁止を実装する。 |
| M-065 | `feat(api): expose baseline endpoint` | M-056,M-057,M-058,M-059,M-061,M-064 | high | multipart入力をBaselineAnalysisへ変換し、全定義済みHTTP errorを返す。 |
| M-066 | `feat(api): expose compare endpoint` | M-056,M-057,M-058,M-059,M-062,M-064 | high | 2画像とobject listをSceneComparisonへ変換し、idempotentに返す。 |
| M-067 | `feat(api): expose verify endpoint` | M-056,M-057,M-058,M-059,M-063,M-064 | high | 最終画像をSceneVerificationへ変換し、uncertainを成功へ変換しない。 |
| M-068 | `feat(data): add Firestore metadata repository` | M-055 | high | 許可項目だけを書き、label/image/signature/promptを型として受け取らない。 |
| M-069 | `feat(api): add metadata and delete endpoints` | M-056,M-057,M-068 | medium | 本人uid配下だけへ書込み・削除し、削除をidempotentにする。 |
| M-070 | `feat(obs): add privacy-safe logs and metrics` | M-055 | high | latency/model/schema/errorのみ記録し、禁止キーをテストで検出する。 |
| M-071 | `infra(cloud): containerize vision service` | M-055 | medium | non-root、distroless相当、healthcheck、SBOM、ローカル起動を実装する。 |
| M-072 | `ci(cloud): deploy with workload identity` | M-071,M-005 | high | 長期鍵なしでArtifact RegistryとCloud Runへdeployし、環境分離する。 |
| M-073 | `feat(ui): implement capture quality screen` | M-016,M-017,M-018,M-045 | medium | 保存可否、理由、緑状態、保存ボタンをReducer stateから表示する。 |
| M-074 | `feat(network): add authenticated API client` | M-011,M-006 | high | ID token/App Check/idempotency/version headers、timeout、retry policyを実装する。 |
| M-075 | `feat(flow): analyze baseline capture` | M-023,M-065,M-074 | high | CAPTURING→ANALYZING→REVIEWINGを一ユースケースで実装し、重複押下を防ぐ。 |
| M-076 | `feat(ui): review detected objects` | M-075 | high | 番号枠、名称、除外、1個の手動矩形追加、確定条件を実装する。 |
| M-077 | `feat(data): persist SceneSnapshot locally` | M-030,M-041,M-043,M-076 | high | Room transactionと画像file commitをアトミック化し、途中失敗を残さない。 |
| M-078 | `feat(flow): commit saved scene and anchor` | M-019,M-077 | high | SceneSnapshotとAnchor lifecycleを結び、WAITING_FOR_CHANGEへ一度だけ遷移する。 |
| M-079 | `feat(ui): add move-objects intermission` | M-078 | low | 保存物体サムネイル、戻すボタン、誤リセット防止を実装する。 |
| M-080 | `feat(flow): capture and compare current scene` | M-023,M-066,M-074,M-079 | high | 現在画像取得、compare request、schema mapping、GUIDING遷移を実装する。 |
| M-081 | `feat(flow): initialize local trackers from comparison` | M-048,M-050,M-080 | high | current bboxとtracking IDを関連付け、失敗物体を静的ガイドへ分離する。 |
| M-082 | `feat(flow): orchestrate one-object guidance` | M-038,M-039,M-043,M-081 | high | plannerの先頭1件だけを表示し、完了で次へ進む。 |
| M-083 | `feat(flow): update move guidance per frame` | M-026,M-040,M-052,M-053,M-082 | high | 15Hz以上で矢印と距離を更新し、3cm/5cmヒステリシスを適用する。 |
| M-084 | `feat(flow): show rotation guidance` | M-027,M-080,M-082 | medium | orientationMeaningfulかつ差分ありの場合だけ静的回転指示を表示する。 |
| M-085 | `feat(ui): handle missing and ambiguous objects` | M-036,M-080,M-082 | high | 架空位置を出さず、サムネイル・reason code・再検出導線を表示する。 |
| M-086 | `feat(flow): trigger final verification` | M-052,M-067,M-074,M-083 | high | 全局所完了後だけ最終画像を取得し、最大3回制限を守る。 |
| M-087 | `feat(flow): apply verification corrections` | M-039,M-086 | high | needs_correction物体だけをNEEDS_CORRECTIONへ戻し、plannerを再実行する。 |
| M-088 | `feat(ui): show restored completion` | M-028,M-087 | medium | 全物体verified時のみREALITY RESTORED、ハプティクス、リセットを表示する。 |
| M-089 | `feat(flow): implement typed recovery matrix` | M-017,M-039,M-074,M-080 | high | 全エラーコードを再試行先へ対応付け、内部本文をUIへ出さない。 |
| M-090 | `feat(demo): add one-tap clean reset` | M-077,M-088 | medium | 端末内scene、anchor、trackers、UI stateを1操作で初期化し、Firestore削除を非同期実行する。 |
| M-091 | `feat(obs): add Crashlytics and no-PII analytics` | M-010,M-070 | high | 許可イベントだけを送信し、禁止プロパティの静的テストを追加する。 |
| M-092 | `test(api): add contract and adversarial tests` | M-064,M-065,M-066,M-067 | high | invalid JSON、unknown enum、prompt injection画像説明、oversize、authを自動化する。 |
| M-093 | `test(android): add reducer and adapter tests` | M-073,M-089 | medium | 状態遷移、権限、tracking reason、API mapping、Room rollbackを自動化する。 |
| M-094 | `test(ar): add recorded-session replay harness` | M-020,M-022,M-053 | high | 記録frame/Poseで座標投影とガイド更新を端末非依存に再生できる。 |
| M-095 | `test(e2e): build physical run recorder` | M-088,M-091 | medium | sceneId、latency、model、結果、動画名、位置誤差を1実行ごとに保存する。 |
| M-096 | `test(e2e): execute required 60-run matrix` | M-095 | high | TEST_MATRIX必須60回を完了し、合格閾値と失敗fixtureを記録する。 |
| M-097 | `perf(android): enforce frame and memory budgets` | M-083,M-096 | high | Release buildでFPS、メモリ、JNI、画像処理を計測し、予算超過を解消する。 |
| M-098 | `security(repo): add secret, license, and SBOM gates` | M-005,M-071 | medium | secret scan、cargo deny、govulncheck、dependency license allowlist、SBOMをCIへ入れる。 |
| M-099 | `test(privacy): verify image and metadata deletion` | M-069,M-077,M-090 | high | 端末・Firestore・ログに禁止データが残らず、失敗削除が再試行される。 |
| M-100 | `feat(a11y): finalize accessibility and localization` | M-088 | medium | 48dp、TalkBack、色以外の状態、reduce motion、日本語resource分離を検証する。 |
| M-101 | `release(android): configure signing and App Distribution` | M-072,M-091,M-098 | high | 鍵をリポジトリへ置かず、Release buildをFirebase App Distributionへ配布する。 |
| M-102 | `docs(demo): lock operator script and fallback behavior` | M-096,M-101 | low | 20秒説明、対象物制約、失敗時の一言、予備端末手順を1枚に固定する。 |

## 最初のマージ依存列

```text
M-001
  └─ M-002
      └─ M-003
          ├─ M-004
          ├─ M-005
          ├─ M-006 ─ M-007 ─ M-008
          ├─ M-009 ─ M-012 ─ M-013 ─ M-014
          ├─ M-029 ─ M-030
          └─ M-055
```

縦に最初の体験を通す依存列:

```text
M-014 AR session
→ M-018 plane
→ M-019 anchor
→ M-020 CPU image
→ M-022 coordinates
→ M-023 VLM image
→ M-061 baseline prompt
→ M-065 baseline endpoint
→ M-074 API client
→ M-075 baseline flow
→ M-076 review
→ M-077 persistence
→ M-080 compare
→ M-081 tracker init
→ M-082 guidance
→ M-083 live arrow
→ M-086 verify
→ M-088 completion
```

この列も各PRは独立した完成状態を保ち、途中の仮UIやハードコードを`main`へ入れない。
