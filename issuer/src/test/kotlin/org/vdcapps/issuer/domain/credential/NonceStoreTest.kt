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
        // 末尾から 2 文字目（インデックス 73）を変えて署名を壊す。
        // 末尾 1 文字（インデックス 74）は base64url のパディングビット（下位 2 ビット）を含み、
        // Java の Base64 デコーダはその 2 ビットを無視するため、
        // 一部の文字置換（例: 'A'↔'B'）ではデコード後のバイト列が変わらず
        // verify が true を返してしまう（約 6.25% の確率でフレーキー化する）。
        // インデックス 73 は 6 ビット全てが実データに対応するため安全に改ざんできる。
        val idx = nonce.length - 2
        val tampered = nonce.substring(0, idx) + (if (nonce[idx] == 'A') 'B' else 'A') + nonce.last()
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
