<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>エラー - Photo ID Verifier</title>
    <script src="https://cdn.tailwindcss.com"></script>
</head>
<body class="bg-slate-50 min-h-screen">

<nav class="bg-emerald-600 shadow-md">
    <div class="max-w-5xl mx-auto px-4 py-3 flex items-center">
        <span class="text-white font-bold text-lg tracking-tight">Photo ID Verifier</span>
    </div>
</nav>

<main class="max-w-lg mx-auto px-4 py-16">
    <div class="bg-white rounded-2xl shadow-sm border border-red-100 p-8 text-center">
        <div class="text-5xl mb-4">⚠️</div>
        <h2 class="text-xl font-bold text-slate-800 mb-2">エラーが発生しました</h2>
        <p class="text-slate-500 text-sm mb-6">${message!"予期しないエラーが発生しました。"}</p>
        <a href="/verifier"
           class="inline-block bg-emerald-600 hover:bg-emerald-700 text-white font-medium px-6 py-2 rounded-lg transition-colors text-sm">
            検証ページに戻る
        </a>
    </div>
</main>

</body>
</html>
