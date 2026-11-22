package com.alt.backend.provider

import com.alt.backend.domain.DataStatus
import com.alt.backend.domain.Journey
import com.alt.backend.domain.JourneyLeg
import com.alt.backend.domain.LegMode
import com.alt.backend.domain.SearchRequest
import java.text.Normalizer
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Locale

/**
 * Demo-only journeys for the disruption replays (TRANSPORT_PROVIDER=demo), all labelled MOCK.
 * Times are plausible approximations relative to the requested departure, not a timetable.
 *
 * - Lausanne -> Genève: IC 1 and IR 90 along Renens VD - Morges (affected by the Renens incident), and
 *   a lake alternative avoiding that section: m2 to Ouchy, CGN boat to Évian, Léman Express to Genève.
 * - Zürich Stadelhofen -> Stettbach: S-Bahn through the tunnel (affected by the Stadelhofen incident),
 *   and a tram alternative.
 * Any other request is answered by the team's MockTransportProvider, unchanged.
 */
class DemoScenarioTransportProvider(private val fallback: TransportProvider = MockTransportProvider()) : TransportProvider {

    override suspend fun search(request: SearchRequest): ProviderResult {
        val od = normalize(request.origin) to normalize(request.destination)
        val base = request.earliestDeparture
        return when (od) {
            "lausanne" to "geneve" -> ProviderResult.Success(lausanneGeneva(base))
            "lausanne" to "fribourg" -> ProviderResult.Success(lausanneFribourg(base))
            "zurich stadelhofen" to "stettbach" -> ProviderResult.Success(stadelhofenStettbach(base))
            else -> fallback.search(request)
        }
    }

    private fun lausanneGeneva(base: OffsetDateTime) = listOf(
        journey("demo-renens-ic1", leg(base, 5, 40, "Lausanne", "Genève", LS, GE, "IC 1", listOf(MORGES))),
        journey("demo-renens-ir90", leg(base, 12, 63, "Lausanne", "Genève", LS, GE, "IR 90", listOf(RENENS, MORGES, ALLAMAN))),
        journey("demo-renens-lake",
            leg(base, 5, 11, "Lausanne-Gare", "Lausanne-Ouchy", null, null, "m2"),
            leg(base, 25, 60, "Lausanne-Ouchy", "Évian-les-Bains (débarcadère)", null, null, "CGN boat"),
            leg(base, 60, 70, "Évian-les-Bains (débarcadère)", "Évian-les-Bains", null, null, null, mode = LegMode.WALK),
            leg(base, 78, 140, "Évian-les-Bains", "Genève", null, GE, "L1"))
    )

    /**
     * The transfer-miss demo: one journey changes at Fribourg with 3 minutes of slack, the other with 30.
     * A predicted (or demo-perturbed) delay on the first leg should make the tight one not-recommended.
     */
    private fun lausanneFribourg(base: OffsetDateTime) = listOf(
        journey("demo-fribourg-tight",
            leg(base, 5, 40, "Lausanne", "Fribourg", LS, FRIBOURG, "IC 1"),
            leg(base, 43, 75, "Fribourg", "Bern", FRIBOURG, BERN, "IR 15")),
        journey("demo-fribourg-safe",
            leg(base, 10, 45, "Lausanne", "Fribourg", LS, FRIBOURG, "IR 90"),
            leg(base, 75, 110, "Fribourg", "Bern", FRIBOURG, BERN, "IC 5"))
    )

    private fun stadelhofenStettbach(base: OffsetDateTime) = listOf(
        journey("demo-stadelhofen-s-bahn", leg(base, 3, 9, "Zürich Stadelhofen", "Stettbach", STADELHOFEN, STETTBACH, "S11")),
        journey("demo-stadelhofen-tram",
            leg(base, 4, 20, "Zürich, Stadelhofen", "Zürich, Schwamendingerplatz", null, null, "Tram 11"),
            leg(base, 24, 32, "Zürich, Schwamendingerplatz", "Zürich, Bahnhof Stettbach", null, null, "Tram 7"))
    )

    private fun leg(
        base: OffsetDateTime, dep: Long, arr: Long, from: String, to: String, fromSloid: String?, toSloid: String?,
        label: String?, via: List<String> = emptyList(), mode: LegMode = LegMode.TRANSIT
    ) = JourneyLeg(mode, from, to, base.plusMinutes(dep), base.plusMinutes(arr), label, null, null, fromSloid, toSloid, via)

    private fun journey(id: String, vararg legs: JourneyLeg) = Journey(
        id = id,
        departure = legs.first().departure,
        arrival = legs.last().arrival,
        durationMinutes = Duration.between(legs.first().departure, legs.last().arrival).toMinutes().toInt(),
        transfers = legs.count { it.mode == LegMode.TRANSIT } - 1,
        legs = legs.toList(),
        dataStatus = DataStatus.MOCK
    )

    private fun normalize(name: String): String =
        Normalizer.normalize(name.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private companion object {
        const val LS = "ch:1:sloid:1120"
        const val GE = "ch:1:sloid:1008"
        const val FRIBOURG = "ch:1:sloid:4100"
        const val BERN = "ch:1:sloid:7000"
        const val RENENS = "ch:1:sloid:1118"
        const val MORGES = "ch:1:sloid:1037"
        const val ALLAMAN = "ch:1:sloid:1035"
        const val STADELHOFEN = "ch:1:sloid:3003"
        const val STETTBACH = "ch:1:sloid:3147"
    }
}
