<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>プロフィール確認 - Photo ID Issuer</title>
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

<main class="max-w-xl mx-auto px-4 py-10">
    <h2 class="text-2xl font-bold text-slate-800 mb-6">Photo ID 発行</h2>

    <#if error??>
    <div class="bg-red-50 border border-red-200 text-red-700 rounded-xl px-4 py-3 mb-6 text-sm">
        ${error}
    </div>
    </#if>

    <!-- Entra ID 情報 -->
    <div class="bg-white rounded-2xl shadow-sm border border-slate-100 mb-6 overflow-hidden">
        <div class="px-5 py-3 bg-slate-50 border-b border-slate-100 font-semibold text-slate-700 text-sm">
            Entra ID から取得したユーザー情報
        </div>
        <div class="divide-y divide-slate-50">
            <div class="flex px-5 py-3 text-sm">
                <span class="w-28 text-slate-400 shrink-0">表示名</span>
                <span class="text-slate-800">${displayName}</span>
            </div>
            <div class="flex px-5 py-3 text-sm">
                <span class="w-28 text-slate-400 shrink-0">メール</span>
                <span class="text-slate-800">${email!"(未設定)"}</span>
            </div>
            <div class="flex px-5 py-3 text-sm">
                <span class="w-28 text-slate-400 shrink-0">写真</span>
                <#if hasPhoto>
                <span class="bg-emerald-100 text-emerald-700 text-xs font-medium px-2 py-0.5 rounded-full">取得済み</span>
                <#else>
                <span class="bg-slate-100 text-slate-500 text-xs font-medium px-2 py-0.5 rounded-full">未設定</span>
                </#if>
            </div>
        </div>
    </div>

    <!-- 入力フォーム -->
    <form method="post" action="/issue">
        <div class="bg-white rounded-2xl shadow-sm border border-slate-100 mb-6 overflow-hidden">
            <div class="px-5 py-3 bg-slate-50 border-b border-slate-100 font-semibold text-slate-700 text-sm">
                証明書に含める情報を確認・入力
            </div>
            <div class="p-5 space-y-5">
                <div>
                    <label for="familyName" class="block text-sm font-medium text-slate-700 mb-1">
                        姓（Family Name）
                    </label>
                    <input type="text" id="familyName" name="familyName"
                           value="${familyName}" required
                           class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm text-slate-800 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:border-transparent">
                </div>
                <div>
                    <label for="givenName" class="block text-sm font-medium text-slate-700 mb-1">
                        名（Given Name）
                    </label>
                    <input type="text" id="givenName" name="givenName"
                           value="${givenName}" required
                           class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm text-slate-800 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:border-transparent">
                </div>
                <div>
                    <label for="birthDate" class="block text-sm font-medium text-slate-700 mb-1">
                        生年月日
                        <span class="text-red-500 ml-0.5">*</span>
                        <span class="text-slate-400 font-normal ml-1 text-xs">（Entra ID から自動取得できないため入力が必要です）</span>
                    </label>
                    <input type="date" id="birthDate" name="birthDate"
                           required max="${.now?string('yyyy-MM-dd')}"
                           class="w-full border border-slate-200 rounded-lg px-3 py-2 text-sm text-slate-800 focus:outline-none focus:ring-2 focus:ring-indigo-400 focus:border-transparent">
                </div>
            </div>
        </div>

        <button type="submit"
                class="w-full bg-indigo-600 hover:bg-indigo-700 text-white font-semibold py-3 rounded-xl shadow-sm transition-colors text-sm">
            📱 Wallet に Photo ID を発行する
        </button>
    </form>
</main>

</body>
</html>
