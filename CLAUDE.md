# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

Entra ID でログインしたユーザーの情報を元に **ISO/IEC TS 23220-4 Photo ID** の Verifiable Credential (mdoc 形式) を発行する Kotlin/JVM ウェブアプリケーション。

- **プロトコル**: OID4VCI Pre-Authorized Code フロー
- **VC フォーマット**: `mso_mdoc`、doctype `org.iso.23220.photoid.1`
- **ライブラリ**: Multipaz 0.97.0 (GMaven `google()`)、Ktor 3.0.3 + Netty
- **Wallet**: Multipaz Compose Wallet で VC を受け取ることを検証済み前提

## ビルド・実行コマンド

```bash
# ビルド
./gradlew build

# テスト全件
./gradlew test

# 単一テストクラス
./gradlew test --tests "org.multipaz.issuer.domain.credential.CredentialIssuanceServiceTest"

# 起動（事前に環境変数が必要）
./gradlew run

# 実行可能 JAR を作成して起動
./gradlew installDist
./build/install/multipaz-issuer/bin/multipaz-issuer
```

## 必要な環境変数

```bash
ENTRA_TENANT_ID=<Azure AD テナント ID>
ENTRA_CLIENT_ID=<アプリ登録のクライアント ID>
ENTRA_CLIENT_SECRET=<クライアントシークレット>
# 任意
BASE_URL=https://your-domain.example.com   # デフォルト: http://localhost:8080
ENTRA_REDIRECT_URI=https://your-domain.example.com/auth/callback
KEY_STORE_PATH=issuer-keystore.p12          # 初回起動時に自動生成
KEY_STORE_PASSWORD=changeit
ISSUING_COUNTRY=JP
ISSUING_AUTHORITY="Multipaz Issuer"
```

Azure でのアプリ登録時に必要な設定:

- リダイレクト URI: `{BASE_URL}/auth/callback`
- スコープ: `User.Read` (Graph API)
- `Mail.Read` は不要（UPN を email の代替として使用）

## アーキテクチャ

### レイヤー構成 (DDD 軽量版)

```text
domain/
  identity/EntraUser.kt          # Entra ID から取得するユーザー情報
  credential/PhotoIdCredential.kt # 発行する Photo ID の属性値
  credential/IssuanceSession.kt   # OID4VCI セッション状態機械 (PENDING→TOKEN_ISSUED→CREDENTIAL_ISSUED)
  credential/CredentialIssuanceService.kt  # pre-authorized_code / access_token 管理
  credential/IssuanceSessionRepository.kt # インターフェース + InMemory 実装

infrastructure/
  entra/EntraIdClient.kt         # MS Graph API で displayName/surname/photo を取得
  multipaz/IssuerKeyStore.kt     # 発行者署名鍵 (P-256 EC) を PKCS12 に永続化、初回自動生成
  multipaz/PhotoIdBuilder.kt     # Multipaz API で IssuerNamespaces→MSO→COSE_Sign1 を組み立て

application/
  IssueCredentialUseCase.kt      # ドメインとインフラの橋渡し。証明書属性の組み立てと発行

web/
  plugins/Auth.kt                # Ktor Sessions + OAuth (Entra ID) 設定、UserSession 定義
  plugins/Routing.kt             # FreeMarker + CallLogging、全ルートの登録
  plugins/Serialization.kt       # ContentNegotiation (JSON)
  routes/HomeRoutes.kt           # GET / と /profile
  routes/AuthRoutes.kt           # /auth/login, /auth/callback, /auth/logout
  routes/Oid4vciRoutes.kt        # OID4VCI エンドポイント群（下記参照）

Application.kt                   # DI 相当の配線と module() 関数
```

### OID4VCI エンドポイント

| エンドポイント | 役割 |
| --- | --- |
| `GET /.well-known/openid-credential-issuer` | Credential Issuer Metadata |
| `GET /.well-known/oauth-authorization-server` | AS Metadata |
| `GET /credential-offer/{sessionId}` | Credential Offer JSON（Wallet が最初に呼ぶ） |
| `POST /token` | pre-authorized_code → access_token 交換 |
| `POST /nonce` | c_nonce の再取得 |
| `POST /credential` | proof JWT 検証 → 署名済み mdoc 発行 |

### ブラウザ UI フロー

```text
GET /  (未ログイン) → ログインボタン
GET /auth/login     → Entra ID OAuth リダイレクト
GET /auth/callback  → Graph API でユーザー情報取得 → UserSession 保存
GET /profile        → 氏名確認フォーム + 生年月日入力（Entra ID にないため必須入力）
POST /issue         → IssuanceSession 生成 → /offer/{sessionId} へリダイレクト
GET /offer/{id}     → QR コード表示（openid-credential-offer:// URI）
```

### Multipaz API の使用箇所

`PhotoIdBuilder.kt` が核心。以下の順序で mdoc を構築する:

1. `buildIssuerNamespaces {}` DSL でデータ要素と random salt を生成
2. `issuerNamespaces.getValueDigests(Algorithm.SHA256)` でダイジェストマップを作成
3. `MobileSecurityObject(...)` で MSO を構築 (`deviceKey` = Wallet の EC 公開鍵)
4. MSO を `Tagged(24, Bstr(...))` でラップして COSE payload にする
5. `Cose.coseSign1Sign(AsymmetricKey.X509CertifiedExplicit(...), ...)` で IssuerAuth を署名
6. `buildCborMap { put("nameSpaces", ...); put("issuerAuth", ...) }` で IssuerSigned を組み立て
7. CBOR バイト列を base64url エンコードして OID4VCI レスポンスの `credential` に入れる

**Java ↔ Multipaz の鍵変換**: `IssuerKeyStore` は Java `ECPrivateKey` を管理し、`PhotoIdBuilder.buildMultipazSigningKey()` で P-256 の `d` バイト列から `EcPrivateKey(EcCurve.P256, dBytes)` を生成。proof JWT の Wallet 公開鍵は Nimbus JOSE+JWT で `ECKey` を取り出し、`ecPublicKeyFromCoordinates(x, y)` で `EcPublicKey(EcCurve.P256, uncompressed)` に変換。

### 注意点

- **生年月日**: Entra ID の標準属性には含まれないため、ブラウザ UI でユーザーに入力させる
- **顔写真**: Graph API `/me/photo/$value` から取得。未設定の場合は portrait 要素を省略（optional）
- **セッション管理**: `InMemoryIssuanceSessionRepository` は再起動でリセットされる。本番では永続化ストアに差し替える
- **発行者鍵**: 自己署名証明書。本番では CA 署名の証明書チェーンに差し替える
- **Multipaz のリポジトリ**: `google()` (GMaven) に publish されている
