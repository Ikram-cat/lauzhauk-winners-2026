package com.alt.backend.service

import com.alt.backend.db.PlansTable
import org.jetbrains.exposed.sql.AndOp
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Provider-neutral place reference stored with each saved plan. */
data class PlaceRef(val input: String?, val name: String, val id: String?)

data class PlanRecord(
    val id: UUID, val userId: UUID, val label: String?, val origin: PlaceRef, val destination: PlaceRef,
    val departure: String, val journeyJson: String?, val createdAt: Instant
)

class PlanService(private val db: Database, private val clock: Clock) {

    suspend fun create(
        userId: UUID, origin: PlaceRef, destination: PlaceRef, departure: String, label: String?, journeyJson: String?
    ): PlanRecord = newSuspendedTransaction(db = db) {
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        PlansTable.insert {
            it[PlansTable.id] = id
            it[PlansTable.userId] = userId
            it[PlansTable.label] = label
            it[PlansTable.originInput] = origin.input
            it[PlansTable.originName] = origin.name
            it[PlansTable.originId] = origin.id
            it[PlansTable.destinationInput] = destination.input
            it[PlansTable.destinationName] = destination.name
            it[PlansTable.destinationId] = destination.id
            it[PlansTable.departure] = departure
            it[PlansTable.journeySnapshot] = journeyJson
            it[PlansTable.createdAt] = now
        }
        PlanRecord(id, userId, label, origin, destination, departure, journeyJson, now)
    }

    suspend fun list(userId: UUID, limit: Int): List<PlanRecord> =
        newSuspendedTransaction(db = db) {
            PlansTable.selectAll().where { PlansTable.userId eq userId }
                .orderBy(PlansTable.createdAt to SortOrder.DESC, PlansTable.id to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    PlanRecord(
                        row[PlansTable.id], userId, row[PlansTable.label],
                        PlaceRef(row[PlansTable.originInput], row[PlansTable.originName], row[PlansTable.originId]),
                        PlaceRef(row[PlansTable.destinationInput], row[PlansTable.destinationName], row[PlansTable.destinationId]),
                        row[PlansTable.departure], row[PlansTable.journeySnapshot], row[PlansTable.createdAt]
                    )
                }
        }

    /** Ownership-scoped lookup: another user's plan is indistinguishable from a missing one. */
    suspend fun findOwned(userId: UUID, planId: UUID): PlanRecord? =
        newSuspendedTransaction(db = db) {
            PlansTable.selectAll().where { (PlansTable.id eq planId) and (PlansTable.userId eq userId) }
                .limit(1).firstOrNull()?.let { row ->
                    PlanRecord(
                        row[PlansTable.id], userId, row[PlansTable.label],
                        PlaceRef(row[PlansTable.originInput], row[PlansTable.originName], row[PlansTable.originId]),
                        PlaceRef(row[PlansTable.destinationInput], row[PlansTable.destinationName], row[PlansTable.destinationId]),
                        row[PlansTable.departure], row[PlansTable.journeySnapshot], row[PlansTable.createdAt]
                    )
                }
        }

    suspend fun delete(userId: UUID, planId: UUID): Boolean =
        newSuspendedTransaction(db = db) {
            PlansTable.deleteWhere { (PlansTable.id eq planId) and (PlansTable.userId eq userId) }
        } > 0
}
