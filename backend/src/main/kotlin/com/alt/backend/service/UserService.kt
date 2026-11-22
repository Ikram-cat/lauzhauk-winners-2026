package com.alt.backend.service

import com.alt.backend.auth.PasswordHasher
import com.alt.backend.db.UsersTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class UserRecord(val id: UUID, val email: String, val displayName: String?)

class UserService(private val db: Database, private val clock: Clock) {

    sealed interface RegisterResult {
        data class Ok(val user: UserRecord) : RegisterResult
        data object EmailTaken : RegisterResult
    }

    suspend fun register(email: String, password: String, displayName: String?): RegisterResult {
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        val hash = PasswordHasher.hash(password)
        return newSuspendedTransaction(db = db) {
            if (UsersTable.selectAll().where { UsersTable.email eq email }.limit(1).firstOrNull() != null) {
                return@newSuspendedTransaction RegisterResult.EmailTaken
            }
            UsersTable.insert {
                it[UsersTable.id] = id
                it[UsersTable.email] = email
                it[passwordHash] = hash
                it[UsersTable.displayName] = displayName
                it[createdAt] = now
            }
            RegisterResult.Ok(UserRecord(id, email, displayName))
        }
    }

    /** Null on unknown email or wrong password — identical to the caller. */
    suspend fun login(email: String, password: String): UserRecord? =
        newSuspendedTransaction(db = db) {
            UsersTable.selectAll().where { UsersTable.email eq email }.limit(1).firstOrNull()?.let { row ->
                if (PasswordHasher.verify(password, row[UsersTable.passwordHash])) {
                    UserRecord(row[UsersTable.id], row[UsersTable.email], row[UsersTable.displayName])
                } else null
            }
        }

    suspend fun byId(id: UUID): UserRecord? =
        newSuspendedTransaction(db = db) {
            UsersTable.selectAll().where { UsersTable.id eq id }.limit(1).firstOrNull()?.let { row ->
                UserRecord(row[UsersTable.id], row[UsersTable.email], row[UsersTable.displayName])
            }
        }
}
