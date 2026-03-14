# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## ドキュメント更新ルール

**コードを変更したら、必ず同じコミットで以下のドキュメントも更新すること。**

| 変更の種類 | 更新対象 |
| --- | --- |
| エンドポイントの追加・変更・削除 | `CLAUDE.md`（エンドポイント表）、`README.md`（API エンドポイント表） |
| 環境変数の追加・変更・削除 | `CLAUDE.md`（必要な環境変数）、`README.md`（環境変数セクション） |
| ファイル構成の変更（新規クラス追加等） | `CLAUDE.md`（レイヤー構成）、`README.md`（プロジェクト構造） |
| 依存ライブラリの追加・変更 | `README.md`（技術スタック表） |
| アーキテクチャ・設計判断の変更 | `README.md`（設計上の判断と制約） |
| プラグイン・ミドルウェアの追加・変更 | `CLAUDE.md`（該当プラグインの説明） |

ドキュメントの更新を省略してはならない。「後で更新する」は認めない。

## テスト更新ルール

**コードを変更したら、必ず同じコミットで対応するテストも更新または追加すること。**

| 変更の種類 | テストの対応 |
| --- | --- |
| 新しいバリデーション・ガード節の追加 | 正常系（通過）と異常系（拒否）の両方をテスト |
| エンドポイントの追加・変更 | ルートのテスト（統合テスト）を追加・更新 |
| ドメインロジック・ユースケースの変更 | 単体テストを追加・更新 |
| セキュリティ機能の追加（認証・CSRF・レート制限等） | 正常系と攻撃シナリオの両方をテスト |
| バグ修正 | バグを再現するテストを追加し、修正後に通過することを確認 |
| 関数シグネチャの変更（引数の追加等） | 既存テストが新シグネチャに対応しているか確認・更新 |

テストの更新を省略してはならない。「後で書く」は認めない。

### テスト配置の原則

- **単体テスト**: ドメインロジック・インフラ実装は `src/test/kotlin/...` の対応パッケージに配置
- **統合テスト**: Ktor ルートは `web/routes/` または `web/` パッケージのテストに配置
- **テスト用ヘルパー**: テストモジュールや共通セットアップは同一ファイルの `private` 関数・クラスとして定義
- **テスト間の独立性**: グローバルな状態（RateLimiter 等）は `@BeforeTest` でリセットすること
- **テスト名**: 日本語で「〜する場合は〜を返す」形式で記述し、何をテストしているか明確にすること

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
- **ログ**: Logback 1.5.12 + logstash-logback-encoder 8.0（JSON 構造化ログ）

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
KEY_STORE_PASSWORD=<強いパスワードを設定>   # 必須。デフォルト値なし
ISSUING_COUNTRY=JP
ISSUING_AUTHORITY="VDC Apps Issuer"
REDIS_URL=redis://localhost:6379            # 未設定時はインメモリ（issuer/verifier 共通）
TRUSTED_ISSUER_CERT=/path/to/issuer-ca.pem # Verifier 専用: 信頼する発行者証明書の PEM パス
                                            # 未設定時は警告を出力して証明書検証をスキップ（開発用のみ）
# ログ設定（issuer/verifier 共通）
LOG_FORMAT=json                             # json（デフォルト）または text（ローカル開発向け）
LOG_LEVEL_APP=INFO                          # アプリログレベル（DEBUG/INFO/WARN/ERROR）
LOG_LEVEL_KTOR=WARN                         # Ktor フレームワークログレベル
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
  credential/NonceStore.kt               # c_nonce のステートレス発行・検証（HMAC-SHA256）

infrastructure/
  entra/EntraIdClient.kt                 # MS Graph API で displayName/surname/photo を取得
  multipaz/IssuerKeyStore.kt             # 発行者署名鍵 (P-256 EC) を PKCS12 に永続化、初回自動生成
  multipaz/PhotoIdBuilder.kt             # Multipaz API で IssuerNamespaces→MSO→COSE_Sign1 を組み立て
  redis/RedisIssuanceSessionRepository.kt# Redis 実装（REDIS_URL 設定時に使用）

application/
  IssueCredentialUseCase.kt              # 証明書発行のユースケース

web/
  plugins/Auth.kt                        # Ktor Sessions + OAuth (Entra ID) 設定、UserSession 定義
                                         # cookie に SameSite=Strict を設定
                                         # UserSession: userId/displayName/givenName/familyName/email/hasPhoto/csrfToken
                                         # ※ entraAccessToken はセキュリティ上 Cookie に保存しない
  plugins/Routing.kt                     # FreeMarker + CallId + CallLogging + StatusPages、全ルートの登録
                                         # SecurityHeaders プラグイン: X-Frame-Options, X-Content-Type-Options,
                                         # X-XSS-Protection, Referrer-Policy, CSP, HSTS（HTTPS 時のみ）
  plugins/Serialization.kt               # ContentNegotiation (JSON)
  routes/HomeRoutes.kt                   # GET / と /profile（csrfToken をテンプレートに渡す）
  routes/AuthRoutes.kt                   # /auth/login, /auth/callback, /auth/logout
                                         # コールバック時に csrfToken を生成して UserSession に保存
  routes/HealthRoutes.kt                 # GET /health（liveness）、GET /readiness（Redis 疎通確認）
  routes/Oid4vciRoutes.kt                # OID4VCI エンドポイント群
                                         # /issue: CSRF トークン検証・生年月日バリデーション
                                         # /token, /credential, /nonce: IP ベースのレート制限
                                         # /credential: Content-Type 検証・JWT アルゴリズム検証

Application.kt                           # DI 相当の配線と module() 関数
                                         # HTTP クライアント（タイムアウト付き）、Redis pool 管理
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
                                              # trustedCertificates: 信頼済み発行者証明書リスト（空=開発モード警告）
                                              # ※ DeviceSigned nonce 検証は未実装（TODO）
  redis/RedisVerificationSessionRepository.kt # Redis 実装（REDIS_URL 設定時に使用）

application/
  VerifyCredentialUseCase.kt                  # 証明書検証のユースケース

web/
  plugins/Routing.kt                          # FreeMarker + CallId + CallLogging + StatusPages、全ルートの登録
                                              # SecurityHeaders プラグイン（Issuer と同等）
  plugins/Serialization.kt                    # ContentNegotiation (JSON)
  routes/HealthRoutes.kt                      # GET /health（liveness）、GET /readiness（Redis 疎通確認）
  routes/VerifierRoutes.kt                    # OID4VP エンドポイント群

Application.kt                                # DI 相当の配線と module() 関数
                                              # Redis pool 管理
                                              # TRUSTED_ISSUER_CERT から信頼済み証明書をロードして MdocVerifier に渡す
```

### 共通エンドポイント（Issuer・Verifier 両方）

| エンドポイント   | 役割                                                            |
| ---------------- | --------------------------------------------------------------- |
| `GET /health`    | Liveness probe（常に 200 OK）                                   |
| `GET /readiness` | Readiness probe（Redis 設定時は ping で疎通確認。503 = 未準備） |

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
- **セッション管理**: `REDIS_URL` 設定で Redis、未設定でインメモリ。InMemory は再起動でリセット
- **発行者鍵**: 自己署名証明書。本番では CA 署名の証明書チェーンに差し替える
- **Verifier の証明書信頼**: `TRUSTED_ISSUER_CERT` 環境変数で信頼済み PEM ファイルを指定する。未設定時は WARN ログを出力して検証をスキップ（開発専用）。本番では IACA (Issuing Authority Certificate Authority) のルート証明書を設定すること
- **Verifier の nonce 検証**: OID4VP の nonce は DeviceSigned > DeviceAuthentication > SessionTranscript に含まれるが、現状は DeviceSigned の解析・署名検証が未実装。WARN ログで警告を出力中。本番導入前に実装が必要
- **CSRF 保護**: UserSession に csrfToken を保持し /issue POST で検証。Cookie の SameSite=Strict と合わせた多層防御
- **Multipaz のリポジトリ**: `google()` (GMaven) に publish されている
