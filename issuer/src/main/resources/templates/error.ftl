<!DOCTYPE html>
<html lang="ja">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <title>エラー - Photo ID Issuer</title>
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/bootstrap@5.3.3/dist/css/bootstrap.min.css">
</head>
<body>
<nav class="navbar navbar-dark bg-primary">
    <div class="container">
        <span class="navbar-brand fw-bold">Photo ID Issuer</span>
    </div>
</nav>

<div class="container mt-5" style="max-width: 600px;">
    <div class="alert alert-danger">
        <h4 class="alert-heading">エラーが発生しました</h4>
        <p>${message!"予期しないエラーが発生しました。"}</p>
    </div>
    <a href="/" class="btn btn-primary">ホームに戻る</a>
</div>
</body>
</html>
