<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>プロフィール確認 - Photo ID Issuer</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body>
<nav class="navbar navbar-dark bg-primary">
    <div class="container">
        <span class="navbar-brand fw-bold">Photo ID Issuer</span>
        <a href="/auth/logout" class="btn btn-outline-light btn-sm">ログアウト</a>
    </div>
</nav>

<div class="container mt-4" style="max-width: 640px;">
    <h2 class="mb-4">Photo ID 発行</h2>

    <#if error??>
    <div class="alert alert-danger">${error}</div>
    </#if>

    <div class="card shadow-sm mb-4">
        <div class="card-header bg-light fw-semibold">Entra ID から取得したユーザー情報</div>
        <div class="card-body">
            <div class="row mb-2">
                <div class="col-4 text-muted">表示名</div>
                <div class="col-8">${displayName}</div>
            </div>
            <div class="row mb-2">
                <div class="col-4 text-muted">メール</div>
                <div class="col-8">${email!"(未設定)"}</div>
            </div>
            <#if hasPhoto>
            <div class="row mb-2">
                <div class="col-4 text-muted">写真</div>
                <div class="col-8"><span class="badge bg-success">取得済み</span></div>
            </div>
            <#else>
            <div class="row mb-2">
                <div class="col-4 text-muted">写真</div>
                <div class="col-8"><span class="badge bg-secondary">未設定</span></div>
            </div>
            </#if>
        </div>
    </div>

    <form method="post" action="/issue">
        <div class="card shadow-sm mb-4">
            <div class="card-header bg-light fw-semibold">証明書に含める情報を確認・入力</div>
            <div class="card-body">
                <div class="mb-3">
                    <label for="familyName" class="form-label">姓（Family Name）</label>
                    <input type="text" class="form-control" id="familyName" name="familyName"
                           value="${familyName}" required>
                </div>
                <div class="mb-3">
                    <label for="givenName" class="form-label">名（Given Name）</label>
                    <input type="text" class="form-control" id="givenName" name="givenName"
                           value="${givenName}" required>
                </div>
                <div class="mb-3">
                    <label for="birthDate" class="form-label">
                        生年月日 <span class="text-danger">*</span>
                        <small class="text-muted ms-1">（Entra ID から自動取得できないため入力が必要です）</small>
                    </label>
                    <input type="date" class="form-control" id="birthDate" name="birthDate"
                           required max="${.now?string('yyyy-MM-dd')}">
                </div>
            </div>
        </div>

        <div class="d-grid">
            <button type="submit" class="btn btn-primary btn-lg">
                📱 Wallet に Photo ID を発行する
            </button>
        </div>
    </form>
</div>
</body>
</html>
