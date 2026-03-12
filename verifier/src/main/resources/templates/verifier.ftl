<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Photo ID 検証 - Photo ID Verifier</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-50 min-h-screen">

<nav class="bg-emerald-600 shadow-md">
    <div class="max-w-5xl mx-auto px-4 py-3 flex items-center">
        <span class="text-white font-bold text-lg tracking-tight">Photo ID Verifier</span>
    </div>
</nav>

<main class="max-w-xl mx-auto px-4 py-10">
    <h2 class="text-2xl font-bold text-slate-800 text-center mb-1">Photo ID を検証</h2>
    <p class="text-slate-500 text-sm text-center mb-8">Multipaz Compose Wallet の Photo ID を提示してください。</p>

    <!-- Digital Credentials API -->
    <div class="bg-white rounded-2xl shadow-sm border border-slate-100 mb-5 overflow-hidden">
        <div class="px-5 py-3 bg-slate-50 border-b border-slate-100 font-semibold text-slate-700 text-sm">
            ブラウザで提示（Digital Credentials API）
        </div>
        <div class="p-6 text-center">
            <p class="text-slate-500 text-sm mb-5">デバイスの Wallet アプリから直接 Photo ID を提示します。</p>
            <button id="dcApiBtn" onclick="requestDcApi()"
                    class="bg-emerald-600 hover:bg-emerald-700 text-white font-semibold px-8 py-3 rounded-xl shadow-sm transition-colors text-sm">
                📲 Wallet から提示する
            </button>
            <div id="dcApiStatus" class="hidden mt-4 text-sm rounded-xl px-4 py-3"></div>
        </div>
    </div>

    <!-- QR code flow -->
    <div class="bg-white rounded-2xl shadow-sm border border-slate-100 mb-5 overflow-hidden">
        <div class="px-5 py-3 bg-slate-50 border-b border-slate-100 font-semibold text-slate-700 text-sm">
            QR コードで提示（OID4VP）
        </div>
        <div class="p-6">
            <p class="text-slate-500 text-sm text-center mb-4">Multipaz Compose Wallet の QR スキャンで提示します。</p>
            <div class="flex justify-center mb-4">
                <img src="data:image/png;base64,${qrCodeBase64}"
                     alt="OID4VP Request QR Code"
                     class="rounded-xl border border-slate-200 shadow-sm max-w-xs w-full">
            </div>
            <div class="flex gap-2">
                <input type="text" id="oid4vpUri" value="${oid4vpUri}" readonly
                       class="flex-1 bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-xs font-mono text-slate-600 focus:outline-none">
                <button type="button"
                        onclick="navigator.clipboard.writeText(document.getElementById('oid4vpUri').value)"
                        class="bg-slate-100 hover:bg-slate-200 text-slate-600 text-xs font-medium px-3 py-2 rounded-lg transition-colors whitespace-nowrap">
                    コピー
                </button>
            </div>
        </div>
    </div>

    <!-- Expiry -->
    <div class="bg-amber-50 border border-amber-100 rounded-xl px-4 py-3 text-sm text-amber-700">
        この検証リクエストは <strong>10 分間</strong>有効です。
        結果は <a href="/verifier/result/${sessionId}" class="underline">こちら</a> で確認できます。
    </div>
</main>

<script>
    const sessionId = "${sessionId}";
    const baseUrl = "${baseUrl}";
    const nonce = "${nonce}";
    const dcqlQuery = ${dcqlQueryJson};

    async function requestDcApi() {
        const btn = document.getElementById("dcApiBtn");
        const status = document.getElementById("dcApiStatus");

        if (!window.navigator.credentials || !window.DigitalCredential) {
            status.className = "mt-4 text-sm rounded-xl px-4 py-3 bg-amber-50 border border-amber-100 text-amber-700";
            status.textContent = "このブラウザは Digital Credentials API をサポートしていません。QR コードをお使いください。";
            status.classList.remove("hidden");
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
            status.className = "mt-4 text-sm rounded-xl px-4 py-3 bg-red-50 border border-red-100 text-red-700";
            status.textContent = "エラー: " + e.message;
            status.classList.remove("hidden");
            btn.disabled = false;
            btn.textContent = "📲 Wallet から提示する";
        }
    }
</script>

</body>
</html>
