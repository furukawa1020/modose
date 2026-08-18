# ADR-0002: 決定論的コアをRustへ分離する

- Status: Accepted
- Date: 2026-08-18

## Context

座標計算、物体対応付け、ガイド順序、ヒステリシス、状態遷移は、フレームやネットワーク状態にかかわらず同じ入力へ同じ結果を返し、偽成功を防ぐ必要がある。これらをAndroid UIやVLM応答処理へ混在させると、検証可能性とメモリ所有権が崩れる。

## Decision

Android非依存の`scene-core`をRustで実装し、`scene-core-jni`を唯一のAndroid境界とする。JNIでは数値構造、列挙値、固定形式のバイト列だけを受け渡す。

RustはAndroid `Context`、ARCore型、Firebase SDK、HTTPクライアント、UI文言、`Bitmap`、`Media.Image`を保持しない。成功状態はRust状態機械の不変条件と検証済み入力を通した場合だけ返す。

## Consequences

- コアをAndroid端末なしで単体・性質ベーステストできる。
- JNI変換の追加コストと境界管理が必要になる。
- RustパニックをJNI境界の外へ伝播させず、型付きエラーへ変換する必要がある。

## Reconsider when

JNI境界が性能予算を継続的に超える、またはRustツールチェーンが対象Android ABIを維持できないことを再現可能な計測で確認した場合に再検討する。
