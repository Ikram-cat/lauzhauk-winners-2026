package com.alt.backend.auth

import com.alt.backend.db.AuthTokensTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Opaque bearer tokens: 32 random bytes hex-encoded, handed to the client once,
 * stored only as their SHA-256 hex digest. Resolution deletes expired rows.
 */
class TokenService(private val db: Database, private val clock: Clock) {

    data class IssuedToken(val token: String, val expiresAt: Instant)

    private val sha256 = MessageDigest.getInstance("SHA-256")
    private val random = SecureRandom()

    fun hashOf(raw: String): String = synchronized(sha256) {
        sha256.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    suspend fun issue(userId: UUID): IssuedToken {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val raw = bytes.joinToString("") { "%02x".format(it) }
        val now = Instant.now(clock)
        val expires = now.plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS)
        newSuspendedTransaction(db = db) {
            AuthTokensTable.insert {
                it[id] = UUID.randomUUID()
                it[AuthTokensTable.userId] = userId
                it[tokenHash] = hashOf(raw)
                it[createdAt] = now
                it[expiresAt] = expires
            }
        }
        return IssuedToken(raw, expires)
    }

    suspend fun resolve(raw: String): UUID? {
        val now = Instant.now(clock)
        return newSuspendedTransaction(db = db) {
            AuthTokensTable.deleteWhere { AuthTokensTable.expiresAt less now }
            AuthTokensTable.selectAll().where { AuthTokensTable.tokenHash eq hashOf(raw) }
                .limit(1).firstOrNull()?.get(AuthTokensTable.userId)
        }
    }

    suspend fun revoke(raw: String): Boolean =
        newSuspendedTransaction(db = db) {
            AuthTokensTable.deleteWhere { AuthTokensTable.tokenHash eq hashOf(raw) }
        } > 0
}
