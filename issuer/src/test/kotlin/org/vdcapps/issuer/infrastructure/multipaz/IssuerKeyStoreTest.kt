package org.vdcapps.issuer.infrastructure.multipaz

import java.io.File
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IssuerKeyStoreTest {
    private fun tempKeyStorePath(): String = File.createTempFile("issuer-ks-test", ".p12").apply { deleteOnExit() }.absolutePath

    // ---- 新規生成 ----

    @Test
    fun `存在しないファイルパスで初期化すると新規生成される`() {
        val path = tempKeyStorePath()
        File(path).delete() // 確実に存在しない状態に
        val ks = IssuerKeyStore(path, "testpass")
        assertNotNull(ks.privateKey)
        assertNotNull(ks.certificate)
    }

    @Test
    fun `新規生成された秘密鍵は EC 鍵`() {
        val path = tempKeyStorePath()
        File(path).delete()
        val ks = IssuerKeyStore(path, "testpass")
        assertTrue(ks.privateKey is ECPrivateKey, "秘密鍵が EC 鍵であること")
    }

    @Test
    fun `新規生成された公開鍵は EC P-256 鍵`() {
        val path = tempKeyStorePath()
        File(path).delete()
        val ks = IssuerKeyStore(path, "testpass")
        val pubKey = ks.certificate.publicKey
        assertTrue(pubKey is ECPublicKey, "公開鍵が EC 鍵であること")
        val spec = (pubKey as ECPublicKey).params
        // P-256: フィールドサイズ 256 bit
        assertEquals(256, spec.curve.field.fieldSize)
    }

    @Test
    fun `新規生成されると PKCS12 ファイルが作られる`() {
        val path = tempKeyStorePath()
        File(path).delete()
        IssuerKeyStore(path, "testpass")
        assertTrue(File(path).exists(), "PKCS12 ファイルが存在すること")
        assertTrue(File(path).length() > 0, "PKCS12 ファイルが空でないこと")
    }

    @Test
    fun `新規生成された証明書の Subject に VDC Apps が含まれる`() {
        val path = tempKeyStorePath()
        File(path).delete()
        val ks = IssuerKeyStore(path, "testpass")
        assertTrue(
            ks.certificate.subjectX500Principal.name
                .contains("VDC Apps"),
            "Subject: ${ks.certificate.subjectX500Principal.name}",
        )
    }

    @Test
    fun `certChainDer は 1 要素のリストで証明書 DER バイト列を含む`() {
        val path = tempKeyStorePath()
        File(path).delete()
        val ks = IssuerKeyStore(path, "testpass")
        assertEquals(1, ks.certChainDer.size)
        assertTrue(ks.certChainDer[0].isNotEmpty())
        // DER 先頭は SEQUENCE タグ (0x30)
        assertEquals(0x30.toByte(), ks.certChainDer[0][0])
    }

    @Test
    fun `有効期限がおよそ 10 年後`() {
        val path = tempKeyStorePath()
        File(path).delete()
        val ks = IssuerKeyStore(path, "testpass")
        val now = System.currentTimeMillis()
        val tenYearsMs = 10L * 365 * 24 * 60 * 60 * 1000
        val notAfterMs = ks.certificate.notAfter.time
        // ±30日の誤差を許容
        val toleranceMs = 30L * 24 * 60 * 60 * 1000
        assertTrue(notAfterMs > now + tenYearsMs - toleranceMs, "notAfter が 10 年後より前: ${ks.certificate.notAfter}")
        assertTrue(notAfterMs < now + tenYearsMs + toleranceMs, "notAfter が 10 年後より後: ${ks.certificate.notAfter}")
    }

    // ---- 既存ファイルの読み込み ----

    @Test
    fun `既存の PKCS12 ファイルを読み込める`() {
        val path = tempKeyStorePath()
        File(path).delete()
        // 1回目で生成
        val ks1 = IssuerKeyStore(path, "testpass")
        // 2回目で読み込み
        val ks2 = IssuerKeyStore(path, "testpass")
        assertNotNull(ks2.privateKey)
        assertNotNull(ks2.certificate)
    }

    @Test
    fun `読み込み時は新規生成時と同じ証明書 Subject を持つ`() {
        val path = tempKeyStorePath()
        File(path).delete()
        val ks1 = IssuerKeyStore(path, "testpass")
        val subjectFirst = ks1.certificate.subjectX500Principal.name

        val ks2 = IssuerKeyStore(path, "testpass")
        assertEquals(subjectFirst, ks2.certificate.subjectX500Principal.name)
    }

    @Test
    fun `読み込み時は同じシリアル番号を持つ`() {
        val path = tempKeyStorePath()
        File(path).delete()
        val ks1 = IssuerKeyStore(path, "testpass")
        val serial1 = ks1.certificate.serialNumber

        val ks2 = IssuerKeyStore(path, "testpass")
        assertEquals(serial1, ks2.certificate.serialNumber)
    }

    @Test
    fun `読み込み時は同じ公開鍵を持つ`() {
        val path = tempKeyStorePath()
        File(path).delete()
        val ks1 = IssuerKeyStore(path, "testpass")
        val pubKey1 =
            ks1.certificate.publicKey.encoded
                .toList()

        val ks2 = IssuerKeyStore(path, "testpass")
        assertEquals(
            pubKey1,
            ks2.certificate.publicKey.encoded
                .toList(),
        )
    }

    @Test
    fun `certChainDer の内容が証明書の DER エンコードと一致する`() {
        val path = tempKeyStorePath()
        File(path).delete()
        val ks = IssuerKeyStore(path, "testpass")
        assertEquals(ks.certificate.encoded.toList(), ks.certChainDer[0].toList())
    }

    // ---- サブディレクトリ自動生成 ----

    @Test
    fun `親ディレクトリが存在しない場合でも自動生成される`() {
        val tempRoot = File(System.getProperty("java.io.tmpdir"), "ks-test-${System.nanoTime()}")
        val path = "$tempRoot/subdir/issuer.p12"
        try {
            IssuerKeyStore(path, "testpass")
            assertTrue(File(path).exists(), "サブディレクトリ内のファイルが生成されること")
        } finally {
            File(path).delete()
            tempRoot.deleteRecursively()
        }
    }
}
