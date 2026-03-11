package org.vdcapps.issuer.domain.credential

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class InMemoryIssuanceSessionRepositoryTest {
    private fun repo() = InMemoryIssuanceSessionRepository()

    private fun session(
        id: String = "sess-1",
        code: String = "code-1",
        token: String? = null,
    ) = IssuanceSession(
        id = id,
        preAuthorizedCode = code,
        credential = sampleCredential(),
        accessToken = token,
        createdAt = Instant.now(),
        expiresAt = Instant.now().plusSeconds(600),
    )

    private fun sampleCredential() =
        PhotoIdCredential(
            familyName = "Yamada",
            givenName = "Taro",
            birthDate = LocalDate(1990, 1, 15),
            portrait = null,
            documentNumber = "DOC001",
            issuingCountry = "JP",
            issuingAuthority = "Test Issuer",
            issueDate = LocalDate(2025, 1, 1),
            expiryDate = LocalDate(2026, 1, 1),
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
            val r = repo()
            assertNull(r.findById("ghost"))
        }

    // ---- findByPreAuthorizedCode ----

    @Test
    fun `save して findByPreAuthorizedCode で取得できる`() =
        runTest {
            val r = repo()
            val s = session(code = "my-code")
            r.save(s)
            assertEquals(s, r.findByPreAuthorizedCode("my-code"))
        }

    @Test
    fun `findByPreAuthorizedCode は存在しないコードに対して null を返す`() =
        runTest {
            val r = repo()
            assertNull(r.findByPreAuthorizedCode("no-code"))
        }

    // ---- findByAccessToken ----

    @Test
    fun `accessToken を持つセッションを findByAccessToken で取得できる`() =
        runTest {
            val r = repo()
            val s = session(token = "tok-abc")
            r.save(s)
            assertEquals(s, r.findByAccessToken("tok-abc"))
        }

    @Test
    fun `accessToken が null のセッションは findByAccessToken で取得できない`() =
        runTest {
            val r = repo()
            r.save(session(token = null))
            assertNull(r.findByAccessToken("tok-abc"))
        }

    @Test
    fun `findByAccessToken は存在しないトークンに対して null を返す`() =
        runTest {
            val r = repo()
            assertNull(r.findByAccessToken("ghost-token"))
        }

    // ---- update ----

    @Test
    fun `update でセッション内容が上書きされる`() =
        runTest {
            val r = repo()
            val s = session()
            r.save(s)

            val updated = s.copy(state = IssuanceSession.State.TOKEN_ISSUED, accessToken = "new-token")
            r.update(updated)

            val found = r.findById(s.id)
            assertNotNull(found)
            assertEquals(IssuanceSession.State.TOKEN_ISSUED, found.state)
            assertEquals("new-token", found.accessToken)
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
            val r = repo()
            r.delete("nonexistent") // 例外が出ないこと
        }

    // ---- 複数セッション ----

    @Test
    fun `複数セッションを独立して管理できる`() =
        runTest {
            val r = repo()
            val s1 = session(id = "s1", code = "c1")
            val s2 = session(id = "s2", code = "c2")
            r.save(s1)
            r.save(s2)

            assertEquals(s1, r.findById("s1"))
            assertEquals(s2, r.findById("s2"))
            assertEquals(s1, r.findByPreAuthorizedCode("c1"))
            assertEquals(s2, r.findByPreAuthorizedCode("c2"))
        }
}
