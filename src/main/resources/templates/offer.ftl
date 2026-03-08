<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>QR コードをスキャン - Multipaz Photo ID Issuer</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body>
<nav class="navbar navbar-dark bg-primary">
    <div class="container">
        <span class="navbar-brand fw-bold">Multipaz Photo ID Issuer</span>
        <a href="/auth/logout" class="btn btn-outline-light btn-sm">ログアウト</a>
    </div>
</nav>

<div class="container mt-4" style="max-width: 600px;">
    <h2 class="mb-2 text-center">QR コードをスキャン</h2>
    <p class="text-center text-muted mb-4">Multipaz Compose Wallet でスキャンしてください。</p>

    <div class="text-center mb-4">
        <img src="data:image/png;base64,${qrCodeBase64}" alt="Credential Offer QR Code"
             class="img-fluid border rounded shadow-sm" style="max-width: 300px;">
    </div>

    <div class="card border-0 shadow-sm mb-4">
        <div class="card-body">
            <h6 class="card-title text-muted mb-2">Credential Offer URI</h6>
            <div class="input-group">
                <input type="text" class="form-control form-control-sm font-monospace"
                       id="offerUri" value="${offerUri}" readonly>
                <button class="btn btn-outline-secondary btn-sm" type="button"
                        onclick="navigator.clipboard.writeText(document.getElementById('offerUri').value)">
                    コピー
                </button>
            </div>
        </div>
    </div>

    <div class="alert alert-info">
        <strong>手順：</strong>
        <ol class="mb-0 mt-1">
            <li>Multipaz Compose Wallet アプリを開く</li>
            <li>「資格情報を追加」または QR スキャンを選択</li>
            <li>上記の QR コードをスキャン</li>
            <li>Photo ID を受け取る</li>
        </ol>
    </div>

    <div class="alert alert-warning mt-3">
        この QR コードは <strong>10 分間</strong>有効です。
    </div>

    <div class="text-center mt-3">
        <a href="/profile" class="btn btn-outline-secondary">別の証明書を発行する</a>
    </div>
</div>
</body>
</html>
