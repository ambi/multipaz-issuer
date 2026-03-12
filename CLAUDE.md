# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## プロジェクト概要

ISO/IEC TS 23220-4 Photo ID の Verifiable Credential (mdoc 形式) の **Issuer** と **Verifier** を含む Kotlin/JVM ウェブアプリケーション。

- **Issuer**: Entra ID でログインしたユーザーの情報を元に Photo ID VC を発行
  - プロトコル: OID4VCI Pre-Authorized Code フロー
- **Verifier**: Multipaz Compose Wallet が保持する Photo ID VC を検証
  - プロトコル: OID4VP（QR コードフロー）+ Digital Credentials API（ブラウザフロー）
- **VC フォーマット**: `mso_mdoc`、doctype `org.iso.23220.photoid.1`
- **ライブラリ**: Multipaz 0.97.0 (GMaven `google()`)、Ktor 3.0.3 + Netty
- **CSSフレームワーク**: Tailwind
- **Wallet**: Multipaz Compose Wallet で VC の発行・提示を検証済み前提

## プロジェクト構成

Gradle マルチプロジェクト (`rootProject.name = "vdc-apps"`)。

```text
vdc-apps/
  issuer/    # パッケージ: org.vdcapps.issuer
  verifier/  # パッケージ: org.vdcapps.verifier
```

## ビルド・実行コマンド

```bash
# ビルド
./gradlew build

# テスト全件
./gradlew test

# 単一テストクラス
./gradlew test --tests "org.vdcapps.issuer.domain.credential.CredentialIssuanceServiceTest"

# 起動（事前に環境変数が必要）
./gradlew :issuer:run
./gradlew :verifier:run

# 実行可能 JAR を作成して起動
./gradlew installDist
./issuer/build/install/issuer/bin/issuer
./verifier/build/install/verifier/bin/verifier
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
ISSUING_AUTHORITY="VDC Apps Issuer"
```

Azure でのアプリ登録時に必要な設定:

- リダイレクト URI: `{BASE_URL}/auth/callback`
- スコープ: `User.Read` (Graph API)
- `Mail.Read` は不要（UPN を email の代替として使用）

## アーキテクチャ

### レイヤー構成 (DDD 軽量版)

#### issuer/ (`org.vdcapps.issuer`)

```text
domain/
  identity/EntraUser.kt                  # Entra ID から取得するユーザー情報
  credential/PhotoIdCredential.kt        # 発行する Photo ID の属性値
  credential/IssuanceSession.kt          # OID4VCI セッション状態機械 (PENDING→TOKEN_ISSUED→CREDENTIAL_ISSUED)
  credential/CredentialIssuanceService.kt# pre-authorized_code / access_token 管理
  credential/IssuanceSessionRepository.kt# インターフェース + InMemory 実装
  credential/NonceStore.kt               # c_nonce の生成・検証

infrastructure/
  entra/EntraIdClient.kt                 # MS Graph API で displayName/surname/photo を取得
  multipaz/IssuerKeyStore.kt             # 発行者署名鍵 (P-256 EC) を PKCS12 に永続化、初回自動生成
  multipaz/PhotoIdBuilder.kt             # Multipaz API で IssuerNamespaces→MSO→COSE_Sign1 を組み立て

application/
  IssueCredentialUseCase.kt              # 証明書発行のユースケース

web/
  plugins/Auth.kt                        # Ktor Sessions + OAuth (Entra ID) 設定、UserSession 定義
  plugins/Routing.kt                     # FreeMarker + CallLogging、全ルートの登録
  plugins/Serialization.kt               # ContentNegotiation (JSON)
  routes/HomeRoutes.kt                   # GET / と /profile
  routes/AuthRoutes.kt                   # /auth/login, /auth/callback, /auth/logout
  routes/Oid4vciRoutes.kt                # OID4VCI エンドポイント群

Application.kt                           # DI 相当の配線と module() 関数
```

#### verifier/ (`org.vdcapps.verifier`)

```text
domain/
  verification/VerificationSession.kt         # OID4VP セッション状態機械 (PENDING→RESPONSE_RECEIVED→VERIFIED/FAILED)
  verification/VerificationResult.kt          # 検証済みクレーム（名前空間 → 要素名 → 値）
  verification/VerificationSessionRepository.kt # インターフェース + InMemory 実装
  verification/VerificationService.kt         # OID4VP セッション管理ドメインサービス

infrastructure/
  multipaz/MdocVerifier.kt                    # DeviceResponse CBOR を検証し VerificationResult を返す

application/
  VerifyCredentialUseCase.kt                  # 証明書検証のユースケース

web/
  plugins/Routing.kt                          # FreeMarker + CallLogging、全ルートの登録
  plugins/Serialization.kt                    # ContentNegotiation (JSON)
  routes/VerifierRoutes.kt                    # OID4VP エンドポイント群

Application.kt                                # DI 相当の配線と module() 関数
```

### OID4VCI エンドポイント（Issuer）

| エンドポイント                                | 役割                                    |
| --------------------------------------------- | --------------------------------------- |
| `GET /.well-known/openid-credential-issuer`   | Credential Issuer Metadata              |
| `GET /.well-known/oauth-authorization-server` | AS Metadata                             |
| `POST /token`                                 | pre-authorized_code → access_token 交換 |
| `POST /nonce`                                 | c_nonce の再取得                        |
| `POST /credential`                            | proof JWT 検証 → 署名済み mdoc 発行     |

### OID4VP エンドポイント（Verifier）

| エンドポイント                      | 役割                                                         |
| ----------------------------------- | ------------------------------------------------------------ |
| `GET /verifier`                     | 検証開始ページ（QR コード + Digital Credentials API ボタン） |
| `GET /verifier/request/{sessionId}` | OID4VP Authorization Request JSON（Wallet が取得）           |
| `POST /verifier/response`           | Wallet が VP を POST するエンドポイント                      |
| `GET /verifier/result/{sessionId}`  | 検証結果の表示（ポーリング + 最終結果ページ）                |

### Issuer ブラウザ UI フロー

```text
GET /  (未ログイン) → ログインボタン
GET /auth/login     → Entra ID OAuth リダイレクト
GET /auth/callback  → Graph API でユーザー情報取得 → UserSession 保存
GET /profile        → 氏名確認フォーム + 生年月日入力
POST /issue         → IssuanceSession 生成 → /offer/{sessionId} へリダイレクト
GET /offer/{id}     → QR コード表示（openid-credential-offer:// URI）
```

### Verifier フロー（OID4VP QR コード）

```text
GET /verifier               → VerificationSession 生成 → QR コード表示
                              QR に openid4vp://?request_uri=.../verifier/request/{id} を含む
GET /verifier/request/{id}  → Wallet が Authorization Request JSON を取得
POST /verifier/response     → Wallet が vp_token + presentation_submission を POST
                              MdocVerifier で署名検証・クレーム抽出
GET /verifier/result/{id}   → 検証結果ページ（ポーリングで Wallet の応答を待つ）
```

### Verifier フロー（Digital Credentials API）

```text
GET /verifier               → ページロード
                              JS で navigator.credentials.get({ digital: {...} }) を呼ぶ
                              Wallet（OS 経由）が vp_token を返す
                              JS が POST /verifier/response に送信
GET /verifier/result/{id}   → 検証結果ページへ JS でリダイレクト
```

### Multipaz API の使用箇所（Issuer: PhotoIdBuilder.kt）

1. `buildIssuerNamespaces {}` DSL でデータ要素と random salt を生成
2. `issuerNamespaces.getValueDigests(Algorithm.SHA256)` でダイジェストマップを作成
3. `MobileSecurityObject(...)` で MSO を構築 (`deviceKey` = Wallet の EC 公開鍵)
4. MSO を `Tagged(24, Bstr(...))` でラップして COSE payload にする
5. `Cose.coseSign1Sign(AsymmetricKey.X509CertifiedExplicit(...), ...)` で IssuerAuth を署名
6. `buildCborMap { put("nameSpaces", ...); put("issuerAuth", ...) }` で IssuerSigned を組み立て
7. CBOR バイト列を base64url エンコードして OID4VCI レスポンスの `credential` に入れる

### Multipaz API の使用箇所（Verifier: MdocVerifier.kt）

1. `Cbor.decode(deviceResponseBytes)` で DeviceResponse CBOR をパース
2. `documents[0].issuerSigned.issuerAuth` から COSE_Sign1 配列を取得
3. protected/unprotected headers から x5chain（発行者 X.509 証明書 DER）を取得
4. `MobileSecurityObject.fromDataItem(...)` で MSO をパース → 有効期間・docType 検証
5. Bouncy Castle / Java Signature API で ES256 (SHA256withECDSA) 署名を検証
6. `issuerSigned.nameSpaces` から各 `IssuerSignedItem`（Tagged(24, Bstr(...))）をデコードして要素値を抽出

**DeviceResponse CBOR 構造**:

```text
DeviceResponse = { "version": "1.0", "documents": [Document], "status": 0 }
Document = { "docType": tstr, "issuerSigned": IssuerSigned }
IssuerSigned = { "nameSpaces": IssuerNameSpaces, "issuerAuth": COSE_Sign1 }
IssuerNameSpaces = { tstr => [Tagged(24, Bstr(IssuerSignedItem CBOR))] }
IssuerSignedItem = { "digestID": uint, "random": bstr, "elementIdentifier": tstr, "elementValue": any }
```

**OID4VP vp_token**: base64url エンコードされた DeviceResponse バイト列（mso_mdoc フォーマット）

### 注意点

- **生年月日**: Entra ID の標準属性には含まれないため、ブラウザ UI でユーザーに入力させる
- **顔写真**: Graph API `/me/photo/$value` から取得。未設定の場合は portrait 要素を省略（optional）
- **セッション管理**: InMemory 実装は再起動でリセット。本番では永続化ストアに差し替える
- **発行者鍵**: 自己署名証明書。本番では CA 署名の証明書チェーンに差し替える
- **Verifier の証明書信頼**: 現状は VC に含まれる x5chain 証明書の署名を直接信頼。本番では IACA (Issuing Authority Certificate Authority) のルート証明書で検証する
- **Multipaz のリポジトリ**: `google()` (GMaven) に publish されている
