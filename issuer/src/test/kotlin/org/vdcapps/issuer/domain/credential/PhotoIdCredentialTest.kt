package org.vdcapps.issuer.domain.credential

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PhotoIdCredentialTest {
    private fun credential(
        documentNumber: String = "DOC001",
        familyName: String = "Yamada",
        givenName: String = "Taro",
    ) = PhotoIdCredential(
        familyName = familyName,
        givenName = givenName,
        birthDate = LocalDate(1990, 1, 15),
        portrait = null,
        documentNumber = documentNumber,
        issuingCountry = "JP",
        issuingAuthority = "Test Issuer",
        issueDate = LocalDate(2025, 1, 1),
        expiryDate = LocalDate(2026, 1, 1),
    )

    @Test
    fun `同じ documentNumber のクレデンシャルは等しい`() {
        val c1 = credential(documentNumber = "DOC001", familyName = "Yamada")
        val c2 = credential(documentNumber = "DOC001", familyName = "Suzuki")
        assertEquals(c1, c2)
    }

    @Test
    fun `異なる documentNumber のクレデンシャルは等しくない`() {
        val c1 = credential(documentNumber = "DOC001")
        val c2 = credential(documentNumber = "DOC002")
        assertNotEquals(c1, c2)
    }

    @Test
    fun `同じ documentNumber の hashCode は同じ`() {
        val c1 = credential(documentNumber = "DOC001", givenName = "Taro")
        val c2 = credential(documentNumber = "DOC001", givenName = "Jiro")
        assertEquals(c1.hashCode(), c2.hashCode())
    }

    @Test
    fun `異なる documentNumber の hashCode は通常異なる`() {
        val c1 = credential(documentNumber = "DOC001")
        val c2 = credential(documentNumber = "DOC999")
        assertNotEquals(c1.hashCode(), c2.hashCode())
    }

    @Test
    fun `portrait が null のクレデンシャルを生成できる`() {
        val c = credential()
        assertEquals(null, c.portrait)
    }

    @Test
    fun `portrait が非 null のクレデンシャルを生成できる`() {
        val photoBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        val c =
            credential().copy(portrait = photoBytes)
        assertEquals(3, c.portrait!!.size)
    }

    @Test
    fun `自身との比較は等しい`() {
        val c = credential()
        assertEquals(c, c)
    }

    @Test
    fun `null との比較は等しくない`() {
        val c = credential()
        assertNotEquals<Any?>(c, null)
    }
}
