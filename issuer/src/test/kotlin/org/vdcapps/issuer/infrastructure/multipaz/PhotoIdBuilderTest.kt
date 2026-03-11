package org.vdcapps.issuer.infrastructure.multipaz

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.multipaz.crypto.EcPublicKeyDoubleCoordinate
import org.vdcapps.issuer.domain.credential.PhotoIdCredential
import java.io.File
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhotoIdBuilderTest {
    private fun tempKeyStore(): IssuerKeyStore {
        val f = File.createTempFile("builder-ks-test", ".p12").apply { deleteOnExit() }
        f.delete()
        return IssuerKeyStore(f.absolutePath, "testpass")
    }

    private fun builder() = PhotoIdBuilder(tempKeyStore())

    private fun sampleCredential(portrait: ByteArray? = null) =
        PhotoIdCredential(
            familyName = "Yamada",
            givenName = "Taro",
            birthDate = LocalDate(1990, 1, 15),
            portrait = portrait,
            documentNumber = "DOC001234567",
            issuingCountry = "JP",
            issuingAuthority = "Test Issuer",
            issueDate = LocalDate(2025, 1, 1),
            expiryDate = LocalDate(2026, 12, 31),
        )

    private fun sampleHolderKey(): org.multipaz.crypto.EcPublicKey {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val kp = kpg.generateKeyPair()
        val pub = kp.public as ECPublicKey
        val xBytes = pub.w.affineX.toByteArray()
        val yBytes = pub.w.affineY.toByteArray()
        return PhotoIdBuilder(tempKeyStore()).ecPublicKeyFromCoordinates(xBytes, yBytes)
    }

    // ---- buildJwkSetJson ----

    @Test
    fun `buildJwkSetJson は keys 配列を含む JSON を返す`() {
        val json = builder().buildJwkSetJson()
        assertTrue(json.contains("\"keys\""), "keys フィールドが含まれること: $json")
    }

    @Test
    fun `buildJwkSetJson は EC P-256 の kty と crv を含む`() {
        val json = builder().buildJwkSetJson()
        assertTrue(json.contains("\"EC\""), "kty=EC が含まれること: $json")
        assertTrue(json.contains("\"P-256\""), "crv=P-256 が含まれること: $json")
    }

    @Test
    fun `buildJwkSetJson は use=sig alg=ES256 を含む`() {
        val json = builder().buildJwkSetJson()
        assertTrue(json.contains("\"sig\""), "use=sig が含まれること: $json")
        assertTrue(json.contains("\"ES256\""), "alg=ES256 が含まれること: $json")
    }

    @Test
    fun `buildJwkSetJson の x と y は空でない`() {
        val json = builder().buildJwkSetJson()
        assertTrue(json.contains("\"x\""), "x フィールドが含まれること: $json")
        assertTrue(json.contains("\"y\""), "y フィールドが含まれること: $json")
        // "x":"..." の値が空でないことを確認（ダブルクォートが連続していない）
        assertTrue(!json.contains("\"x\":\"\""), "x が空でないこと")
        assertTrue(!json.contains("\"y\":\"\""), "y が空でないこと")
    }

    @Test
    fun `buildJwkSetJson は同じキーストアで 2 回呼んでも同じ結果を返す`() {
        val b = builder()
        assertEquals(b.buildJwkSetJson(), b.buildJwkSetJson())
    }

    // ---- ecPublicKeyFromCoordinates ----

    @Test
    fun `ecPublicKeyFromCoordinates は 32 バイト x y から EcPublicKey を生成する`() {
        val kpg = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val kp = kpg.generateKeyPair()
        val pub = kp.public as ECPublicKey

        val b = builder()

        // Normalize x, y to 32 bytes (same logic as PhotoIdBuilder)
        fun normalize(bytes: ByteArray): ByteArray =
            when {
                bytes.size == 33 && bytes[0] == 0.toByte() -> bytes.copyOfRange(1, 33)
                bytes.size < 32 -> ByteArray(32 - bytes.size) + bytes
                else -> bytes
            }

        val x = normalize(pub.w.affineX.toByteArray())
        val y = normalize(pub.w.affineY.toByteArray())
        val result = b.ecPublicKeyFromCoordinates(x, y)

        assertNotNull(result)
        assertTrue(result is EcPublicKeyDoubleCoordinate)
    }

    @Test
    fun `ecPublicKeyFromCoordinates でゼロパディングが必要な座標も処理できる`() {
        // 32 バイト未満の座標（先頭が 0 の場合）
        val shortX = ByteArray(31) { 0x01 }
        val shortY = ByteArray(31) { 0x02 }
        val b = builder()
        // 例外が出ないこと
        val result = b.ecPublicKeyFromCoordinates(shortX, shortY)
        assertNotNull(result)
    }

    @Test
    fun `ecPublicKeyFromCoordinates で 33 バイト（先頭 0x00 付き）の座標も処理できる`() {
        // 33 バイトの座標（符号ビット対策で BigInteger が先頭に 0x00 を追加する場合）
        val paddedX =
            ByteArray(33).also {
                it[0] = 0x00
                it.fill(0x01, 1, 33)
            }
        val paddedY =
            ByteArray(33).also {
                it[0] = 0x00
                it.fill(0x02, 1, 33)
            }
        val b = builder()
        val result = b.ecPublicKeyFromCoordinates(paddedX, paddedY)
        assertNotNull(result)
    }

    // ---- buildCredential ----

    @Test
    fun `buildCredential は空でない base64url 文字列を返す`() =
        runTest {
            val b = builder()
            val holderKey = sampleHolderKey()
            val result = b.buildCredential(sampleCredential(), holderKey)
            assertTrue(result.isNotEmpty(), "buildCredential の結果が空でないこと")
        }

    @Test
    fun `buildCredential の結果は URL-safe base64 形式`() =
        runTest {
            val b = builder()
            val holderKey = sampleHolderKey()
            val result = b.buildCredential(sampleCredential(), holderKey)
            val urlSafe = Regex("^[A-Za-z0-9_-]+$")
            assertTrue(urlSafe.matches(result), "結果が URL-safe base64 形式であること: 先頭=${result.take(20)}")
        }

    @Test
    fun `buildCredential は portrait なしのクレデンシャルを処理できる`() =
        runTest {
            val b = builder()
            val holderKey = sampleHolderKey()
            val result = b.buildCredential(sampleCredential(portrait = null), holderKey)
            assertTrue(result.isNotEmpty())
        }

    @Test
    fun `buildCredential は portrait ありのクレデンシャルを処理できる`() =
        runTest {
            val b = builder()
            val holderKey = sampleHolderKey()
            val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
            val result = b.buildCredential(sampleCredential(portrait = photo), holderKey)
            assertTrue(result.isNotEmpty())
        }

    @Test
    fun `buildCredential を 2 回呼ぶと異なる結果を返す（random salt）`() =
        runTest {
            val b = builder()
            val holderKey = sampleHolderKey()
            val result1 = b.buildCredential(sampleCredential(), holderKey)
            val result2 = b.buildCredential(sampleCredential(), holderKey)
            // IssuerNamespaces の random salt が毎回変わるため結果も変わる
            assertTrue(result1 != result2, "buildCredential は毎回異なる結果を返すはず（random salt）")
        }
}
