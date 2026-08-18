# ADR-0003: クラウドAPIをGoとCloud Runで実装する

- Status: Accepted
- Date: 2026-08-18

## Context

VLM呼び出しの前後にはID tokenとApp Check tokenの検証、画像制限、構造化出力検証、冪等性、プライバシーを守るログが必要である。これらをAndroidへ置くと認証情報とプロンプト契約を信頼できないクライアントへ公開する。

## Decision

Cloud Run上のGoサービスを唯一のVertex AI入口とする。サービスは薄いHTTP層、認証、App Check、入力正規化、JSON Schema検証、冪等性、Firestoreメタデータ、観測可能性を分離する。

API契約はOpenAPI 3.1とJSON Schemaを正本とし、handlerからVertex AIおよびFirestoreへ直接依存しない。モデルIDは`VLM_MODEL_ID`設定から取得する。

## Consequences

- クライアントを信用せずに入力と出力の契約を強制できる。
- Cloud RunとVertex AIの障害を型付きエラーへ変換する責任をサービスが持つ。
- ガイド中の毎フレーム処理はクラウドへ依存させない。

## Reconsider when

認証、App Check、Vertex AI、構造化出力、冪等性を同じセキュリティ境界で提供する別の実行基盤に、運用上の明確な優位性が確認された場合に再検討する。
