<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Photo ID 検証 - Photo ID Verifier</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body>
<nav class="navbar navbar-dark bg-success">
    <div class="container">
        <span class="navbar-brand fw-bold">Photo ID Verifier</span>
    </div>
</nav>

<div class="container mt-4" style="max-width: 640px;">
    <h2 class="mb-2 text-center">Photo ID を検証</h2>
    <p class="text-center text-muted mb-4">Multipaz Compose Wallet の Photo ID を提示してください。</p>

    <!-- Digital Credentials API（ブラウザフロー） -->
    <div class="card border-0 shadow-sm mb-4">
        <div class="card-header bg-light fw-semibold">
            ブラウザで提示（Digital Credentials API）
        </div>
        <div class="card-body text-center py-4">
            <p class="text-muted mb-3">
                デバイスの Wallet アプリから直接 Photo ID を提示します。
            </p>
            <button id="dcApiBtn" class="btn btn-success btn-lg px-5" onclick="requestDcApi()">
                📲 Wallet から提示する
            </button>
            <div id="dcApiStatus" class="mt-3 d-none"></div>
        </div>
    </div>

    <!-- QR コードフロー（OID4VP） -->
    <div class="card border-0 shadow-sm mb-4">
        <div class="card-header bg-light fw-semibold">
            QR コードで提示（OID4VP）
        </div>
        <div class="card-body">
            <p class="text-muted mb-3 text-center">
                Multipaz Compose Wallet の QR スキャンで提示します。
            </p>
            <div class="text-center mb-3">
                <img src="data:image/png;base64,${qrCodeBase64}"
                     alt="OID4VP Request QR Code"
                     class="img-fluid border rounded shadow-sm" style="max-width: 280px;">
            </div>
            <div class="input-group input-group-sm">
                <input type="text" class="form-control form-control-sm font-monospace"
                       id="oid4vpUri" value="${oid4vpUri}" readonly>
                <button class="btn btn-outline-secondary btn-sm" type="button"
                        onclick="navigator.clipboard.writeText(document.getElementById('oid4vpUri').value)">
                    コピー
                </button>
            </div>
        </div>
    </div>

    <div class="alert alert-warning">
        この検証リクエストは <strong>10 分間</strong>有効です。
        結果は <a href="/verifier/result/${sessionId}">こちら</a> で確認できます。
    </div>
</div>

<script>
    const sessionId = "${sessionId}";
    const baseUrl = "${baseUrl}";
    const nonce = "${nonce}";
    const dcqlQuery = ${dcqlQueryJson};

    async function requestDcApi() {
        const btn = document.getElementById("dcApiBtn");
        const status = document.getElementById("dcApiStatus");

        if (!window.navigator.credentials || !window.DigitalCredential) {
            status.className = "mt-3 alert alert-warning";
            status.textContent = "このブラウザは Digital Credentials API をサポートしていません。QR コードをお使いください。";
            status.classList.remove("d-none");
            return;
        }

        btn.disabled = true;
        btn.textContent = "⏳ 待機中...";

        try {
            const credential = await navigator.credentials.get({
                digital: {
                    requests: [{
                        protocol: "openid4vp",
                        data: {
                            response_type: "vp_token",
                            client_id: baseUrl + "/verifier/response",
                            client_id_scheme: "redirect_uri",
                            response_mode: "direct_post",
                            response_uri: baseUrl + "/verifier/response",
                            nonce: nonce,
                            state: sessionId,
                            dcql_query: dcqlQuery,
                        }
                    }]
                }
            });

            // Wallet からのレスポンスを Verifier に送信
            // DCQL 応答: credential.data が { "photo-id": "<base64>" } の場合と plain string の場合に対応
            const rawVpToken = credential.data?.vp_token ?? credential.token ?? credential.data;
            const vpToken = (rawVpToken && typeof rawVpToken === "object")
                ? JSON.stringify(rawVpToken)
                : rawVpToken ?? JSON.stringify(credential);
            const resp = await fetch(baseUrl + "/verifier/response", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    vp_token: vpToken,
                    state: sessionId,
                })
            });

            if (resp.ok) {
                window.location.href = baseUrl + "/verifier/result/" + sessionId;
            } else {
                const err = await resp.json();
                throw new Error(err.error_description || "検証リクエストに失敗しました");
            }
        } catch (e) {
            status.className = "mt-3 alert alert-danger";
            status.textContent = "エラー: " + e.message;
            status.classList.remove("d-none");
            btn.disabled = false;
            btn.textContent = "📲 Wallet から提示する";
        }
    }
</script>
</body>
</html>
