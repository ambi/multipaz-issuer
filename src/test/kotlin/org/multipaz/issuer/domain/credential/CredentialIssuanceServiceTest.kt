package org.multipaz.issuer.domain.credential

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class CredentialIssuanceServiceTest {

    private fun createService(): CredentialIssuanceService =
        CredentialIssuanceService(InMemoryIssuanceSessionRepository())

    private fun sampleCredential() = PhotoIdCredential(
        familyName = "Yamada",
        givenName = "Taro",
        birthDate = LocalDate(1990, 1, 15),
        portrait = null,
        documentNumber = "TESTDOC001",
        issuingCountry = "JP",
        issuingAuthority = "Test Issuer",
        issueDate = LocalDate(2025, 1, 1),
        expiryDate = LocalDate(2026, 1, 1),
    )

    @Test
    fun `createSession は PENDING 状態のセッションを生成する`() = runTest {
        val service = createService()
        val session = service.createSession(sampleCredential())

        assertEquals(IssuanceSession.State.PENDING, session.state)
        assertNotNull(session.preAuthorizedCode)
        assertNotNull(session.cNonce)
        assertNotNull(session.id)
    }

    @Test
    fun `exchangePreAuthorizedCode は有効なコードで TOKEN_ISSUED に遷移する`() = runTest {
        val service = createService()
        val session = service.createSession(sampleCredential())

        val result = service.exchangePreAuthorizedCode(session.preAuthorizedCode)

        assertNotNull(result)
        val (accessToken, updatedSession) = result
        assertNotNull(accessToken)
        assertEquals(IssuanceSession.State.TOKEN_ISSUED, updatedSession.state)
        assertNotNull(updatedSession.accessToken)
        assertEquals(accessToken, updatedSession.accessToken)
    }

    @Test
    fun `exchangePreAuthorizedCode は無効なコードで null を返す`() = runTest {
        val service = createService()
        service.createSession(sampleCredential())

        val result = service.exchangePreAuthorizedCode("invalid-code")
        assertNull(result)
    }

    @Test
    fun `同じ pre-authorized_code を 2 回使うと 2 回目は null を返す`() = runTest {
        val service = createService()
        val session = service.createSession(sampleCredential())

        val first = service.exchangePreAuthorizedCode(session.preAuthorizedCode)
        assertNotNull(first)

        val second = service.exchangePreAuthorizedCode(session.preAuthorizedCode)
        assertNull(second)
    }

    @Test
    fun `findSessionByAccessToken は TOKEN_ISSUED セッションを返す`() = runTest {
        val service = createService()
        val session = service.createSession(sampleCredential())
        val (accessToken, _) = service.exchangePreAuthorizedCode(session.preAuthorizedCode)!!

        val found = service.findSessionByAccessToken(accessToken)
        assertNotNull(found)
        assertEquals(IssuanceSession.State.TOKEN_ISSUED, found.state)
    }

    @Test
    fun `markCredentialIssued は状態を CREDENTIAL_ISSUED に更新する`() = runTest {
        val service = createService()
        val session = service.createSession(sampleCredential())
        val (_, tokenSession) = service.exchangePreAuthorizedCode(session.preAuthorizedCode)!!

        service.markCredentialIssued(tokenSession)

        val found = service.findSessionById(session.id)
        assertNotNull(found)
        assertEquals(IssuanceSession.State.CREDENTIAL_ISSUED, found.state)
    }
}
