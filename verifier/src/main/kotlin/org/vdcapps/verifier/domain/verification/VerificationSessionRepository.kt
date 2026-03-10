package org.vdcapps.verifier.domain.verification

import java.util.concurrent.ConcurrentHashMap

interface VerificationSessionRepository {
    suspend fun save(session: VerificationSession)

    suspend fun findById(id: String): VerificationSession?

    suspend fun update(session: VerificationSession)

    suspend fun delete(id: String)
}

/** インメモリ実装。本番では永続化ストアに置き換える。 */
class InMemoryVerificationSessionRepository : VerificationSessionRepository {
    private val store = ConcurrentHashMap<String, VerificationSession>()

    override suspend fun save(session: VerificationSession) {
        store[session.id] = session
    }

    override suspend fun findById(id: String): VerificationSession? = store[id]

    override suspend fun update(session: VerificationSession) {
        store[session.id] = session
    }

    override suspend fun delete(id: String) {
        store.remove(id)
    }
}
