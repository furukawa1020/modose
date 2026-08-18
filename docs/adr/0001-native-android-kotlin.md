# ADR-0001: AndroidクライアントをKotlinで実装する

- Status: Accepted
- Date: 2026-08-18

## Context

製品の中心は、ARCoreのセッション、CPUカメラ画像、端末内推論、OpenGL ES描画、Firebase認証を単一のAndroidライフサイクルで安全に扱うことである。クロスプラットフォーム抽象化を挟むと、画像とGPUリソースの所有権、ARCore更新順序、端末固有の障害理由が不透明になる。

## Decision

AndroidクライアントはKotlinとJetpack Composeで実装し、ARCore SDKおよびOpenGL ESを直接利用する。UIはsingle-activity構成とし、Android固有のライフサイクル、権限、描画、端末内Vision、永続化、通信をKotlin側が所有する。

React Native、Flutter、Unity、WebView中心の構成、および非公式なSceneform抽象化を主実装へ採用しない。

## Consequences

- ARCoreの追跡状態とリソース解放をAndroidのライフサイクルへ直接対応付けられる。
- Android以外のクライアントはこの判断の対象外であり、コード共有を目的に製品境界を広げない。
- UIとAndroidアダプタはRustの内部表現へ依存せず、狭いJNI契約だけへ依存する。

## Reconsider when

ARCoreと同等の機能・性能・障害情報を保った公式クロスプラットフォームAPIが提供され、実機計測で性能予算と所有権規則を満たす場合に限り再検討する。
