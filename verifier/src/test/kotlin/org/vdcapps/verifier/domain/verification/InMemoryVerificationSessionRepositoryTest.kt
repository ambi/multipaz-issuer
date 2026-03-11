package org.vdcapps.verifier.domain.verification

import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InMemoryVerificationSessionRepositoryTest {
    private fun repo() = InMemoryVerificationSessionRepository()

    private fun session(id: String = "sess-1") =
        VerificationSession(
            id = id,
            nonce = "nonce-$id",
            createdAt = Instant.now(),
            expiresAt = Instant.now().plusSeconds(600),
        )

    // ---- save / findById ----

    @Test
    fun `save して findById で取得できる`() =
        runTest {
            val r = repo()
            val s = session()
            r.save(s)
            assertEquals(s, r.findById(s.id))
        }

    @Test
    fun `findById は存在しない ID に対して null を返す`() =
        runTest {
            assertNull(repo().findById("ghost"))
        }

    // ---- update ----

    @Test
    fun `update で状態が書き換わる`() =
        runTest {
            val r = repo()
            val s = session()
            r.save(s)
            r.update(s.copy(state = VerificationSession.State.VERIFIED))
            assertEquals(VerificationSession.State.VERIFIED, r.findById(s.id)!!.state)
        }

    @Test
    fun `update で result がセットされる`() =
        runTest {
            val r = repo()
            val s = session()
            r.save(s)

            val result =
                VerificationResult(
                    docType = "org.iso.23220.photoid.1",
                    claims = mapOf("ns" to mapOf("key" to "value")),
                    issuerCertificateSubject = "CN=Test",
                    validFrom = Instant.now(),
                    validUntil = Instant.now().plusSeconds(3600),
                )
            r.update(s.copy(state = VerificationSession.State.VERIFIED, result = result))

            val updated = r.findById(s.id)!!
            assertEquals(result, updated.result)
            assertEquals(VerificationSession.State.VERIFIED, updated.state)
        }

    // ---- delete ----

    @Test
    fun `delete でセッションが削除される`() =
        runTest {
            val r = repo()
            val s = session()
            r.save(s)
            r.delete(s.id)
            assertNull(r.findById(s.id))
        }

    @Test
    fun `delete は存在しない ID に対してエラーを出さない`() =
        runTest {
            repo().delete("nonexistent") // 例外が出ないこと
        }

    // ---- 複数セッション ----

    @Test
    fun `複数セッションを独立して管理できる`() =
        runTest {
            val r = repo()
            val s1 = session("id-1")
            val s2 = session("id-2")
            r.save(s1)
            r.save(s2)

            assertEquals(s1, r.findById("id-1"))
            assertEquals(s2, r.findById("id-2"))

            r.delete("id-1")
            assertNull(r.findById("id-1"))
            assertEquals(s2, r.findById("id-2"))
        }
}
