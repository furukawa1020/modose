# GitHub Actions依存更新規則

## 目的

GitHub Actionsの実行ランタイム廃止と、浮動参照による意図しない依存更新を防ぐ。

## 参照規則

外部Actionはリリースタグだけで参照せず、検証したcommit SHAへ固定する。行末コメントへ対応するリリース番号を記録する。

```yaml
uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
```

commit SHAは公式リポジトリのタグ参照から取得し、第三者のミラーや検索結果だけを根拠にしない。

## 更新時の確認

Action更新は、利用箇所と利用目的を確認して小さなPRへ分離する。

- 公式リリースが安定版である
- タグが指すcommit SHAとworkflowのSHAが一致する
- `action.yml`の`runs.using`がGitHub Actionsのサポート対象である
- 入力、出力、権限、資格情報の保持方法に破壊的変更がない
- CIが成功する
- deploy系Actionはpreflightまたはstagingで実行証拠を残す

## Nodeランタイム警告

GitHub Actionsがランタイム廃止警告を出した場合、workflow内のNode.js設定だけを探さない。JavaScript Action自身が古いNodeランタイムを宣言している場合があるため、警告が示すActionと、そのActionの`action.yml`を確認する。

警告を無視したまま成功扱いにしない。GitHub側の強制ランタイム移行へ依存せず、対応版へ明示的に更新する。

## 更新範囲

複数workflowに同じActionが存在しても、ユーザー可視のデプロイ経路や認証経路が異なる場合はPRを分ける。各PRで正常系、失敗系、固定SHAの根拠を説明する。

無関係なAction、workflowロジック、権限変更を依存更新PRへ混ぜない。
