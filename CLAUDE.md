# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

ISO/IEC TS 23220-4 Photo ID（mdoc 形式）の **Issuer** と **Verifier** を含む Kotlin/JVM マルチプロジェクト。

- **Issuer**: Entra ID でログインしたユーザーに OID4VCI Pre-Authorized Code フローで Photo ID を発行
- **Verifier**: OID4VP（QR コード）+ Digital Credentials API で Photo ID を検証
- Gradle マルチプロジェクト（`issuer/`・`verifier/`）、パッケージは `org.vdcapps.issuer` / `org.vdcapps.verifier`

詳細は [README.md](README.md) を参照。

## ビルド・テストコマンド

```bash
./gradlew build
./gradlew test
./gradlew test --tests "org.vdcapps.issuer.domain.credential.CredentialIssuanceServiceTest"
./gradlew :issuer:run     # 環境変数が必要（README.md 参照）
./gradlew :verifier:run
```

## ドキュメント更新ルール

**コードを変更したら、必ず同じコミットで README.md も更新すること。**

| 変更の種類 | 更新対象 |
| --- | --- |
| エンドポイントの追加・変更・削除 | `README.md`（API エンドポイント表） |
| 環境変数の追加・変更・削除 | `README.md`（環境変数セクション） |
| ファイル構成の変更（新規クラス追加等） | `README.md`（プロジェクト構造） |
| 依存ライブラリの追加・変更 | `README.md`（技術スタック表） |
| アーキテクチャ・設計判断の変更 | `README.md`（設計上の判断と制約） |

省略不可。「後で更新する」は認めない。

## テスト更新ルール

**コードを変更したら、必ず同じコミットで対応するテストも更新または追加すること。**

| 変更の種類 | テストの対応 |
| --- | --- |
| バリデーション・ガード節の追加 | 正常系（通過）と異常系（拒否）の両方をテスト |
| エンドポイントの追加・変更 | 統合テストを追加・更新 |
| ドメインロジック・ユースケースの変更 | 単体テストを追加・更新 |
| セキュリティ機能の追加（認証・CSRF・レート制限等） | 正常系と攻撃シナリオの両方をテスト |
| バグ修正 | バグを再現するテストを追加してから修正 |
| 関数シグネチャの変更 | 既存テストが新シグネチャに対応しているか確認 |

省略不可。「後で書く」は認めない。配置・命名の規約は README.md「テスト規約」を参照。
