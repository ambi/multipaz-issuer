package org.vdcapps.verifier.domain.verification

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerificationServiceTest {
    private fun service() = VerificationService(InMemoryVerificationSessionRepository())

    private fun sampleResult() =
        VerificationResult(
            docType = "org.iso.23220.photoid.1",
            claims = mapOf("org.iso.23220.2" to mapOf("family_name" to "Yamada")),
            issuerCertificateSubject = "CN=Test Issuer",
            validFrom = Instant.now().minusSeconds(60),
            validUntil = Instant.now().plusSeconds(3600),
        )

    // ---- createSession ----

    @Test
    fun `createSession は PENDING 状態のセッションを生成する`() =
        runTest {
            val session = service().createSession()
            assertEquals(VerificationSession.State.PENDING, session.state)
        }

    @Test
    fun `createSession は空でない ID と nonce を持つセッションを生成する`() =
        runTest {
            val session = service().createSession()
            assertTrue(session.id.isNotEmpty())
            assertTrue(session.nonce.isNotEmpty())
        }

    @Test
    fun `createSession は呼ぶたびに異なる ID と nonce を生成する`() =
        runTest {
            val svc = service()
            val s1 = svc.createSession()
            val s2 = svc.createSession()
            assertNotEquals(s1.id, s2.id)
            assertNotEquals(s1.nonce, s2.nonce)
        }

    @Test
    fun `createSession の nonce は URL-safe base64 形式`() =
        runTest {
            val session = service().createSession()
            val urlSafe = Regex("^[A-Za-z0-9_-]+$")
            assertTrue(urlSafe.matches(session.nonce), "nonce=${session.nonce}")
        }

    @Test
    fun `createSession で生成したセッションは findById で取得できる`() =
        runTest {
            val svc = service()
            val session = svc.createSession()
            assertEquals(session, svc.findById(session.id))
        }

    // ---- findById ----

    @Test
    fun `findById は存在しない ID に対して null を返す`() =
        runTest {
            assertNull(service().findById("ghost"))
        }

    // ---- markResponseReceived ----

    @Test
    fun `markResponseReceived で状態が RESPONSE_RECEIVED になる`() =
        runTest {
            val svc = service()
            val session = svc.createSession()
            svc.markResponseReceived(session)
            assertEquals(VerificationSession.State.RESPONSE_RECEIVED, svc.findById(session.id)!!.state)
        }

    @Test
    fun `markResponseReceived は他のフィールドを変更しない`() =
        runTest {
            val svc = service()
            val session = svc.createSession()
            svc.markResponseReceived(session)
            val updated = svc.findById(session.id)!!
            assertEquals(session.id, updated.id)
            assertEquals(session.nonce, updated.nonce)
            assertNull(updated.result)
            assertNull(updated.errorMessage)
        }

    // ---- markVerified ----

    @Test
    fun `markVerified で状態が VERIFIED になり result がセットされる`() =
        runTest {
            val svc = service()
            val session = svc.createSession()
            val result = sampleResult()
            svc.markVerified(session, result)

            val updated = svc.findById(session.id)!!
            assertEquals(VerificationSession.State.VERIFIED, updated.state)
            assertEquals(result, updated.result)
            assertNull(updated.errorMessage)
        }

    // ---- markFailed ----

    @Test
    fun `markFailed で状態が FAILED になり errorMessage がセットされる`() =
        runTest {
            val svc = service()
            val session = svc.createSession()
            svc.markFailed(session, "署名検証失敗")

            val updated = svc.findById(session.id)!!
            assertEquals(VerificationSession.State.FAILED, updated.state)
            assertEquals("署名検証失敗", updated.errorMessage)
            assertNull(updated.result)
        }

    // ---- 状態遷移の連鎖 ----

    @Test
    fun `PENDING → RESPONSE_RECEIVED → VERIFIED の状態遷移`() =
        runTest {
            val svc = service()
            val session = svc.createSession()

            svc.markResponseReceived(session)
            assertEquals(VerificationSession.State.RESPONSE_RECEIVED, svc.findById(session.id)!!.state)

            svc.markVerified(session, sampleResult())
            assertEquals(VerificationSession.State.VERIFIED, svc.findById(session.id)!!.state)
            assertNotNull(svc.findById(session.id)!!.result)
        }

    @Test
    fun `PENDING → RESPONSE_RECEIVED → FAILED の状態遷移`() =
        runTest {
            val svc = service()
            val session = svc.createSession()

            svc.markResponseReceived(session)
            svc.markFailed(session, "エラー")

            val updated = svc.findById(session.id)!!
            assertEquals(VerificationSession.State.FAILED, updated.state)
            assertEquals("エラー", updated.errorMessage)
        }
}
