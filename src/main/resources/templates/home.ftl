<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Multipaz Photo ID Issuer</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body>
<nav class="navbar navbar-dark bg-primary">
    <div class="container">
        <span class="navbar-brand fw-bold">Multipaz Photo ID Issuer</span>
    </div>
</nav>

<div class="container mt-5">
    <div class="row justify-content-center">
        <div class="col-md-8 text-center">
            <h1 class="display-5 mb-3">Photo ID をデジタル証明書として発行</h1>
            <p class="lead text-muted mb-4">
                Microsoft アカウントでログインすると、あなたのユーザー情報をもとに
                ISO/IEC TS 23220-4 準拠の Photo ID Verifiable Credential を発行します。<br>
                発行された VC は Multipaz Compose Wallet で受け取れます。
            </p>
            <a href="/auth/login" class="btn btn-primary btn-lg px-5">
                <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" fill="currentColor" class="bi bi-microsoft me-2" viewBox="0 0 16 16">
                    <path d="M7.462 0H0v7.19h7.462zM16 0H8.538v7.19H16zM7.462 8.211H0V16h7.462zm8.538 0H8.538V16H16z"/>
                </svg>
                Microsoft アカウントでログイン
            </a>
        </div>
    </div>

    <div class="row mt-5 g-4">
        <div class="col-md-4">
            <div class="card h-100 border-0 shadow-sm">
                <div class="card-body text-center p-4">
                    <div class="fs-1 mb-3">🔐</div>
                    <h5 class="card-title">Entra ID 認証</h5>
                    <p class="card-text text-muted">Microsoft Entra ID でセキュアに認証し、ユーザー情報を取得します。</p>
                </div>
            </div>
        </div>
        <div class="col-md-4">
            <div class="card h-100 border-0 shadow-sm">
                <div class="card-body text-center p-4">
                    <div class="fs-1 mb-3">📄</div>
                    <h5 class="card-title">ISO 23220-4 準拠</h5>
                    <p class="card-text text-muted">ISO/IEC TS 23220-4 Photo ID フォーマットの mdoc 形式で発行します。</p>
                </div>
            </div>
        </div>
        <div class="col-md-4">
            <div class="card h-100 border-0 shadow-sm">
                <div class="card-body text-center p-4">
                    <div class="fs-1 mb-3">📱</div>
                    <h5 class="card-title">Multipaz Wallet 対応</h5>
                    <p class="card-text text-muted">OID4VCI プロトコルで Multipaz Compose Wallet に直接発行します。</p>
                </div>
            </div>
        </div>
    </div>
</div>

<footer class="container text-center text-muted mt-5 mb-3">
    <small>Powered by <a href="https://github.com/openwallet-foundation/multipaz" class="text-decoration-none">Multipaz</a></small>
</footer>
</body>
</html>
