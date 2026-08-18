# ADR-0006: TypeScriptを導入しない

- Status: Accepted
- Date: 2026-08-18

## Context

製品の実行境界はAndroid、決定論的コア、クラウドAPIの3つであり、それぞれKotlin、Rust、Goで必要な機能を満たせる。追加言語はツールチェーン、依存監査、Schema生成、CI、担当境界を増やす。

## Decision

製品コード、ビルド用スクリプト、CI補助、テストハーネスへTypeScriptおよびJavaScriptランタイムを導入しない。AndroidはKotlin、決定論的コアはRust、クラウドAPIと契約補助はGoまたは各標準ツールで実装する。

生成物にJavaScriptが含まれるツールは、リポジトリへランタイム依存や保守対象コードを持ち込まず、再現可能な固定ツールとしてのみ利用を検討できる。

## Consequences

- Node.jsを必須ツールチェーンに含めない。
- OpenAPIとJSON Schemaの検証・コード生成はGo、JVM、Rust、または固定済みスタンドアロンツールから選ぶ。
- Web管理画面が必要になっても、本ADRを変更せず製品リポジトリへTypeScriptを追加できない。

## Reconsider when

新しいユーザー要件がWeb実行環境を必須とし、既存3言語では保守性または安全性を満たせない根拠が示された場合に、新ADRで再検討する。
