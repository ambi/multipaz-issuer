package org.vdcapps.issuer.web.util

import io.ktor.server.application.ApplicationCall

/**
 * IP ベースのスライディングウィンドウ式レート制限。
 * キーは "<エンドポイント>:<IP>" の形式。
 * テストからリセットできるよう internal にする。
 */
internal object RateLimiter {
    private val windows = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()
    private const val WINDOW_MS = 60_000L

    /** テスト用: すべてのウィンドウをクリアする。本番コードからは呼ばないこと。 */
    fun clearForTesting() {
        windows.clear()
    }

    fun isAllowed(
        key: String,
        maxPerWindow: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        // マップが肥大化した場合にエントリを掃除する
        if (windows.size > 50_000) {
            val cutoff = now - WINDOW_MS
            windows.entries.removeIf { (_, deque) ->
                synchronized(deque) { deque.isEmpty() || deque.last() < cutoff }
            }
        }
        val deque = windows.getOrPut(key) { ArrayDeque() }
        synchronized(deque) {
            val cutoff = now - WINDOW_MS
            while (deque.isNotEmpty() && deque.first() < cutoff) deque.removeFirst()
            if (deque.size >= maxPerWindow) return false
            deque.addLast(now)
            return true
        }
    }
}

/** リクエスト元 IP を取得する。リバースプロキシ経由の場合は X-Forwarded-For を優先。 */
internal fun ApplicationCall.clientIp(): String {
    val forwardedFor = request.headers["X-Forwarded-For"]
    if (!forwardedFor.isNullOrBlank()) {
        val first = forwardedFor.split(",").firstOrNull()?.trim()
        if (!first.isNullOrBlank()) return first
    }
    return request.local.remoteHost
}
