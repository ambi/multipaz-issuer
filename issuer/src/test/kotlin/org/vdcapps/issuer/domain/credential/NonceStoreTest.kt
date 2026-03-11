package org.vdcapps.issuer.domain.credential

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class NonceStoreTest {
    @Test
    fun `generate は空でない文字列を返す`() {
        val store = NonceStore()
        val nonce = store.generate()
        assertTrue(nonce.isNotEmpty())
    }

    @Test
    fun `generate は URL-safe base64 文字だけを返す`() {
        val store = NonceStore()
        val nonce = store.generate()
        val urlSafeBase64 = Regex("^[A-Za-z0-9_-]+$")
        assertTrue(urlSafeBase64.matches(nonce), "nonce=$nonce に URL-unsafe な文字が含まれている")
    }

    @Test
    fun `generate は呼ぶたびに異なる nonce を返す`() {
        val store = NonceStore()
        val n1 = store.generate()
        val n2 = store.generate()
        assertNotEquals(n1, n2)
    }

    @Test
    fun `consume は生成した nonce で true を返す`() {
        val store = NonceStore()
        val nonce = store.generate()
        assertTrue(store.consume(nonce))
    }

    @Test
    fun `consume は存在しない nonce で false を返す`() {
        val store = NonceStore()
        assertFalse(store.consume("no-such-nonce"))
    }

    @Test
    fun `consume は同じ nonce を 2 回使えない（one-time use）`() {
        val store = NonceStore()
        val nonce = store.generate()
        assertTrue(store.consume(nonce))
        assertFalse(store.consume(nonce))
    }

    @Test
    fun `TTL 切れの nonce は consume で false を返す`() {
        // TTL = -1 秒 → 生成直後に有効期限切れ
        val store = NonceStore(ttlSeconds = -1L)
        val nonce = store.generate()
        assertFalse(store.consume(nonce), "期限切れ nonce は消費できないはず")
    }

    @Test
    fun `複数の nonce を独立して管理できる`() {
        val store = NonceStore()
        val n1 = store.generate()
        val n2 = store.generate()
        val n3 = store.generate()

        assertTrue(store.consume(n2))
        // n2 を消費しても n1, n3 は独立して生きている
        assertTrue(store.consume(n1))
        assertTrue(store.consume(n3))
    }

    @Test
    fun `generate は 32 バイト相当（43 文字）の nonce を返す`() {
        val store = NonceStore()
        val nonce = store.generate()
        // 32 bytes を base64url（パディングなし）にエンコードすると ceil(32*4/3) = 43 文字
        assertEquals(43, nonce.length)
    }
}
