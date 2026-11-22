package com.alt.backend.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object UsersTable : Table("users") {
    val id = uuid("id")
    val email = text("email").uniqueIndex()
    val passwordHash = text("password_hash")
    val displayName = text("display_name").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AuthTokensTable : Table("auth_tokens") {
    val id = uuid("id")
    val userId = reference("user_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val tokenHash = varchar("token_hash", 64).uniqueIndex()
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object PlansTable : Table("plans") {
    val id = uuid("id")
    val userId = reference("user_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val label = varchar("label", 80).nullable()
    val originInput = varchar("origin_input", 120).nullable()
    val originName = varchar("origin_name", 120)
    val originId = varchar("origin_id", 120).nullable()
    val destinationInput = varchar("destination_input", 120).nullable()
    val destinationName = varchar("destination_name", 120)
    val destinationId = varchar("destination_id", 120).nullable()
    val departure = varchar("departure", 40)
    val journeySnapshot = text("journey_snapshot").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object TravelsTable : Table("travels") {
    val id = uuid("id")
    val userId = reference("user_id", UsersTable.id, onDelete = ReferenceOption.CASCADE)
    val planId = reference("plan_id", PlansTable.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val originName = varchar("origin_name", 120)
    val destinationName = varchar("destination_name", 120)
    val departure = varchar("departure", 40)
    val arrivalPlanned = varchar("arrival_planned", 40).nullable()
    val rating = integer("rating").nullable()
    val note = varchar("note", 500).nullable()
    val delayMinutes = integer("delay_minutes").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}
