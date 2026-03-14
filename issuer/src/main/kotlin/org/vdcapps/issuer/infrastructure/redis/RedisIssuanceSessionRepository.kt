package org.vdcapps.issuer.infrastructure.redis

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.vdcapps.issuer.domain.credential.IssuanceSession
import org.vdcapps.issuer.domain.credential.IssuanceSessionRepository
import org.vdcapps.issuer.domain.credential.PhotoIdCredential
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.io.Closeable
import java.net.URI
import java.time.Instant
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true }

@Serializable
private data class PhotoIdCredentialDto(
    val familyName: String,
    val givenName: String,
    val birthDate: String,
    val portrait: String?,
    val documentNumber: String,
    val issuingCountry: String,
    val issuingAuthority: String,
    val issueDate: String,
    val expiryDate: String,
)

@Serializable
private data class IssuanceSessionDto(
    val id: String,
    val preAuthorizedCode: String,
    val credential: PhotoIdCredentialDto,
    val accessToken: String?,
    val state: String,
    val createdAt: Long,
    val expiresAt: Long,
)

private fun IssuanceSession.toDto() =
    IssuanceSessionDto(
        id = id,
        preAuthorizedCode = preAuthorizedCode,
        credential =
            PhotoIdCredentialDto(
                familyName = credential.familyName,
                givenName = credential.givenName,
                birthDate = credential.birthDate.toString(),
                portrait = credential.portrait?.let { Base64.getEncoder().encodeToString(it) },
                documentNumber = credential.documentNumber,
                issuingCountry = credential.issuingCountry,
                issuingAuthority = credential.issuingAuthority,
                issueDate = credential.issueDate.toString(),
                expiryDate = credential.expiryDate.toString(),
            ),
        accessToken = accessToken,
        state = state.name,
        createdAt = createdAt.epochSecond,
        expiresAt = expiresAt.epochSecond,
    )

private fun IssuanceSessionDto.toSession() =
    IssuanceSession(
        id = id,
        preAuthorizedCode = preAuthorizedCode,
        credential =
            PhotoIdCredential(
                familyName = credential.familyName,
                givenName = credential.givenName,
                birthDate = LocalDate.parse(credential.birthDate),
                portrait = credential.portrait?.let { Base64.getDecoder().decode(it) },
                documentNumber = credential.documentNumber,
                issuingCountry = credential.issuingCountry,
                issuingAuthority = credential.issuingAuthority,
                issueDate = LocalDate.parse(credential.issueDate),
                expiryDate = LocalDate.parse(credential.expiryDate),
            ),
        accessToken = accessToken,
        state = IssuanceSession.State.valueOf(state),
        createdAt = Instant.ofEpochSecond(createdAt),
        expiresAt = Instant.ofEpochSecond(expiresAt),
    )

/**
 * Redis 実装。セッションデータを JSON シリアライズして保存する。
 *
 * キー設計:
 *   issuer:session:{id}            → セッション JSON（TTL = expiresAt まで）
 *   issuer:code:{preAuthorizedCode} → セッション ID（セカンダリインデックス）
 *   issuer:token:{accessToken}      → セッション ID（セカンダリインデックス）
 */
class RedisIssuanceSessionRepository(
    redisUrl: String,
) : IssuanceSessionRepository,
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

    override suspend fun save(session: IssuanceSession): Unit =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis ->
                val encoded = json.encodeToString(session.toDto())
                val ttl = (session.expiresAt.epochSecond - Instant.now().epochSecond).coerceAtLeast(1)
                jedis.setex(sessionKey(session.id), ttl, encoded)
                jedis.setex(codeKey(session.preAuthorizedCode), ttl, session.id)
                session.accessToken?.let { jedis.setex(tokenKey(it), ttl, session.id) }
            }
        }

    override suspend fun findByPreAuthorizedCode(code: String): IssuanceSession? =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis ->
                val id = jedis.get(codeKey(code)) ?: return@withContext null
                jedis.get(sessionKey(id))?.let { json.decodeFromString<IssuanceSessionDto>(it).toSession() }
            }
        }

    override suspend fun findByAccessToken(token: String): IssuanceSession? =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis ->
                val id = jedis.get(tokenKey(token)) ?: return@withContext null
                jedis.get(sessionKey(id))?.let { json.decodeFromString<IssuanceSessionDto>(it).toSession() }
            }
        }

    override suspend fun findById(id: String): IssuanceSession? =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis ->
                jedis.get(sessionKey(id))?.let { json.decodeFromString<IssuanceSessionDto>(it).toSession() }
            }
        }

    override suspend fun update(session: IssuanceSession) = save(session)

    override suspend fun delete(id: String): Unit =
        withContext(Dispatchers.IO) {
            pool.resource.use { jedis ->
                val encoded = jedis.get(sessionKey(id)) ?: return@withContext
                val dto = json.decodeFromString<IssuanceSessionDto>(encoded)
                jedis.del(sessionKey(id))
                jedis.del(codeKey(dto.preAuthorizedCode))
                dto.accessToken?.let { jedis.del(tokenKey(it)) }
            }
        }

    override fun close() = pool.close()

    private fun sessionKey(id: String) = "issuer:session:$id"

    private fun codeKey(code: String) = "issuer:code:$code"

    private fun tokenKey(token: String) = "issuer:token:$token"
}
