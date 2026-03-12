<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>検証結果 - Photo ID Verifier</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-50 min-h-screen">

<nav class="bg-emerald-600 shadow-md">
    <div class="max-w-5xl mx-auto px-4 py-3 flex items-center justify-between">
        <span class="text-white font-bold text-lg tracking-tight">Photo ID Verifier</span>
        <a href="/verifier"
           class="text-emerald-200 hover:text-white text-sm border border-emerald-400 hover:border-white px-3 py-1 rounded-lg transition-colors">
            新しい検証
        </a>
    </div>
</nav>

<main class="max-w-2xl mx-auto px-4 py-10">

<#if status == "pending">
    <!-- Polling -->
    <div class="text-center py-16">
        <div class="inline-block w-12 h-12 border-4 border-emerald-200 border-t-emerald-600 rounded-full animate-spin mb-6"></div>
        <h3 class="text-xl font-semibold text-slate-800 mb-2">Wallet からの応答を待っています</h3>
        <p class="text-slate-500 text-sm">QR コードをスキャンするか、Digital Credentials API で Photo ID を提示してください。</p>
    </div>
    <script>
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
    <!-- Success -->
    <div class="flex items-start gap-3 bg-emerald-50 border border-emerald-100 rounded-2xl px-5 py-4 mb-6">
        <span class="text-2xl mt-0.5">✅</span>
        <div>
            <p class="font-semibold text-emerald-800">検証成功</p>
            <p class="text-emerald-700 text-sm">Photo ID の署名と有効期限が確認されました。</p>
        </div>
    </div>

    <!-- Certificate info -->
    <div class="bg-white rounded-2xl shadow-sm border border-slate-100 mb-5 overflow-hidden">
        <div class="px-5 py-3 bg-slate-50 border-b border-slate-100 font-semibold text-slate-700 text-sm">
            証明書情報
        </div>
        <div class="divide-y divide-slate-50">
            <div class="flex px-5 py-3 text-sm">
                <span class="w-32 text-slate-400 shrink-0">DocType</span>
                <span class="font-mono text-slate-700 text-xs">${docType}</span>
            </div>
            <div class="flex px-5 py-3 text-sm">
                <span class="w-32 text-slate-400 shrink-0">発行者証明書</span>
                <span class="text-slate-700 text-xs">${issuerSubject}</span>
            </div>
            <div class="flex px-5 py-3 text-sm">
                <span class="w-32 text-slate-400 shrink-0">有効期間</span>
                <span class="text-slate-700 text-xs">${validFrom} 〜 ${validUntil}</span>
            </div>
        </div>
    </div>

    <!-- Claims table -->
    <div class="bg-white rounded-2xl shadow-sm border border-slate-100 mb-6 overflow-hidden">
        <div class="px-5 py-3 bg-slate-50 border-b border-slate-100 font-semibold text-slate-700 text-sm">
            開示されたクレーム
        </div>
        <div class="overflow-x-auto">
            <table class="w-full text-sm">
                <thead>
                    <tr class="bg-slate-50 border-b border-slate-100 text-slate-500 text-xs">
                        <th class="text-left px-5 py-2 font-medium">名前空間</th>
                        <th class="text-left px-4 py-2 font-medium">要素名</th>
                        <th class="text-left px-4 py-2 font-medium">値</th>
                    </tr>
                </thead>
                <tbody class="divide-y divide-slate-50">
                <#list claimRows as row>
                    <tr class="hover:bg-slate-50 transition-colors">
                        <td class="px-5 py-2.5 font-mono text-xs text-slate-400">${row.namespace}</td>
                        <td class="px-4 py-2.5 font-mono text-xs text-slate-600">${row.name}</td>
                        <td class="px-4 py-2.5 text-slate-800">${row.value}</td>
                    </tr>
                </#list>
                </tbody>
            </table>
        </div>
    </div>

    <div class="text-center">
        <a href="/verifier"
           class="inline-block bg-emerald-600 hover:bg-emerald-700 text-white font-medium px-6 py-2 rounded-lg transition-colors text-sm">
            新しい検証を開始
        </a>
    </div>

<#elseif status == "failed">
    <!-- Failure -->
    <div class="flex items-start gap-3 bg-red-50 border border-red-100 rounded-2xl px-5 py-4 mb-6">
        <span class="text-2xl mt-0.5">❌</span>
        <div>
            <p class="font-semibold text-red-800">検証失敗</p>
            <p class="text-red-700 text-sm">${errorMessage!"不明なエラーが発生しました"}</p>
        </div>
    </div>

    <div class="text-center">
        <a href="/verifier"
           class="inline-block border border-red-300 hover:border-red-400 text-red-600 hover:text-red-700 font-medium px-6 py-2 rounded-lg transition-colors text-sm">
            やり直す
        </a>
    </div>

</#if>

</main>

</body>
</html>
