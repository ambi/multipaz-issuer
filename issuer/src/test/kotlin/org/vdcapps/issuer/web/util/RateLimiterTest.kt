package org.vdcapps.issuer.web.util

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RateLimiter のスライディングウィンドウ動作を検証する単体テスト。
 */
class RateLimiterTest {
    @BeforeTest
    fun setUp() {
        RateLimiter.clearForTesting()
    }

    @Test
    fun `制限内のリクエストは許可される`() {
        assertTrue(RateLimiter.isAllowed("test-key", 3))
        assertTrue(RateLimiter.isAllowed("test-key", 3))
        assertTrue(RateLimiter.isAllowed("test-key", 3))
    }

    @Test
    fun `制限を超えたリクエストは拒否される`() {
        repeat(5) { RateLimiter.isAllowed("key-limit", 5) }
        assertFalse(RateLimiter.isAllowed("key-limit", 5))
    }

    @Test
    fun `異なるキーは独立してカウントされる`() {
        repeat(5) { RateLimiter.isAllowed("key-a", 5) }
        // key-a は上限に達しているが key-b は独立している
        assertFalse(RateLimiter.isAllowed("key-a", 5))
        assertTrue(RateLimiter.isAllowed("key-b", 5))
    }

    @Test
    fun `上限が 1 の場合、2 回目は拒否される`() {
        assertTrue(RateLimiter.isAllowed("singleton", 1))
        assertFalse(RateLimiter.isAllowed("singleton", 1))
    }

    @Test
    fun `clearForTesting 後はカウントがリセットされる`() {
        repeat(5) { RateLimiter.isAllowed("reset-key", 5) }
        assertFalse(RateLimiter.isAllowed("reset-key", 5))

        RateLimiter.clearForTesting()

        assertTrue(RateLimiter.isAllowed("reset-key", 5))
    }

    @Test
    fun `maxPerWindow が 0 の場合は常に拒否される`() {
        assertFalse(RateLimiter.isAllowed("zero-limit", 0))
    }
}
