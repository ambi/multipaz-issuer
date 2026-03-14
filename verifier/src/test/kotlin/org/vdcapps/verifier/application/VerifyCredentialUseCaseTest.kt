package org.vdcapps.verifier.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.vdcapps.verifier.domain.verification.InMemoryVerificationSessionRepository
import org.vdcapps.verifier.domain.verification.VerificationResult
import org.vdcapps.verifier.domain.verification.VerificationService
import org.vdcapps.verifier.domain.verification.VerificationSession
import org.vdcapps.verifier.infrastructure.multipaz.MdocVerifier
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class VerifyCredentialUseCaseTest {
    private fun setup(): Triple<VerifyCredentialUseCase, VerificationService, MdocVerifier> {
        val mdocVerifier = mockk<MdocVerifier>()
        val verificationService = VerificationService(InMemoryVerificationSessionRepository())
        val useCase = VerifyCredentialUseCase(verificationService, mdocVerifier)
        return Triple(useCase, verificationService, mdocVerifier)
    }

    private fun sampleResult() =
        VerificationResult(
            docType = "org.iso.23220.photoid.1",
            claims = mapOf("org.iso.23220.2" to mapOf("family_name" to "Yamada")),
            issuerCertificateSubject = "CN=Test Issuer",
            validFrom = Instant.now().minusSeconds(60),
            validUntil = Instant.now().plusSeconds(3600),
        )

    private fun validBase64(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    // ---- startVerification ----

    @Test
    fun `startVerification は PENDING セッションを生成して返す`() =
        runTest {
            val (useCase) = setup()
            val session = useCase.startVerification()
            assertEquals(VerificationSession.State.PENDING, session.state)
            assertNotNull(session.id)
            assertNotNull(session.nonce)
        }

    // ---- processVpToken 成功 ----

    @Test
    fun `processVpToken 成功時に VerificationResult を返す`() =
        runTest {
            val (useCase, _, mdocVerifier) = setup()
            val session = useCase.startVerification()
            val expected = sampleResult()
            every { mdocVerifier.verify(any(), any()) } returns expected

            val result = useCase.processVpToken(session, validBase64())
            assertEquals(expected, result)
        }

    @Test
    fun `processVpToken 成功時にセッション状態が VERIFIED になる`() =
        runTest {
            val (useCase, verificationService, mdocVerifier) = setup()
            val session = useCase.startVerification()
            every { mdocVerifier.verify(any(), any()) } returns sampleResult()

            useCase.processVpToken(session, validBase64())

            val updated = verificationService.findById(session.id)!!
            assertEquals(VerificationSession.State.VERIFIED, updated.state)
            assertNotNull(updated.result)
        }

    @Test
    fun `processVpToken 成功時に result がセッションに保存される`() =
        runTest {
            val (useCase, verificationService, mdocVerifier) = setup()
            val session = useCase.startVerification()
            val expected = sampleResult()
            every { mdocVerifier.verify(any(), any()) } returns expected

            useCase.processVpToken(session, validBase64())

            assertEquals(expected, verificationService.findById(session.id)!!.result)
        }

    @Test
    fun `processVpToken は RESPONSE_RECEIVED を経由して VERIFIED に遷移する`() =
        runTest {
            val (useCase, verificationService, mdocVerifier) = setup()
            val session = useCase.startVerification()
            // RESPONSE_RECEIVED への遷移は markVerified の前に起きるため
            // verify() 呼び出し前にセッションが RESPONSE_RECEIVED になることを確認
            every { mdocVerifier.verify(any(), any()) } answers {
                val mid = runBlocking { verificationService.findById(session.id)!! }
                assertEquals(VerificationSession.State.RESPONSE_RECEIVED, mid.state)
                sampleResult()
            }
            useCase.processVpToken(session, validBase64())
        }

    @Test
    fun `processVpToken は base64url パディングなしの vp_token を受け付ける`() =
        runTest {
            val (useCase, _, mdocVerifier) = setup()
            val session = useCase.startVerification()
            every { mdocVerifier.verify(any(), any()) } returns sampleResult()
            // パディングなし（正常）
            val noPadding = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(10))
            useCase.processVpToken(session, noPadding) // 例外が出ないこと
        }

    @Test
    fun `processVpToken は base64url パディングあり（末尾 =）も受け付ける`() =
        runTest {
            val (useCase, _, mdocVerifier) = setup()
            val session = useCase.startVerification()
            every { mdocVerifier.verify(any(), any()) } returns sampleResult()
            // パディングあり（trimEnd('=') で処理される）
            val withPadding = Base64.getUrlEncoder().encodeToString(ByteArray(10)) // ends with ==
            useCase.processVpToken(session, withPadding) // 例外が出ないこと
        }

    // ---- processVpToken 失敗：MdocVerifier が例外 ----

    @Test
    fun `processVpToken で MdocVerifier が例外を投げるとセッションが FAILED になる`() =
        runTest {
            val (useCase, verificationService, mdocVerifier) = setup()
            val session = useCase.startVerification()
            every { mdocVerifier.verify(any(), any()) } throws IllegalArgumentException("COSE_Sign1 検証失敗")

            assertFailsWith<IllegalArgumentException> {
                useCase.processVpToken(session, validBase64())
            }

            val updated = verificationService.findById(session.id)!!
            assertEquals(VerificationSession.State.FAILED, updated.state)
            assertEquals("COSE_Sign1 検証失敗", updated.errorMessage)
        }

    @Test
    fun `processVpToken で MdocVerifier が例外を投げると同じ例外が再スローされる`() =
        runTest {
            val (useCase, _, mdocVerifier) = setup()
            val session = useCase.startVerification()
            every { mdocVerifier.verify(any(), any()) } throws IllegalArgumentException("error-msg")

            val ex =
                assertFailsWith<IllegalArgumentException> {
                    useCase.processVpToken(session, validBase64())
                }
            assertEquals("error-msg", ex.message)
        }

    @Test
    fun `processVpToken で FAILED のとき result は null のまま`() =
        runTest {
            val (useCase, verificationService, mdocVerifier) = setup()
            val session = useCase.startVerification()
            every { mdocVerifier.verify(any(), any()) } throws RuntimeException("fail")

            assertFailsWith<RuntimeException> { useCase.processVpToken(session, validBase64()) }
            assertNull(verificationService.findById(session.id)!!.result)
        }

    // ---- processVpToken 失敗：不正な vp_token ----

    @Test
    fun `不正な base64 文字列の vp_token でセッションが FAILED になる`() =
        runTest {
            val (useCase, verificationService, _) = setup()
            val session = useCase.startVerification()

            assertFailsWith<Exception> {
                useCase.processVpToken(session, "!!!not-base64!!!")
            }

            assertEquals(VerificationSession.State.FAILED, verificationService.findById(session.id)!!.state)
        }

    // ---- MdocVerifier が正しいバイト列を受け取ることの確認 ----

    @Test
    fun `processVpToken は base64url デコードしたバイト列を MdocVerifier に渡す`() =
        runTest {
            val (useCase, _, mdocVerifier) = setup()
            val session = useCase.startVerification()
            val bytes = ByteArray(16) { (it + 1).toByte() }
            val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            every { mdocVerifier.verify(bytes, any()) } returns sampleResult()

            useCase.processVpToken(session, token)

            verify(exactly = 1) { mdocVerifier.verify(bytes, any()) }
        }
}
