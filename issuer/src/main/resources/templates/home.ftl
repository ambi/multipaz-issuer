<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>Photo ID Issuer</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-50 min-h-screen flex flex-col">

<nav class="bg-indigo-600 shadow-md">
    <div class="max-w-5xl mx-auto px-4 py-3 flex items-center">
        <span class="text-white font-bold text-lg tracking-tight">Photo ID Issuer</span>
    </div>
</nav>

<main class="flex-1 max-w-5xl mx-auto w-full px-4 py-12">

    <!-- Hero -->
    <div class="text-center mb-12">
        <h1 class="text-4xl font-bold text-slate-800 mb-4">Photo ID をデジタル証明書として発行</h1>
        <p class="text-slate-500 text-lg max-w-2xl mx-auto mb-8">
            Microsoft アカウントでログインすると、あなたのユーザー情報をもとに
            ISO/IEC TS 23220-4 準拠の Photo ID Verifiable Credential を発行します。<br>
            発行された VC は Multipaz Compose Wallet で受け取れます。
        </p>
        <a href="/auth/login"
           class="inline-flex items-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white font-semibold px-8 py-3 rounded-xl shadow-sm transition-colors">
            <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" fill="currentColor" viewBox="0 0 16 16">
                <path d="M7.462 0H0v7.19h7.462zM16 0H8.538v7.19H16zM7.462 8.211H0V16h7.462zm8.538 0H8.538V16H16z"/>
            </svg>
            Microsoft アカウントでログイン
        </a>
    </div>

    <!-- Feature cards -->
    <div class="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
        <div class="bg-white rounded-2xl shadow-sm border border-slate-100 p-6 text-center">
            <div class="text-4xl mb-3">🔐</div>
            <h3 class="font-semibold text-slate-800 mb-2">Entra ID 認証</h3>
            <p class="text-slate-500 text-sm">Microsoft Entra ID でセキュアに認証し、ユーザー情報を取得します。</p>
        </div>
        <div class="bg-white rounded-2xl shadow-sm border border-slate-100 p-6 text-center">
            <div class="text-4xl mb-3">📄</div>
            <h3 class="font-semibold text-slate-800 mb-2">ISO 23220-4 準拠</h3>
            <p class="text-slate-500 text-sm">ISO/IEC TS 23220-4 Photo ID フォーマットの mdoc 形式で発行します。</p>
        </div>
        <div class="bg-white rounded-2xl shadow-sm border border-slate-100 p-6 text-center">
            <div class="text-4xl mb-3">📱</div>
            <h3 class="font-semibold text-slate-800 mb-2">Multipaz Wallet 対応</h3>
            <p class="text-slate-500 text-sm">OID4VCI プロトコルで Multipaz Compose Wallet に直接発行します。</p>
        </div>
    </div>

    <!-- Verifier link -->
    <div class="max-w-md mx-auto">
        <div class="bg-white rounded-2xl shadow-sm border border-slate-100 p-6 text-center">
            <div class="text-4xl mb-3">🔍</div>
            <h3 class="font-semibold text-slate-800 mb-2">Photo ID を検証する</h3>
            <p class="text-slate-500 text-sm mb-4">発行した Photo ID を OID4VP / Digital Credentials API で検証します。</p>
            <a href="/verifier"
               class="inline-block bg-emerald-600 hover:bg-emerald-700 text-white font-medium px-6 py-2 rounded-lg transition-colors">
                Verifier を開く
            </a>
        </div>
    </div>

</main>

<footer class="text-center text-slate-400 text-sm py-6">
    Powered by <a href="https://github.com/openwallet-foundation/multipaz" class="text-indigo-500 hover:underline">Multipaz</a>
</footer>

</body>
</html>
