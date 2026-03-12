<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>QR コードをスキャン - Photo ID Issuer</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-50 min-h-screen">

<nav class="bg-indigo-600 shadow-md">
    <div class="max-w-5xl mx-auto px-4 py-3 flex items-center justify-between">
        <span class="text-white font-bold text-lg tracking-tight">Photo ID Issuer</span>
        <a href="/auth/logout"
           class="text-indigo-200 hover:text-white text-sm border border-indigo-400 hover:border-white px-3 py-1 rounded-lg transition-colors">
            ログアウト
        </a>
    </div>
</nav>

<main class="max-w-lg mx-auto px-4 py-10">
    <h2 class="text-2xl font-bold text-slate-800 text-center mb-1">QR コードをスキャン</h2>
    <p class="text-slate-500 text-sm text-center mb-8">Multipaz Compose Wallet でスキャンしてください。</p>

    <!-- QR code -->
    <div class="flex justify-center mb-6">
        <img src="data:image/png;base64,${qrCodeBase64}" alt="Credential Offer QR Code"
             class="rounded-2xl border border-slate-200 shadow-sm max-w-xs w-full">
    </div>

    <!-- Offer URI -->
    <div class="bg-white rounded-2xl shadow-sm border border-slate-100 p-5 mb-5">
        <p class="text-xs font-medium text-slate-400 mb-2">Credential Offer URI</p>
        <div class="flex gap-2">
            <input type="text" id="offerUri" value="${offerUri}" readonly
                   class="flex-1 bg-slate-50 border border-slate-200 rounded-lg px-3 py-2 text-xs font-mono text-slate-600 focus:outline-none">
            <button type="button"
                    onclick="navigator.clipboard.writeText(document.getElementById('offerUri').value)"
                    class="bg-slate-100 hover:bg-slate-200 text-slate-600 text-xs font-medium px-3 py-2 rounded-lg transition-colors whitespace-nowrap">
                コピー
            </button>
        </div>
    </div>

    <!-- Steps -->
    <div class="bg-indigo-50 border border-indigo-100 rounded-2xl p-5 mb-4">
        <p class="text-sm font-semibold text-indigo-800 mb-2">手順</p>
        <ol class="text-sm text-indigo-700 space-y-1 list-decimal list-inside">
            <li>Multipaz Compose Wallet アプリを開く</li>
            <li>「資格情報を追加」または QR スキャンを選択</li>
            <li>上記の QR コードをスキャン</li>
            <li>Photo ID を受け取る</li>
        </ol>
    </div>

    <!-- Expiry warning -->
    <div class="bg-amber-50 border border-amber-100 rounded-xl px-4 py-3 text-sm text-amber-700 mb-6">
        この QR コードは <strong>10 分間</strong>有効です。
    </div>

    <div class="text-center">
        <a href="/profile"
           class="inline-block border border-slate-300 hover:border-slate-400 text-slate-600 hover:text-slate-800 text-sm font-medium px-5 py-2 rounded-lg transition-colors">
            別の証明書を発行する
        </a>
    </div>
</main>

</body>
</html>
