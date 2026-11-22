package com.alt.backend.db

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.util.UUID

data class JdbcConfig(val url: String, val user: String?, val password: String?, val mode: String)

/**
 * DATABASE_URL handling:
 *  - unset -> isolated H2 in-memory DB (dev default and tests; nothing to configure locally)
 *  - postgres://user:pass@host:port/db (Railway style) -> converted to jdbc:postgresql://
 *  - anything starting with jdbc: -> used verbatim
 */
object Persistence {
    private val log = LoggerFactory.getLogger("com.alt.backend.db.Persistence")

    fun parseDatabaseUrl(raw: String?, user: String? = null, password: String? = null): JdbcConfig {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return JdbcConfig("jdbc:h2:mem:alt-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", "", "h2-in-memory")
        if (s.startsWith("jdbc:")) return JdbcConfig(s, user ?: "sa", password ?: "", "jdbc")
        val body = when {
            s.startsWith("postgresql://") -> s.removePrefix("postgresql://")
            s.startsWith("postgres://") -> s.removePrefix("postgres://")
            else -> throw IllegalArgumentException("Unsupported DATABASE_URL: expected postgres://, postgresql:// or jdbc: prefix")
        }
        if (!body.contains('@') || body.substringBefore(':').isBlank()) {
            throw IllegalArgumentException("Unsupported DATABASE_URL: expected user:password@host:port/db")
        }
        val credentials = body.substringBeforeLast('@')
        val hostAndDb = body.substringAfterLast('@')
        val dbUser = decode(credentials.substringBefore(':'))
        val dbPassword = decode(credentials.substringAfter(':'))
        val hostPort = hostAndDb.substringBefore('/')
        val dbName = hostAndDb.substringAfter('/', "")
        if (hostPort.isBlank() || dbName.isBlank()) {
            throw IllegalArgumentException("Unsupported DATABASE_URL: expected user:password@host:port/db")
        }
        return JdbcConfig("jdbc:postgresql://$hostPort/$dbName", dbUser, dbPassword, "postgres")
    }

    private fun decode(v: String) = runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)

    fun connect(config: JdbcConfig): Database {
        val driver = when {
            config.url.startsWith("jdbc:h2:") -> "org.h2.Driver"
            config.url.startsWith("jdbc:postgresql:") -> "org.postgresql.Driver"
            else -> null
        }
        val db = if (driver != null) {
            Database.connect(config.url, driver = driver, user = config.user ?: "sa", password = config.password ?: "")
        } else {
            Database.connect(config.url, user = config.user ?: "sa", password = config.password ?: "")
        }
        transaction(db) { SchemaUtils.createMissingTablesAndColumns(UsersTable, AuthTokensTable, PlansTable, TravelsTable) }
        log.info("Database: {} ({})", config.mode, config.url.substringBefore('?'))
        return db
    }
}
