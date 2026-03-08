# プロジェクトの概要

このプロジェクトは `multipaz-issuer` という名前です。

このプロジェクトは、利用者の Entra ID 上のユーザ情報をもとに Verifiable Credentials (VC) を発行するウェブアプリケーションの実装です。

## アーキテクチャ

- [Multipaz](https://github.com/openwallet-foundation/multipaz) ライブラリをできるだけ活用して実装する。
- Multipaz は Kotlin Multiplatform で書かれているため、Kotlin Multiplatform で実装する。
- Digital Credentials API (DC API) または OpenID for Verifiable Credential Issurance (OID4VCI) 仕様に則って、VC を発行する。
  - 両方実装できるなら、両方。Multipaz を利用するならば片方しか実装できないのであれば、一旦片方でよい。
- Entra ID の OIDC RP として働き、まずは Entra ID にログインしてもらう。ログイン後、OIDC で取得できるユーザ情報で十分ならばそれで、十分でなければ Graph API を使って詳細なユーザ情報を取得する。そしてその内容を VC に含める。
- VC のフォーマットは ISO/IEC TS 23220-4 Photo ID に従う。
- バックエンドとフロントエンドを分けるようなことはせず、１つのシンプルなウェブアプリケーションとして実装する。
- CSS デザインは Bootstrap を利用する。
- ドメイン駆動設計に基づきつつ、厳密さよりもシンプルさを重視する。

## テスト

- 充実したユニットテストを含める。
- 高速な E2E テストを含める。

## 注意点

- 実装時は、ダミーのデータを返したり適当な仕様準拠をしたりせず、正しく Entra ID のユーザ情報を取得して正しく DC API または OID4VCI に基づいて VC を発行すること。
  - ユニットテスト時はダミーデータでよい。
- 実際に [Multipaz Compose Wallet](https://github.com/openwallet-foundation/multipaz-samples/tree/main/ComposeWallet) を使って VC を発行できること。
- 後に Multipaz ライブラリを使った Verifier も開発する予定なので、発行した VC をMultipaz を使って検証できること。
