# Architecture Decision Records

MODOSEの主要な設計判断を記録する。ADRは採用時点の理由、許可する境界、運用上の帰結、再検討条件を固定する。

## 運用規則

- Statusが`Accepted`のADRは実装上の制約である。
- 判断を変更する場合は既存ADRを直接書き換えず、新しいADRで`Superseded`にする。
- ライブラリの追加だけではADRを要求しない。言語、実行境界、データ所有権、外部契約、プライバシー境界を変える場合はADRを要求する。
- ADRと一般設計文書が矛盾する場合は、より新しいAccepted ADRを優先する。

## 一覧

| ID | 判断 | Status |
| --- | --- | --- |
| [ADR-0001](0001-native-android-kotlin.md) | AndroidクライアントをKotlinで実装する | Accepted |
| [ADR-0002](0002-rust-deterministic-core.md) | 決定論的コアをRustへ分離する | Accepted |
| [ADR-0003](0003-go-cloud-run-api.md) | クラウドAPIをGoとCloud Runで実装する | Accepted |
| [ADR-0004](0004-bounded-vlm-responsibility.md) | VLMの責任を意味理解と最終確認に限定する | Accepted |
| [ADR-0005](0005-local-first-session-boundary.md) | 同一ARセッションのlocal-first設計とする | Accepted |
| [ADR-0006](0006-no-typescript.md) | TypeScriptを導入しない | Accepted |
