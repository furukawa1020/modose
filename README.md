# MODOSE

> 現実に Ctrl+Z を。

MODOSE は、スマートフォンで保存した卓上の物体配置を、VLMによる物体同一性の理解、ARによる残像と矢印、端末内の追跡と再確認によって元の状態へ戻すAndroidアプリです。

## プロダクト境界

- 物理アクチュエータは使わない
- VLA、OS、コンパイラとは呼ばない
- TypeScriptは使わない
- Androidネイティブを主実装とする
- KotlinはAndroid/ARCore/Firebase接続、Rustは決定論的コア、GoはCloud Run APIを担当する
- VLMは意味理解と最終確認だけに使い、毎フレームの位置追跡には使わない
- 印刷マーカー、専用机、三脚、追加センサーを要求しない
- 保存から復元完了までは同一ARCoreセッション内とする

## 想定デモ

1. その場の机へ1〜5個の日用品を置く
2. 「保存」を押す
3. MODOSEが物体を囲み、ユーザーが対象を確認する
4. 審査員が物を移動・回転・一時的に隠す
5. 「戻す」を押す
6. 元の位置に半透明の残像、現在位置から目標位置へ矢印を表示する
7. 人が物を戻す
8. 全体を再確認し `REALITY RESTORED` を表示する

## リポジトリ構成

```text
modose/
├── apps/
│   └── android/              # Kotlin / Jetpack Compose / ARCore
├── crates/
│   ├── scene-core/           # Rust: 状態・幾何・対応付け・復元手順
│   └── scene-core-jni/       # Rust: Android向けJNI境界
├── services/
│   └── vision-api/           # Go / Cloud Run / Vertex AI
├── api/
│   ├── openapi.yaml
│   └── schemas/
├── fixtures/
│   ├── baseline/
│   ├── compare/
│   └── verify/
├── docs/
│   ├── PRODUCT_REQUIREMENTS.md
│   ├── ARCHITECTURE.md
│   ├── BACKLOG.md
│   └── TEST_MATRIX.md
└── .github/
    ├── ISSUE_TEMPLATE/
    └── PULL_REQUEST_TEMPLATE.md
```

## 実装ルール

- 1 Issue = 1 PR
- PRは原則として生成物とロックファイルを除き400変更行以下
- 1 PRで変更するユーザー可視挙動は1つ
- 新しい依存追加、API契約変更、保存形式変更にはADRまたは既存ADR更新を必須とする
- モデル出力を信用せず、必ずJSON Schema検証と決定論的な後段処理を通す
- `main` に未使用コード、仮画面、モック専用分岐、未追跡TODOを残さない
- 正常系だけでなく、復旧可能な失敗経路までIssueの完了条件に含める

最初に読む文書は `docs/PRODUCT_REQUIREMENTS.md`、実装順は `docs/BACKLOG.md` の依存関係に従います。
