package org.vdcapps.issuer.infrastructure.redis

import org.slf4j.LoggerFactory
import org.vdcapps.issuer.web.util.RateLimiterPort
import redis.clients.jedis.JedisPool

private val logger = LoggerFactory.getLogger("RedisRateLimiter")

/**
 * Redis の INCR + PEXPIRE による固定ウィンドウ式レート制限。
 * 複数インスタンス間でカウントを共有するため、水平スケール環境で使用する。
 *
 * ウィンドウ境界で最大 2× のバーストを許容するが、不正利用防止には十分。
 * Redis 障害時はフェイルオープン（リクエストを許可）して可用性を優先する。
 */
internal class RedisRateLimiter(
    private val pool: JedisPool,
    private val windowMs: Long = 60_000L,
) : RateLimiterPort {
    override fun isAllowed(key: String, maxPerWindow: Int): Boolean =
        try {
            pool.resource.use { jedis ->
                val redisKey = "ratelimit:$key"
                val count = jedis.incr(redisKey)
                if (count == 1L) jedis.pexpire(redisKey, windowMs)
                count <= maxPerWindow
            }
        } catch (e: Exception) {
            logger.warn("Redis レート制限エラー。リクエストを許可します。", e)
            true // fail open: Redis 障害より可用性を優先
        }
}
