package alt

import kotlinx.coroutines.runBlocking

/**
 * Headless verification of the shared API + DTO + formatting path.
 * Run: ./gradlew :composeApp:smokeSearch
 */
fun main(args: Array<String>) = runBlocking {
    val base = args.firstOrNull() ?: defaultApiBase()
    val api = AltApi(base)
    println("API base: $base")

    val locs = api.locations("Laus", 3)
    println("locations(" + locs.size + "): " + locs.joinToString { it.name })

    val res = api.search(
        SearchQuery(
            origin = "Lausanne",
            destination = "Genève",
            departure = null,
            arrivalDeadline = null,
            preference = "FASTEST",
            firstClass = false,
            halfFare = false,
        ),
    )
    println("status=${res.status} action=${res.action} reasons=${res.reasons.map { it.code }}")
    val rec = res.recommendedJourney
    println("recommended=${rec?.id} legs=${rec?.legs?.size} alternatives=${res.alternatives.size}")
    rec?.let {
        println("  schedule ${fmtTime(it.schedule?.departure)} -> ${fmtTime(it.schedule?.arrival)} (${fmtDuration(it.schedule?.durationMinutes)}) transfers=${it.schedule?.transfers}")
        println("  fare=${fmtChf(it.fares?.selectedChf)} product=${it.fares?.product} occupancy=${it.maxOccupancy} verified=${it.verified}")
        it.legs.forEach { l ->
            println("  leg ${l.serviceLabel} ${l.origin}->${l.destination} dep=${fmtTime(l.departure)} plat=${l.departurePlatform} delay=${fmtDelay(l.departureDelayMinutes)} est=${fmtEstimate(l.estimatedDepartureDelayMinutes)} proj=${fmtTime(l.projectedArrival)}")
        }
        println("  highlights=${res.highlights}")
    }

    val sim = api.search(
        SearchQuery(
            origin = "Lausanne",
            destination = "Genève",
            departure = null,
            arrivalDeadline = null,
            preference = "FASTEST",
            firstClass = false,
            halfFare = false,
            demoPerturbation = 12345L,
        ),
    )
    println("SIMULATION active=${sim.demoPerturbation?.active} seed=${sim.demoPerturbation?.seed} applied=${sim.demoPerturbation?.applied}")
    println("SIMULATION status=${sim.status} action=${sim.action} reasons=${sim.reasons.map { it.code + (it.serviceLabel?.let { s -> "@" + s } ?: "") + (it.delayMinutes?.let { d -> "+" + d } ?: "") }}")
    println("SIMULATION primary!=recommended: ${sim.primaryJourney?.id != sim.recommendedJourney?.id}")

    if (args.size >= 2) {
        val dl = api.search(
            SearchQuery(
                origin = "Lausanne",
                destination = "Zürich HB",
                departure = null,
                arrivalDeadline = args[1],
                preference = "FASTEST",
                firstClass = true,
                halfFare = true,
            ),
        )
        println("DEADLINE feasible=${dl.deadlineSummary?.feasible} latest=${fmtTime(dl.deadlineSummary?.latestFeasibleDeparture)} newest=${fmtTime(dl.recommendedJourney?.schedule?.arrival)}")
    }
}
