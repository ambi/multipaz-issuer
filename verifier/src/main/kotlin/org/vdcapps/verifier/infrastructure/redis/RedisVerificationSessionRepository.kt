package org.vdcapps.verifier.infrastructure.redis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.vdcapps.verifier.domain.verification.VerificationResult
import org.vdcapps.verifier.domain.verification.VerificationSession
import org.vdcapps.verifier.domain.verification.VerificationSessionRepository
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.io.Closeable
import java.net.URI
import java.time.Instant

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class VerificationResultDto(
    val docType: String,
    val claims: Map<String, Map<String, String>>,
    val issuerCertificateSubject: String,
    val validFrom: Long,
    val validUntil: Long,
)

@Serializable
private data class VerificationSessionDto(
    val id: String,
    val nonce: String,
    val state: String,
    val result: VerificationResultDto?,
    val errorMessage: String?,
    val createdAt: Long,
    val expiresAt: Long,
)

private fun VerificationSession.toDto() =
    VerificationSessionDto(
        id = id,
        nonce = nonce,
        state = state.name,
        result =
            result?.let {
                VerificationResultDto(
                    docType = it.docType,
                    claims = it.claims,
                    issuerCertificateSubject = it.issuerCertificateSubject,
                    validFrom = it.validFrom.epochSecond,
                    validUntil = it.validUntil.epochSecond,
                )
            },
        errorMessage = errorMessage,
        createdAt = createdAt.epochSecond,
        expiresAt = expiresAt.epochSecond,
    )

private fun VerificationSessionDto.toSession() =
    VerificationSession(
        id = id,
        nonce = nonce,
        state = VerificationSession.State.valueOf(state),
        result =
            result?.let {
                VerificationResult(
                    docType = it.docType,
                    claims = it.claims,
                    issuerCertificateSubject = it.issuerCertificateSubject,
                    validFrom = Instant.ofEpochSecond(it.validFrom),
                    validUntil = Instant.ofEpochSecond(it.validUntil),
                )
            },
        errorMessage = errorMessage,
        createdAt = Instant.ofEpochSecond(createdAt),
        expiresAt = Instant.ofEpochSecond(expiresAt),
    )

/**
 * Redis 実装。セッションデータを JSON シリアライズして保存する。
 *
 * キー設計:
 *   verifier:session:{id} → セッション JSON（TTL = expiresAt まで）
 */
class RedisVerificationSessionRepository(
    redisUrl: String,
) : VerificationSessionRepository,
    Closeable {
    val pool =
        JedisPool(
            JedisPoolConfig().apply {
                maxTotal = 16
                maxIdle = 8
                minIdle = 2
                testOnBorrow = true
                testWhileIdle = true
            },
            URI(redisUrl),
        )

    override suspend fun save(session: VerificationSession): Unit =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis ->
                val encoded = json.encodeToString(session.toDto())
                val ttl = (session.expiresAt.epochSecond - Instant.now().epochSecond).coerceAtLeast(1)
                jedis.setex(sessionKey(session.id), ttl, encoded)
            }
        }

    override suspend fun findById(id: String): VerificationSession? =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis ->
                jedis.get(sessionKey(id))?.let { json.decodeFromString<VerificationSessionDto>(it).toSession() }
            }
        }

    override suspend fun update(session: VerificationSession) = save(session)

    override suspend fun delete(id: String): Unit =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis ->
                jedis.del(sessionKey(id))
            }
        }

    override fun close() = pool.close()

    private fun sessionKey(id: String) = "verifier:session:$id"
}
