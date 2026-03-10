<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>検証結果 - Multipaz Verifier</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body>
<nav class="navbar navbar-dark bg-success">
    <div class="container">
        <span class="navbar-brand fw-bold">Multipaz Photo ID Verifier</span>
        <a href="/verifier" class="btn btn-outline-light btn-sm">新しい検証</a>
    </div>
</nav>

<div class="container mt-4" style="max-width: 720px;">

<#if status == "pending">
    <!-- ポーリング中 -->
    <div class="text-center py-5">
        <div class="spinner-border text-success mb-3" role="status" style="width:3rem;height:3rem;">
            <span class="visually-hidden">待機中...</span>
        </div>
        <h4 class="mb-2">Wallet からの応答を待っています</h4>
        <p class="text-muted">QR コードをスキャンするか、Digital Credentials API で Photo ID を提示してください。</p>
    </div>
    <script>
        // 2 秒ごとにポーリング
        setTimeout(function poll() {
            fetch(window.location.href, { headers: { Accept: "application/json" } })
                .then(r => r.json())
                .then(data => {
                    if (data.status !== "pending") {
                        window.location.reload();
                    } else {
                        setTimeout(poll, 2000);
                    }
                })
                .catch(() => setTimeout(poll, 3000));
        }, 2000);
    </script>

<#elseif status == "verified">
    <!-- 検証成功 -->
    <div class="alert alert-success d-flex align-items-center mb-4" role="alert">
        <span class="fs-4 me-3">✅</span>
        <div>
            <strong>検証成功</strong> — Photo ID の署名と有効期限が確認されました。
        </div>
    </div>

    <div class="card border-0 shadow-sm mb-4">
        <div class="card-header bg-light fw-semibold">証明書情報</div>
        <div class="card-body">
            <div class="row mb-2">
                <div class="col-4 text-muted">DocType</div>
                <div class="col-8 font-monospace small">${docType}</div>
            </div>
            <div class="row mb-2">
                <div class="col-4 text-muted">発行者証明書</div>
                <div class="col-8 small">${issuerSubject}</div>
            </div>
            <div class="row mb-2">
                <div class="col-4 text-muted">有効期間</div>
                <div class="col-8 small">${validFrom} 〜 ${validUntil}</div>
            </div>
        </div>
    </div>

    <div class="card border-0 shadow-sm mb-4">
        <div class="card-header bg-light fw-semibold">開示されたクレーム</div>
        <div class="card-body p-0">
            <table class="table table-hover mb-0">
                <thead class="table-light">
                    <tr>
                        <th class="ps-3">名前空間</th>
                        <th>要素名</th>
                        <th>値</th>
                    </tr>
                </thead>
                <tbody>
                <#list claimRows as row>
                    <tr>
                        <td class="ps-3 font-monospace small text-muted">${row.namespace}</td>
                        <td class="font-monospace small">${row.name}</td>
                        <td>${row.value}</td>
                    </tr>
                </#list>
                </tbody>
            </table>
        </div>
    </div>

    <div class="text-center">
        <a href="/verifier" class="btn btn-success">新しい検証を開始</a>
    </div>

<#elseif status == "failed">
    <!-- 検証失敗 -->
    <div class="alert alert-danger d-flex align-items-center mb-4" role="alert">
        <span class="fs-4 me-3">❌</span>
        <div>
            <strong>検証失敗</strong><br>
            <span class="small">${errorMessage!"不明なエラーが発生しました"}</span>
        </div>
    </div>

    <div class="text-center">
        <a href="/verifier" class="btn btn-outline-danger">やり直す</a>
    </div>

</#if>

</div>
</body>
</html>
