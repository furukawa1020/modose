# MODOSE テストマトリクス

## 1. テストの原則

- 机上で成功した一例を完成としない
- VLM、ARCore、端末内追跡、ネットワークを別々に故障させる
- 成功率だけでなく偽成功を最重要指標とする
- 物理テストは動画と結果JSONを残し、再現可能なfixtureへ変換する
- Debug buildではなくRelease相当buildで性能を測る

---

## 2. 参照端末

必須:
- Pixel 8
- 最新のGoogle Play Services for AR
- 縦画面
- 通常バッテリーモード

追加:
- ARCore対応の中価格帯Android端末1台
- Depth非対応ARCore端末1台

---

## 3. 物体セット

### Set A: 日常
- 黒いペン
- 鍵
- 財布
- カード
- カップ

### Set B: 開発机
- USBメモリ
- マウス
- ノート
- イヤホンケース
- 消しゴム

### Set C: 会場即興
- 審査員または観客がその場で出した1〜5個
- 同一製品の重複は除外
- 透明・鏡面・布は除外

### Set D: 境界
- 小さい物体 3〜5 cm
- 細長い物体
- 円形で向きの無い物体
- 互いに色が近いが形が異なる物体
- 1個が別物体へ部分的に重なる構成

---

## 4. 面

- 木目
- 白色無地
- 黒色無地
- 模様あり
- 光沢の弱い会議机

無地面でARCoreの特徴不足になる場合は、対象物体と周辺背景を含めた追跡品質ゲートが保存を拒否することを正とする。

---

## 5. 照明

- 通常室内
- 明るい室内
- 部分的な影
- 低照度
- 逆光

低照度・強逆光は成功を強制せず、正しい品質エラーを出すことを合格条件に含める。

---

## 6. 変更パターン

| ID | 操作 |
|---|---|
| T01 | 1個を10 cm移動 |
| T02 | 1個を30 cm移動 |
| T03 | 1個を90度回転 |
| T04 | 3個を移動 |
| T05 | 全物体を移動 |
| T06 | 1個を画面外へ移動 |
| T07 | 1個を別物体の上へ部分的に重ねる |
| T08 | 無関係な物を1個追加 |
| T09 | カメラ視点を大きく変える |
| T10 | ガイド中に対象外物体を動かす |
| T11 | 正しい位置へ戻した後に再度ずらす |
| T12 | 追跡中に手で一時的に遮る |
| T13 | ガイド中にネットワークを切る |
| T14 | 最終確認直前にネットワークを切る |
| T15 | アプリを5分未満バックグラウンドへ |
| T16 | プロセスを強制終了 |
| T17 | 6個以上置く |
| T18 | 同一外観の物を2個置く |
| T19 | 透明物だけを置く |
| T20 | 暗所で保存を押す |

---

## 7. 必須E2Eケース

最低60回:
- Set A × 木目 × T01〜T12
- Set B × 白色 × T01〜T12
- Set A × 黒色 × T01〜T08
- Set B × 模様あり × T01〜T08
- Set C × 会場机 × T01〜T08
- エラー系 T13〜T20 各1回以上

各実行で記録:
- sceneId
- app version
- modelId
- promptVersion
- object count
- baseline latency
- compare latency
- verify latency
- VLM call count
- match result
- position error
- final result
- false success有無
- 動画ファイル名

---

## 8. 合格閾値

- 支持範囲内のbaseline物体検出率 >= 90%
- 同一物体対応精度 >= 90%
- missing適合率 >= 95%
- 位置復元成功率 >= 90%
- 全体最終成功率 >= 85%
- 成功判定偽陽性 <= 2%
- 位置誤差中央値 <= 3 cm
- 位置誤差90パーセンタイル <= 5 cm
- baseline/compare/verifyのSchema初回適合率 >= 98%
- 1再試行後Schema適合率 >= 99.5%
- 通常フローVLM呼び出し中央値 = 3
- カメラ背景20 FPS未満が連続1秒を超えない
- Rust core p95 < 10 ms
- Androidクラッシュ0件
- Image/Depth/PointCloudリーク0件

---

## 9. 単体テスト

Rust:
- ray-plane intersection
- coordinate transform round trip
- Hungarian assignment
- dummy nodeによるmissing
- ambiguous threshold
- guidance priority
- hysteresis
- state transition invariants
- schema version migration

Go:
- auth middleware
- App Check middleware
- MIME/size/pixel limit
- idempotency
- retry policy
- VLM invalid JSON
- VLM wrong schemaVersion
- VLM unknown enum
- prompt injection文字列を含むfixture
- Firestore writes without labels/images

Android:
- reducer transitions
- permission flow
- AR tracking reason mapping
- capture quality gate
- API error mapping
- Room transaction
- delete/tombstone retry
- haptic event count
- localization resource presence

---

## 10. 物理デモ前チェック

- 端末充電60%以上
- 機内モードOFF
- Wi-Fiとモバイル回線の両方を確認
- Firebase Anonymous Auth成功
- App Checkリリース設定成功
- `/readyz`成功
- Vertex model ID確認
- 会場机で保存品質が緑になる
- 1回リセットして空の状態
- Debug overlay OFF
- Crashlytics送信確認
- 予備端末へ同じRelease buildを配布
- 透明物・同一物体をデモ対象にしない
- デモ開始時に「重ねても1個まで、裏返しはなし」と一言だけ伝える
