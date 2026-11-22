package com.alt.backend.perturbation

import com.alt.backend.domain.Journey
import com.alt.backend.domain.LegMode
import com.alt.backend.provider.StopEvent
import com.alt.backend.provider.StopEventsResult
import java.time.Duration
import java.time.OffsetDateTime
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/**
 * DEMO-ONLY. The service a perturbation targets: same line label, same boarding stop, same scheduled instant.
 * Boards are matched on the label and a +/-5 min window because provider trip ids are not stable across searches.
 */
data class PerturbationTarget(val serviceLabel: String?, val origin: String, val departure: OffsetDateTime) {
    fun matches(label: String?, scheduled: OffsetDateTime): Boolean =
        norm(label) == norm(serviceLabel) && norm(serviceLabel).isNotEmpty() &&
            Math.abs(Duration.between(scheduled, departure).toMinutes()) <= 5
}

/** One reproducible draw: a detectable delay on a top journey plus, sometimes, one cancelled alternative. */
data class PerturbationProfile(
    val seed: Long,
    val delayMinutes: Int,
    val delayTarget: PerturbationTarget,
    val cancelTarget: PerturbationTarget?
)

/**
 * Per-request perturbation state, carried in the coroutine context so `search()` and `stopEvents()` — including the
 * detector's concurrent board queries — see the same draw and stay coherent. Absent from the context means the whole
 * layer is inert: [PerturbationProvider] then delegates unchanged.
 */
class DemoPerturbationState(val seed: Long, val randomDraw: Boolean) :
    AbstractCoroutineContextElement(DemoPerturbationState) {
    companion object Key : CoroutineContext.Key<DemoPerturbationState>

    /** Journeys as the delegate first returned them; synthetic boards are built from these. */
    internal var firstSearch: List<Journey> = emptyList()

    internal var profile: PerturbationProfile? = null
    private var drew = false

    /** What was applied, surfaced as `demoPerturbation.applied` in the response envelope. */
    var applied: List<String> = emptyList()
        private set

    /** Applies the draw to one provider search result; later searches reuse the same targets, never re-draw. */
    fun applyToSearch(journeys: List<Journey>): List<Journey> {
        if (!drew) {
            drew = true
            firstSearch = journeys
            profile = drawProfile(seed, journeys)
            applied = profile?.let { listOfNotNull(
                "delay+${it.delayMinutes}min ${it.delayTarget.serviceLabel ?: "leg"} @ ${it.delayTarget.origin}",
                it.cancelTarget?.let { c -> "cancelled ${c.serviceLabel ?: "journey"} @ ${c.origin}" }
            ) } ?: emptyList()
        }
        val p = profile ?: return journeys
        return journeys.mapNotNull { j ->
            if (p.cancelTarget != null && j.legs.any { it.mode == LegMode.TRANSIT && p.cancelTarget.matches(it.serviceLabel, it.departure) }) {
                null // the provider omits confirmed cancellations from its results, as OJP does
            } else {
                j.copy(legs = j.legs.map { l ->
                    if (l.mode == LegMode.TRANSIT && p.delayTarget.matches(l.serviceLabel, l.departure)) {
                        l.copy(expectedDeparture = l.departure.plusMinutes(p.delayMinutes.toLong()),
                            expectedArrival = l.arrival.plusMinutes(p.delayMinutes.toLong()))
                    } else l
                })
            }
        }
    }

    /**
     * Realtime boards that agree with [applyToSearch]: the delayed trip gets its estimated time moved, the cancelled
     * trip is flagged. The planned (timetable) board is left untouched — it is the reference the detector diffs.
     * A provider without a board (mock, demo scenario) gets one synthesised from the remembered search results.
     */
    fun applyToBoard(stop: String, realtime: Boolean, result: StopEventsResult): StopEventsResult {
        val p = profile ?: return result
        val situations = (result as? StopEventsResult.Success)?.situations.orEmpty()
        val events = when (result) {
            is StopEventsResult.Success -> if (realtime) result.events.map { perturb(it, p) } else result.events
            is StopEventsResult.Failure -> synthesise(stop, p, realtime)
        }
        return StopEventsResult.Success(events, situations)
    }

    private fun perturb(e: StopEvent, p: PerturbationProfile): StopEvent {
        if (p.cancelTarget?.matches(e.label ?: e.lineRef, e.scheduled) == true) return e.copy(cancelled = true)
        return if (p.delayTarget.matches(e.label ?: e.lineRef, e.scheduled)) e.copy(estimated = e.scheduled.plusMinutes(p.delayMinutes.toLong()))
        else e.copy(estimated = e.estimated ?: e.scheduled)
    }

    private fun synthesise(stop: String, p: PerturbationProfile, realtime: Boolean): List<StopEvent> {
        val key = norm(stop)
        if (key.isEmpty()) return emptyList()
        return firstSearch.flatMap { j ->
            j.legs.mapIndexedNotNull { i, l ->
                if (l.mode != LegMode.TRANSIT || norm(l.origin) != key) return@mapIndexedNotNull null
                val cancelled = realtime && p.cancelTarget?.matches(l.serviceLabel, l.departure) == true
                StopEvent(
                    label = l.serviceLabel, lineRef = l.serviceLabel, scheduled = l.departure,
                    estimated = if (!realtime || cancelled) null else l.departure.plusMinutes(
                        if (p.delayTarget.matches(l.serviceLabel, l.departure)) p.delayMinutes.toLong() else 0L),
                    cancelled = cancelled, platform = l.departurePlatform,
                    onward = (listOf(norm(l.destination)) + j.legs.drop(i + 1).map { later -> norm(later.destination) })
                        .filter { it.isNotEmpty() }.toSet(),
                    originDeparture = j.legs.firstOrNull { it.mode == LegMode.TRANSIT }?.departure
                )
            }
        }
    }
}

private fun norm(s: String?) = s?.uppercase()?.filter { it.isLetterOrDigit() }.orEmpty()

/**
 * The draw. Randomness is spent in a fixed order (delay, leg, cancel roll, choice) so a seed always reproduces the
 * whole profile. The delay goes on the first journey of the provider result, which is the plan's primary for the
 * demo providers, so the demo always shows a detected disruption; the cancellation picks an alternative that departs
 * before the delayed leg, which is what makes the detector's earlier-departure-cancelled rule fire.
 */
internal fun drawProfile(seed: Long, journeys: List<Journey>): PerturbationProfile? {
    val rnd = Random(seed)
    val targetJourney = journeys.firstOrNull { j -> j.legs.any { it.mode == LegMode.TRANSIT } } ?: return null
    val transitLegs = targetJourney.legs.indices.filter { targetJourney.legs[it].mode == LegMode.TRANSIT }
    val legIndex = transitLegs[rnd.nextInt(transitLegs.size)]
    val leg = targetJourney.legs[legIndex]
    val delay = 5 + rnd.nextInt(41)
    val roll = rnd.nextInt(100)
    val alternatives = journeys.filter { it !== targetJourney && it.legs.any { l -> l.mode == LegMode.TRANSIT } }
    val eligible = alternatives.filter { alt ->
        alt.legs.firstOrNull { it.mode == LegMode.TRANSIT }?.let { !it.departure.isAfter(leg.departure.minusMinutes(3)) } == true
    }
    val pool = eligible.ifEmpty { alternatives }
    val pick = if (pool.isEmpty()) null else pool[rnd.nextInt(pool.size)]
    val cancel = pick?.takeIf { roll < 50 }?.legs?.firstOrNull { it.mode == LegMode.TRANSIT }
        ?.let { l -> PerturbationTarget(l.serviceLabel, l.origin, l.departure) }
    return PerturbationProfile(seed, delay, PerturbationTarget(leg.serviceLabel, leg.origin, leg.departure), cancel)
}

object DemoPerturbation {
    /**
     * `demoPerturbation=true` draws a fresh seed every request; a number is a reproducible seed. Absent, or the
     * `DEMO_PERTURBATION=false` kill switch, returns null and the layer never touches the request.
     */
    fun parse(raw: String?, enabled: Boolean): DemoPerturbationState? {
        if (!enabled || raw == null) return null
        val v = raw.trim()
        if (v.equals("true", ignoreCase = true)) return DemoPerturbationState(Random.Default.nextLong().let { Math.abs(it) }, randomDraw = true)
        if (v.equals("false", ignoreCase = true)) return null
        val seed = v.toLongOrNull() ?: throw IllegalArgumentException("Parameter 'demoPerturbation' must be true or an integer seed")
        return DemoPerturbationState(seed, randomDraw = false)
    }
}
