package org.vdcapps.issuer.domain.credential

import kotlin.test.Test
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
        assertNotEquals(store.generate(), store.generate())
    }

    @Test
    fun `generate は 75 文字の nonce を返す`() {
        // payload(24 bytes) + HMAC-SHA256(32 bytes) = 56 bytes
        // 56 bytes を base64url（パディングなし）にエンコードすると 75 文字
        val store = NonceStore()
        val nonce = store.generate()
        assertTrue(nonce.length == 75, "nonce の長さが 75 文字ではない: ${nonce.length}")
    }

    @Test
    fun `verify は生成した nonce で true を返す`() {
        val store = NonceStore()
        assertTrue(store.verify(store.generate()))
    }

    @Test
    fun `verify は不正な文字列で false を返す`() {
        val store = NonceStore()
        assertFalse(store.verify("invalid-nonce"))
    }

    @Test
    fun `verify は改ざんされた nonce で false を返す`() {
        val store = NonceStore()
        val nonce = store.generate()
        // 末尾1文字を変えて署名を壊す
        val tampered = nonce.dropLast(1) + if (nonce.last() == 'A') 'B' else 'A'
        assertFalse(store.verify(tampered))
    }

    @Test
    fun `verify は別のサーバーキーで署名された nonce で false を返す`() {
        val store1 = NonceStore()
        val store2 = NonceStore() // 別のランダムキーで初期化
        assertFalse(store2.verify(store1.generate()))
    }

    @Test
    fun `TTL 切れの nonce は verify で false を返す`() {
        // TTL = -1 秒 → 生成直後に有効期限切れ
        val store = NonceStore(ttlSeconds = -1L)
        assertFalse(store.verify(store.generate()))
    }
}
