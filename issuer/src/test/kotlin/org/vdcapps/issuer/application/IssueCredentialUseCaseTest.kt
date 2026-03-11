package org.vdcapps.issuer.application

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.multipaz.crypto.EcPublicKey
import org.vdcapps.issuer.domain.credential.CredentialIssuanceService
import org.vdcapps.issuer.domain.credential.IssuanceSession
import org.vdcapps.issuer.domain.credential.PhotoIdCredential
import org.vdcapps.issuer.domain.identity.EntraUser
import org.vdcapps.issuer.infrastructure.multipaz.PhotoIdBuilder
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueCredentialUseCaseTest {
    private val issuanceService = mockk<CredentialIssuanceService>()
    private val photoIdBuilder = mockk<PhotoIdBuilder>()

    private fun useCase(validityDays: Int = 365) =
        IssueCredentialUseCase(
            issuanceService = issuanceService,
            photoIdBuilder = photoIdBuilder,
            issuingCountry = "JP",
            issuingAuthority = "Test Issuer",
            validityDays = validityDays,
        )

    private fun sampleUser(photo: ByteArray? = null) =
        EntraUser(
            id = "user-001",
            displayName = "Yamada Taro",
            givenName = "Taro",
            familyName = "Yamada",
            email = "taro@example.com",
            photo = photo,
        )

    private fun sampleSession(credential: PhotoIdCredential) =
        IssuanceSession(
            id = "sess-001",
            preAuthorizedCode = "code-001",
            credential = credential,
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(600),
        )

    private fun sampleCredential() =
        PhotoIdCredential(
            familyName = "Yamada",
            givenName = "Taro",
            birthDate = LocalDate(1990, 1, 15),
            portrait = null,
            documentNumber = "DOC001234567",
            issuingCountry = "JP",
            issuingAuthority = "Test Issuer",
            issueDate = LocalDate(2025, 1, 1),
            expiryDate = LocalDate(2026, 1, 1),
        )

    // ---- startIssuance ----

    @Test
    fun `startIssuance が familyName を正しく設定する`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase().startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")
            assertEquals("Yamada", session.credential.familyName)
        }

    @Test
    fun `startIssuance が givenName を正しく設定する`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase().startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")
            assertEquals("Taro", session.credential.givenName)
        }

    @Test
    fun `startIssuance が birthDate を正しく設定する`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase().startIssuance(sampleUser(), LocalDate(1990, 5, 20), "Yamada", "Taro")
            assertEquals(LocalDate(1990, 5, 20), session.credential.birthDate)
        }

    @Test
    fun `startIssuance が issuingCountry を正しく設定する`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase().startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")
            assertEquals("JP", session.credential.issuingCountry)
        }

    @Test
    fun `startIssuance が issuingAuthority を正しく設定する`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase().startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")
            assertEquals("Test Issuer", session.credential.issuingAuthority)
        }

    @Test
    fun `startIssuance の documentNumber は 12 文字`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase().startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")
            assertEquals(12, session.credential.documentNumber.length)
        }

    @Test
    fun `startIssuance の documentNumber は英大文字または数字のみ`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase().startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")
            assertTrue(
                session.credential.documentNumber.all { it.isUpperCase() || it.isDigit() },
                "documentNumber=${session.credential.documentNumber}",
            )
        }

    @Test
    fun `startIssuance の documentNumber は呼ぶたびに異なる`() =
        runTest {
            val docNumbers = mutableListOf<String>()
            coEvery { issuanceService.createSession(any()) } answers {
                val cred = firstArg<PhotoIdCredential>()
                docNumbers.add(cred.documentNumber)
                sampleSession(cred)
            }

            useCase().startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")
            useCase().startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")

            assertEquals(2, docNumbers.size)
            assertTrue(docNumbers[0] != docNumbers[1], "documentNumber が重複しています")
        }

    @Test
    fun `startIssuance の expiryDate は今日から validityDays 後`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase(validityDays = 365).startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")
            val javaToday = java.time.LocalDate.now(ZoneOffset.UTC)
            val expected = javaToday.plusDays(365)
            assertEquals(expected.year, session.credential.expiryDate.year)
            assertEquals(expected.monthValue, session.credential.expiryDate.monthNumber)
            assertEquals(expected.dayOfMonth, session.credential.expiryDate.dayOfMonth)
        }

    @Test
    fun `startIssuance の validityDays=0 のとき expiryDate は今日`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase(validityDays = 0).startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")
            val javaToday = java.time.LocalDate.now(ZoneOffset.UTC)
            assertEquals(javaToday.year, session.credential.expiryDate.year)
            assertEquals(javaToday.monthValue, session.credential.expiryDate.monthNumber)
            assertEquals(javaToday.dayOfMonth, session.credential.expiryDate.dayOfMonth)
        }

    @Test
    fun `startIssuance でユーザーの photo が portrait にセットされる`() =
        runTest {
            val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase().startIssuance(sampleUser(photo = photo), LocalDate(1990, 1, 15), "Yamada", "Taro")
            assertNotNull(session.credential.portrait)
            assertEquals(2, session.credential.portrait!!.size)
        }

    @Test
    fun `startIssuance でユーザーの photo が null のとき portrait は null`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            val session = useCase().startIssuance(sampleUser(photo = null), LocalDate(1990, 1, 15), "Yamada", "Taro")
            assertNull(session.credential.portrait)
        }

    @Test
    fun `startIssuance は issuanceService の createSession を 1 回呼ぶ`() =
        runTest {
            coEvery { issuanceService.createSession(any()) } answers { sampleSession(firstArg()) }

            useCase().startIssuance(sampleUser(), LocalDate(1990, 1, 15), "Yamada", "Taro")

            coVerify(exactly = 1) { issuanceService.createSession(any()) }
        }

    // ---- issueCredential ----

    @Test
    fun `issueCredential は photoIdBuilder の buildCredential を呼ぶ`() =
        runTest {
            val session = sampleSession(sampleCredential())
            val holderKey = mockk<EcPublicKey>()

            coEvery { photoIdBuilder.buildCredential(any(), any()) } returns "cbor-base64"
            coEvery { issuanceService.markCredentialIssued(any()) } returns Unit

            useCase().issueCredential(session, holderKey)

            coVerify(exactly = 1) { photoIdBuilder.buildCredential(session.credential, holderKey) }
        }

    @Test
    fun `issueCredential は markCredentialIssued を呼ぶ`() =
        runTest {
            val session = sampleSession(sampleCredential())
            val holderKey = mockk<EcPublicKey>()

            coEvery { photoIdBuilder.buildCredential(any(), any()) } returns "cbor-base64"
            coEvery { issuanceService.markCredentialIssued(any()) } returns Unit

            useCase().issueCredential(session, holderKey)

            coVerify(exactly = 1) { issuanceService.markCredentialIssued(session) }
        }

    @Test
    fun `issueCredential は buildCredential の戻り値をそのまま返す`() =
        runTest {
            val session = sampleSession(sampleCredential())
            val holderKey = mockk<EcPublicKey>()
            val expectedCbor = "my-credential-cbor-base64url"

            coEvery { photoIdBuilder.buildCredential(session.credential, holderKey) } returns expectedCbor
            coEvery { issuanceService.markCredentialIssued(any()) } returns Unit

            val result = useCase().issueCredential(session, holderKey)
            assertEquals(expectedCbor, result)
        }

    @Test
    fun `issueCredential は buildCredential の後に markCredentialIssued を呼ぶ`() =
        runTest {
            val session = sampleSession(sampleCredential())
            val holderKey = mockk<EcPublicKey>()
            val callOrder = mutableListOf<String>()

            coEvery { photoIdBuilder.buildCredential(any(), any()) } answers {
                callOrder.add("buildCredential")
                "cbor"
            }
            coEvery { issuanceService.markCredentialIssued(any()) } answers {
                callOrder.add("markCredentialIssued")
                Unit
            }

            useCase().issueCredential(session, holderKey)

            assertEquals(listOf("buildCredential", "markCredentialIssued"), callOrder)
        }
}
