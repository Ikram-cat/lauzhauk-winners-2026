package com.alt.backend.service

import com.alt.backend.db.TravelsTable
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class TravelRecord(
    val id: UUID, val userId: UUID, val planId: UUID?, val originName: String, val destinationName: String,
    val departure: String, val arrivalPlanned: String?, val rating: Int?, val note: String?,
    val delayMinutes: Int?, val createdAt: Instant
)

class TravelService(private val db: Database, private val clock: Clock) {

    suspend fun create(
        userId: UUID, planId: UUID?, originName: String, destinationName: String, departure: String,
        arrivalPlanned: String?, rating: Int?, note: String?, delayMinutes: Int?
    ): TravelRecord = newSuspendedTransaction(db = db) {
        val id = UUID.randomUUID()
        val now = Instant.now(clock)
        TravelsTable.insert {
            it[TravelsTable.id] = id
            it[TravelsTable.userId] = userId
            it[TravelsTable.planId] = planId
            it[TravelsTable.originName] = originName
            it[TravelsTable.destinationName] = destinationName
            it[TravelsTable.departure] = departure
            it[TravelsTable.arrivalPlanned] = arrivalPlanned
            it[TravelsTable.rating] = rating
            it[TravelsTable.note] = note
            it[TravelsTable.delayMinutes] = delayMinutes
            it[TravelsTable.createdAt] = now
        }
        TravelRecord(id, userId, planId, originName, destinationName, departure, arrivalPlanned, rating, note, delayMinutes, now)
    }

    suspend fun list(userId: UUID, limit: Int): List<TravelRecord> =
        newSuspendedTransaction(db = db) {
            TravelsTable.selectAll().where { TravelsTable.userId eq userId }
                .orderBy(TravelsTable.createdAt to SortOrder.DESC, TravelsTable.id to SortOrder.DESC)
                .limit(limit)
                .map { row ->
                    TravelRecord(
                        row[TravelsTable.id], userId, row[TravelsTable.planId],
                        row[TravelsTable.originName], row[TravelsTable.destinationName],
                        row[TravelsTable.departure], row[TravelsTable.arrivalPlanned],
                        row[TravelsTable.rating], row[TravelsTable.note], row[TravelsTable.delayMinutes],
                        row[TravelsTable.createdAt]
                    )
                }
        }
}
