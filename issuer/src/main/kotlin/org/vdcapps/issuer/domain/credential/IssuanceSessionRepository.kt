package org.vdcapps.issuer.domain.credential

import java.util.concurrent.ConcurrentHashMap

interface IssuanceSessionRepository {
    suspend fun save(session: IssuanceSession)

    suspend fun findByPreAuthorizedCode(code: String): IssuanceSession?

    suspend fun findByAccessToken(token: String): IssuanceSession?

    suspend fun findById(id: String): IssuanceSession?

    suspend fun update(session: IssuanceSession)

    suspend fun delete(id: String)
}

/** インメモリ実装。本番では永続化ストアに置き換える。 */
class InMemoryIssuanceSessionRepository : IssuanceSessionRepository {
    private val store = ConcurrentHashMap<String, IssuanceSession>()

    override suspend fun save(session: IssuanceSession) {
        store[session.id] = session
    }

    override suspend fun findByPreAuthorizedCode(code: String): IssuanceSession? = store.values.find { it.preAuthorizedCode == code }

    override suspend fun findByAccessToken(token: String): IssuanceSession? = store.values.find { it.accessToken == token }

    override suspend fun findById(id: String): IssuanceSession? = store[id]

    override suspend fun update(session: IssuanceSession) {
        store[session.id] = session
    }

    override suspend fun delete(id: String) {
        store.remove(id)
    }
}
