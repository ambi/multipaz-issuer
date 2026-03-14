package org.vdcapps.issuer.web.util

/** レート制限の抽象インターフェース。インメモリ実装と Redis 実装を切り替えられる。 */
interface RateLimiterPort {
    /**
     * [key] に対してリクエストを許可するか判定する。
     * @return 許可する場合 true、制限超過の場合 false
     */
    fun isAllowed(key: String, maxPerWindow: Int): Boolean
}

/**
 * インメモリのスライディングウィンドウ式レート制限。シングルインスタンスでのみ有効。
 * 水平スケール環境では [RateLimiterPort] を Redis 実装に差し替えること。
 * キーは "<エンドポイント>:<IP>" の形式。
 */
internal object RateLimiter : RateLimiterPort {
    private val windows = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()
    internal const val WINDOW_MS = 60_000L

    /** テスト用: すべてのウィンドウをクリアする。本番コードからは呼ばないこと。 */
    fun clearForTesting() {
        windows.clear()
    }

    override fun isAllowed(
        key: String,
        maxPerWindow: Int,
    ): Boolean {
        val now = System.currentTimeMillis()
        val cutoff = now - WINDOW_MS
        // 1,000 エントリを超えたら期限切れエントリを掃除する（メモリ増大防止）
        if (windows.size > 1_000) {
            windows.entries.removeIf { (_, deque) ->
                synchronized(deque) { deque.isEmpty() || deque.last() < cutoff }
            }
        }
        val deque = windows.getOrPut(key) { ArrayDeque() }
        synchronized(deque) {
            while (deque.isNotEmpty() && deque.first() < cutoff) deque.removeFirst()
            if (deque.size >= maxPerWindow) return false
            deque.addLast(now)
            return true
        }
    }
}
