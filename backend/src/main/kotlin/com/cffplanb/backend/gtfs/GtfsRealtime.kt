package com.cffplanb.backend.gtfs

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class GtfsParseException(message: String) : RuntimeException(message)

/** [sequence] / delays are null when the feed omits them. SKIPPED stop = the trip does not call there. */
data class GtfsStopUpdate(val stopId: String, val delaySeconds: Int?, val skipped: Boolean)

data class GtfsTrip(
    val tripId: String, val routeId: String, val startDate: String, val startTime: String,
    val cancelled: Boolean, val added: Boolean, val stops: List<GtfsStopUpdate>
) {
    /** Scheduled start of the whole trip (at its first stop), Swiss local time; start_time may exceed 24:00:00. */
    val startInstant: Instant? by lazy {
        try {
            val (h, m, s) = startTime.split(":").map { it.toInt() }
            LocalDate.parse(startDate, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(ZoneId.of("Europe/Zurich")).plusSeconds(h * 3600L + m * 60L + s).toInstant()
        } catch (e: Exception) { null }
    }
}

data class GtfsSnapshot(val fetchedAtMs: Long, val trips: List<GtfsTrip>, val entities: Int, val cancelled: Int, val added: Int, val skippedStops: Int) {
    /** route_id minus its "-j26-1" validity suffix, e.g. "91-33-C". */
    val byRoute: Map<String, List<GtfsTrip>> by lazy { trips.groupBy { routeBase(it.routeId) } }
    val byTripId: Map<String, GtfsTrip> by lazy { trips.associateBy { it.tripId } }
}

private val SUFFIX = Regex("-j\\d+-\\d+$")
fun routeBase(routeId: String) = routeId.replace(SUFFIX, "")

/**
 * Minimal protobuf wire decoder for the GTFS-Realtime messages we need (official field numbers from gtfs-realtime.proto):
 * FeedMessage{2 entity}, FeedEntity{1 id, 2 is_deleted, 3 trip_update}, TripUpdate{1 trip, 2 stop_time_update},
 * TripDescriptor{1 trip_id, 2 start_time, 3 start_date, 4 schedule_relationship, 5 route_id},
 * StopTimeUpdate{2 arrival, 3 departure, 4 stop_id, 5 schedule_relationship}, StopTimeEvent{1 delay}.
 * ScheduleRelationship: trip CANCELED = 3, ADDED = 1; stop SKIPPED = 1.
 */
object GtfsDecoder {
    fun parse(b: ByteArray, nowMs: Long): GtfsSnapshot {
        val trips = ArrayList<GtfsTrip>(8000)
        var entities = 0; var cancelled = 0; var added = 0; var skipped = 0
        try {
            fields(b, 0, b.size) { f, _, _, s, l ->
                if (f == 2) {
                    entities++
                    var deleted = false; var tu: IntArray? = null
                    fields(b, s, s + l) { f2, _, v, s2, l2 -> if (f2 == 2) deleted = v != 0L else if (f2 == 3) tu = intArrayOf(s2, l2) }
                    val t = tu
                    if (!deleted && t != null) {
                        val trip = tripUpdate(b, t[0], t[0] + t[1])
                        if (trip != null) {
                            trips += trip
                            if (trip.cancelled) cancelled++
                            if (trip.added) added++
                            skipped += trip.stops.count { it.skipped }
                        }
                    }
                }
            }
        } catch (e: IndexOutOfBoundsException) { throw GtfsParseException("truncated protobuf") }
        if (trips.isEmpty()) throw GtfsParseException("feed contains no trip updates")
        return GtfsSnapshot(nowMs, trips, entities, cancelled, added, skipped)
    }

    private fun tripUpdate(b: ByteArray, from: Int, to: Int): GtfsTrip? {
        var id = ""; var route = ""; var date = ""; var time = ""; var sr = 0L; var hasTrip = false
        val stops = ArrayList<GtfsStopUpdate>(16)
        fields(b, from, to) { f, _, _, s, l ->
            if (f == 1) {
                hasTrip = true
                fields(b, s, s + l) { f2, _, v, s2, l2 ->
                    when (f2) { 1 -> id = str(b, s2, l2); 2 -> time = str(b, s2, l2); 3 -> date = str(b, s2, l2); 4 -> sr = v; 5 -> route = str(b, s2, l2) }
                }
            } else if (f == 2) {
                var stop = ""; var delay: Int? = null; var skipped = false
                fields(b, s, s + l) { f2, _, v, s2, l2 ->
                    when (f2) {
                        2, 3 -> fields(b, s2, s2 + l2) { f3, _, v3, _, _ -> if (f3 == 1 && (f2 == 3 || delay == null)) delay = v3.toInt() }
                        4 -> stop = str(b, s2, l2)
                        5 -> skipped = v == 1L
                    }
                }
                stops += GtfsStopUpdate(stop, delay, skipped)
            }
        }
        return if (hasTrip) GtfsTrip(id, route, date, time, sr == 3L, sr == 1L, stops) else null
    }

    private fun str(b: ByteArray, s: Int, l: Int) = String(b, s, l, Charsets.UTF_8)

    private class Pos(var i: Int)

    private fun varint(b: ByteArray, p: Pos): Long {
        var r = 0L; var sh = 0
        while (true) { val x = b[p.i++].toInt(); r = r or ((x and 0x7f).toLong() shl sh); if (x and 0x80 == 0) return r; sh += 7 }
    }

    /** Calls [f] with (field, wireType, varintValue, payloadStart, payloadLength) for every field in [from, to). */
    private inline fun fields(b: ByteArray, from: Int, to: Int, f: (Int, Int, Long, Int, Int) -> Unit) {
        val p = Pos(from)
        while (p.i < to) {
            val tag = varint(b, p).toInt(); val field = tag ushr 3
            when (val w = tag and 7) {
                0 -> f(field, w, varint(b, p), 0, 0)
                2 -> { val n = varint(b, p).toInt(); if (n < 0 || p.i + n > to) throw GtfsParseException("bad length"); f(field, w, 0, p.i, n); p.i += n }
                1 -> p.i += 8
                5 -> p.i += 4
                else -> throw GtfsParseException("unsupported wire type $w")
            }
        }
    }
}

/** Rules for mapping an OJP line reference (and the trip's first-stop time) onto GTFS-RT ids. */
object GtfsMatch {
    /**
     * OJP line ref "ojp:91033:C" -> GTFS route base "91-33-C"; "ojp:91057:_" (no direction letter) -> "91-57".
     * Rule: ojp:<2-digit operator><3-digit line number>:<letter or _>; leading zeros of the line number are dropped.
     * Anything else (night lines "920N5", missing ref) gives null = no confident mapping.
     */
    fun routeKey(lineRef: String?): String? {
        val m = Regex("^ojp:(\\d{2})(\\d{3}):([A-Za-z0-9_]?)$").matchEntire(lineRef?.trim() ?: return null) ?: return null
        val suffix = m.groupValues[3].takeIf { it.isNotEmpty() && it != "_" }
        return listOfNotNull(m.groupValues[1], m.groupValues[2].toInt().toString(), suffix).joinToString("-")
    }

    /**
     * The one GTFS trip of this line that starts at [originDeparture] (the timetabled departure at the trip's FIRST stop, from OJP).
     * Tolerance 1 minute. Zero or several candidates = no confident match.
     */
    fun find(snap: GtfsSnapshot, lineRef: String?, originDeparture: Instant?): GtfsTrip? {
        val key = routeKey(lineRef) ?: return null
        val t = originDeparture ?: return null
        val c = (snap.byRoute[key] ?: return null).filter { !it.added && it.startInstant?.let { s -> Math.abs(s.epochSecond - t.epochSecond) <= 60 } == true }
        return c.singleOrNull()
    }

    /** True when GTFS stop id [gtfsStop] denotes the same stop place as OJP stop ref [ojpStopRef] ("ch:1:sloid:1120:0:840061"). */
    fun sameStop(gtfsStop: String, ojpStopRef: String?): Boolean {
        val a = place(gtfsStop) ?: return false
        return a == (place(ojpStopRef ?: return false) ?: return false)
    }

    private fun place(id: String): String? =
        Regex("ch:1:sloid:(\\d+)").find(id)?.groupValues?.get(1)?.trimStart('0')
            ?: Regex("^85(\\d{5})").find(id)?.groupValues?.get(1)?.trimStart('0')
}
