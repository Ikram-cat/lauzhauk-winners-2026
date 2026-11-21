package com.cffplanb.backend.provider

import com.cffplanb.backend.domain.DataStatus
import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.JourneyLeg
import com.cffplanb.backend.domain.LegMode
import com.cffplanb.backend.domain.SearchRequest
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import javax.xml.parsers.DocumentBuilderFactory

class OjpLocationResolutionException(message: String) : RuntimeException(message)
class OjpAuthenticationException(message: String) : RuntimeException(message)
class OjpRateLimitedException : RuntimeException("rate limited")
class OjpTimeoutException : RuntimeException("timeout")
class OjpUpstreamException(message: String) : RuntimeException(message)

/**
 * OJP 2.0 transport provider.
 *
 * Before every trip query, resolves the free-text stop name to a sloid via
 * OJPLocationInformationRequest. Results are cached in memory. Resolution
 * failures are surfaced as typed [ProviderResult.LocationResolutionError].
 *
 * The API key is never logged or included in error messages.
 */
class OjpTransportProvider(
    private val apiKey: String,
    private val clock: Clock = Clock.systemUTC(),
    private val httpClient: HttpClient = defaultClient()
) : TransportProvider {

    private val log = LoggerFactory.getLogger(OjpTransportProvider::class.java)
    private val ojpEndpoint = "https://api.opentransportdata.swiss/ojp20"
    private val timeoutMs = 8_000L
    private val suggestTimeoutMs = 4_000L

    // In-memory cache: free-text name -> resolved sloid (e.g. "ch:1:sloid:1120")
    private val stopCache = ConcurrentHashMap<String, String>()

    // -------------------------------------------------------------------------
    // TransportProvider interface
    // -------------------------------------------------------------------------

    override suspend fun search(request: SearchRequest): ProviderResult {
        // 1. Resolve origin stop id
        val originId = try {
            resolveStopId(request.origin)
        } catch (e: OjpAuthenticationException) {
            return ProviderResult.AuthError(e.message ?: "Provider authentication failed")
        } catch (e: OjpLocationResolutionException) {
            return ProviderResult.LocationResolutionError(e.message ?: "Failed to resolve origin stop")
        } catch (e: OjpRateLimitedException) {
            return ProviderResult.RateLimited("OJP rate limit reached")
        } catch (e: OjpTimeoutException) {
            return ProviderResult.Timeout("OJP did not respond within ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProviderResult.Failure("Failed to resolve origin '${request.origin}': ${e.javaClass.simpleName}")
        }

        // 2. Resolve destination stop id
        val destId = try {
            resolveStopId(request.destination)
        } catch (e: OjpAuthenticationException) {
            return ProviderResult.AuthError(e.message ?: "Provider authentication failed")
        } catch (e: OjpLocationResolutionException) {
            return ProviderResult.LocationResolutionError(e.message ?: "Failed to resolve destination stop")
        } catch (e: OjpRateLimitedException) {
            return ProviderResult.RateLimited("OJP rate limit reached")
        } catch (e: OjpTimeoutException) {
            return ProviderResult.Timeout("OJP did not respond within ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ProviderResult.Failure("Failed to resolve destination '${request.destination}': ${e.javaClass.simpleName}")
        }

        // 3. Build and send trip request
        val xml = buildTripRequest(originId, destId, request.earliestDeparture)

        val result = withTimeoutOrNull(timeoutMs) {
            try {
                val response: HttpResponse = httpClient.post(ojpEndpoint) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.parse("application/xml; charset=utf-8"))
                    accept(ContentType.Application.Xml)
                    setBody(xml)
                }
                when (response.status.value) {
                    200 -> {
                        val body = response.bodyAsText()
                        parseTripResponse(body)
                    }
                    401, 403 -> {
                        log.error("OJP authentication error: HTTP {}", response.status.value)
                        ProviderResult.AuthError(
                            "Transport provider authentication failed. Check OJP_API_KEY configuration."
                        )
                    }
                    429 -> ProviderResult.RateLimited("OJP rate limit reached")
                    else -> {
                        log.error("OJP upstream error: HTTP {}", response.status.value)
                        ProviderResult.Failure("OJP upstream returned HTTP ${response.status.value}")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("OJP provider error: {}: {}", e.javaClass.simpleName, e.message)
                ProviderResult.Failure("OJP call failed: ${e.javaClass.simpleName}")
            }
        }

        return result ?: ProviderResult.Timeout("OJP did not respond within ${timeoutMs}ms")
    }

    // -------------------------------------------------------------------------
    // Location resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves a free-text stop name to a sloid via OJPLocationInformationRequest.
     * Caches in memory. Throws typed exceptions on error.
     */
    internal suspend fun resolveStopId(name: String): String {
        stopCache[name]?.let { return it }

        val body = postLocationRequest(buildLocationRequest(name, nowTs()), timeoutMs)

        val sloid = parseLocationResponse(body)
            ?: throw OjpLocationResolutionException("Could not resolve stop '$name' via OJP LocationInformationRequest")

        stopCache[name] = sloid
        log.info("Resolved stop '{}' -> '{}'", name, sloid)
        return sloid
    }


    private fun nowTs(): String =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))

    /** POSTs an OJP LocationInformationRequest. Throws OjpAuthenticationException / OjpLocationResolutionException. */
    private suspend fun postLocationRequest(requestXml: String, bound: Long): String =
        withTimeoutOrNull(bound) {
            try {
                val response: HttpResponse = httpClient.post(ojpEndpoint) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.parse("application/xml; charset=utf-8"))
                    accept(ContentType.Application.Xml)
                    setBody(requestXml)
                }
                when (response.status.value) {
                    200 -> response.bodyAsText()
                    401, 403 -> throw OjpAuthenticationException(
                        "Transport provider authentication failed. Check OJP_API_KEY configuration."
                    )
                    429 -> throw OjpRateLimitedException()
                    else -> throw OjpUpstreamException("OJP location request failed with HTTP ${response.status.value}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: OjpAuthenticationException) {
                throw e
            } catch (e: OjpRateLimitedException) {
                throw e
            } catch (e: OjpUpstreamException) {
                throw e
            } catch (e: Exception) {
                throw OjpUpstreamException("OJP location call failed: ${e.javaClass.simpleName}")
            }
        } ?: throw OjpTimeoutException()

    // ---- Stop events (realtime departures) ----
    private val stopEventTimeoutMs = 4_000L

    override suspend fun stopEvents(stop: String, from: OffsetDateTime, realtime: Boolean): StopEventsResult = try {
        val id = resolveStopId(stop)
        StopEventsResult.Success(parseStopEvents(postLocationRequest(buildStopEventRequest(id, stop, from, nowTs(), realtime), stopEventTimeoutMs)))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        StopEventsResult.Failure("Stop events failed: ${e.javaClass.simpleName}")
    }

    internal fun buildStopEventRequest(stopId: String, name: String, from: OffsetDateTime, ts: String, realtime: Boolean = true): String {
        val fromTs = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(from.withOffsetSameInstant(ZoneOffset.UTC))
        return """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPRequest>
    <siri:ServiceRequest>
      <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
      <siri:RequestorRef>cff-plan-b</siri:RequestorRef>
      <OJPStopEventRequest>
        <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
        <siri:MessageIdentifier>se-1</siri:MessageIdentifier>
        <Location>
          <PlaceRef><siri:StopPointRef>${escapeXml(stopId)}</siri:StopPointRef><Name><Text>${escapeXml(name)}</Text></Name></PlaceRef>
          <DepArrTime>$fromTs</DepArrTime>
        </Location>
        <Params>
          <NumberOfResults>40</NumberOfResults>
          <StopEventType>departure</StopEventType>
          <IncludePreviousCalls>true</IncludePreviousCalls>
          <IncludeOnwardCalls>true</IncludeOnwardCalls>
          <UseRealtimeData>${if (realtime) "full" else "none"}</UseRealtimeData>
        </Params>
      </OJPStopEventRequest>
    </siri:ServiceRequest>
  </OJPRequest>
</OJP>"""
    }

    /** A missing <Cancelled> element means "not cancelled". Events without a parseable timetabled departure are dropped. */
    internal fun parseStopEvents(xml: String): List<StopEvent> {
        val doc = newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val evs = doc.getElementsByTagNameNS("*", "StopEvent")
        return (0 until evs.length).mapNotNull { i ->
            val ev = evs.item(i) as? Element ?: return@mapNotNull null
            val call = firstChildElement(ev, "ThisCall")?.let { firstChildElement(it, "CallAtStop") } ?: return@mapNotNull null
            val dep = firstChildElement(call, "ServiceDeparture") ?: return@mapNotNull null
            val sched = textContent(dep, "TimetabledTime")?.let { parseDateTimeOrNull(it) } ?: return@mapNotNull null
            val svc = firstChildElement(ev, "Service")
            StopEvent(
                label = svc?.let { textIn(it, "PublishedServiceName") ?: textIn(it, "PublicCode") },
                lineRef = svc?.let { textContent(it, "LineRef") },
                scheduled = sched,
                estimated = textContent(dep, "EstimatedTime")?.let { parseDateTimeOrNull(it) },
                cancelled = (svc?.let { textContent(it, "Cancelled") } ?: textContent(ev, "Cancelled")).equals("true", ignoreCase = true),
                platform = textIn(call, "PlannedQuay") ?: textIn(call, "EstimatedQuay"),
                stopRef = textContent(call, "StopPointRef"),
                originDeparture = firstChildElement(ev, "PreviousCall")?.let { pc ->
                    firstChildElement(pc, "ServiceDeparture")?.let { textContent(it, "TimetabledTime") }?.let { parseDateTimeOrNull(it) }
                } ?: sched,
                onward = ev.getElementsByTagNameNS("*", "OnwardCall").let { oc ->
                    (0 until oc.length).mapNotNull { (oc.item(it) as? Element)?.let { e -> textIn(e, "StopPointName") } }
                        .map { n -> n.uppercase().filter { c -> c.isLetterOrDigit() } }.toSet()
                }
            )
        }
    }

    // ---- Autocomplete ----
    private val suggestCache = ConcurrentHashMap<String, Pair<Instant, List<LocationSuggestion>>>()

    override suspend fun searchLocations(query: String): LocationsResult {
        val key = query.trim().lowercase()
        suggestCache[key]?.let { (at, v) -> if (Duration.between(at, Instant.now(clock)).toMinutes() < 10) return LocationsResult.Success(v) }
        return try {
            val xml = postLocationRequest(buildLocationRequest(query, nowTs(), limit = 10, types = listOf("stop", "address")), suggestTimeoutMs)
            val found = parseLocationSuggestions(xml)
            if (suggestCache.size > 500) suggestCache.clear()
            suggestCache[key] = Instant.now(clock) to found
            LocationsResult.Success(found)
        } catch (e: OjpTimeoutException) {
            LocationsResult.Timeout("Location lookup timed out")
        } catch (e: OjpRateLimitedException) {
            LocationsResult.RateLimited("Transport provider is rate limiting requests")
        } catch (e: OjpAuthenticationException) {
            LocationsResult.Failure(e.message ?: "Transport provider authentication failed")
        } catch (e: OjpLocationResolutionException) {
            LocationsResult.Failure("Location lookup failed")
        } catch (e: OjpUpstreamException) {
            LocationsResult.Failure("Location lookup failed")
        }
    }

    internal fun parseLocationSuggestions(xml: String): List<LocationSuggestion> = try {
        val doc = newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val places = doc.getElementsByTagNameNS("*", "Place")
        (0 until places.length).mapNotNull { i ->
            val pl = places.item(i) as? Element ?: return@mapNotNull null
            val stop = firstChildElement(pl, "StopPlace")
            val addr = firstChildElement(pl, "Address")
            // Place/Name is the disambiguated label; fall back to the stop's own name.
            val name = (pl.childNodes.let { n -> (0 until n.length).map { n.item(it) }
                .firstOrNull { it is Element && it.localName == "Name" } as? Element })
                ?.let { textIn(it, "Text") }
            val (id, kind, fallbackName) = when {
                stop != null -> Triple(textContent(stop, "StopPlaceRef"), "stop", textIn(stop, "StopPlaceName"))
                addr != null -> Triple(textContent(addr, "PublicCode"), "address", textIn(addr, "Name"))
                else -> return@mapNotNull null
            }
            val geo = firstChildElement(pl, "GeoPosition")
            val finalName = name ?: fallbackName
            if (id == null || finalName == null) null else LocationSuggestion(
                id, finalName, kind,
                geo?.let { textContent(it, "Longitude")?.toDoubleOrNull() },
                geo?.let { textContent(it, "Latitude")?.toDoubleOrNull() }
            )
        }.take(10)
    } catch (e: Exception) {
        log.error("OJP location suggestion parse error: {}", e.javaClass.simpleName)
        emptyList()
    }

    internal fun buildLocationRequest(name: String, ts: String, limit: Int = 5, types: List<String> = listOf("stop")): String {
        val escaped = escapeXml(name)
        return """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPRequest>
    <siri:ServiceRequest>
      <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
      <siri:RequestorRef>cff-plan-b</siri:RequestorRef>
      <OJPLocationInformationRequest>
        <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
        <InitialInput>
          <Name>$escaped</Name>
        </InitialInput>
        <Restrictions>
          ${types.joinToString("\n          ") { "<Type>$it</Type>" }}
          <NumberOfResults>$limit</NumberOfResults>
        </Restrictions>
      </OJPLocationInformationRequest>
    </siri:ServiceRequest>
  </OJPRequest>
</OJP>"""
    }

    /**
     * Parses OJPLocationInformationDelivery and returns the best sloid.
     * Prefers the first PlaceResult containing a stop with a sloid id (format ch:1:sloid:<id>).
     */
    internal fun parseLocationResponse(xml: String): String? {
        return try {
            val doc = newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val placeResults = doc.getElementsByTagNameNS("*", "PlaceResult")

            for (i in 0 until placeResults.length) {
                val pr = placeResults.item(i) as? Element ?: continue

                // Check StopPlace > StopPlaceRef
                val stopPlaceRefs = pr.getElementsByTagNameNS("*", "StopPlaceRef")
                for (j in 0 until stopPlaceRefs.length) {
                    val ref = stopPlaceRefs.item(j)?.textContent?.trim() ?: continue
                    if (ref.startsWith("ch:1:sloid:")) {
                        return ref
                    }
                }

                // Fall back to StopPoint > StopPointRef
                val stopPointRefs = pr.getElementsByTagNameNS("*", "StopPointRef")
                for (j in 0 until stopPointRefs.length) {
                    val ref = stopPointRefs.item(j)?.textContent?.trim() ?: continue
                    if (ref.startsWith("ch:1:sloid:")) {
                        return ref
                    }
                }
            }

            null
        } catch (e: Exception) {
            log.error("OJP location parse error: {}: {}", e.javaClass.simpleName, e.message)
            null
        }
    }

    // -------------------------------------------------------------------------
    // Trip request
    // -------------------------------------------------------------------------

    internal fun buildTripRequest(originId: String, destId: String, departure: OffsetDateTime): String {
        val nowUtc = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
        val ts = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(nowUtc)
        val depUtc = departure.withOffsetSameInstant(ZoneOffset.UTC)
        val depTs = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(depUtc)

        return """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPRequest>
    <siri:ServiceRequest>
      <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
      <siri:RequestorRef>cff-plan-b</siri:RequestorRef>
      <OJPTripRequest>
        <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
        <Origin>
          <PlaceRef>
            <siri:StopPointRef>${escapeXml(originId)}</siri:StopPointRef>
            <Name><Text>${escapeXml(originId)}</Text></Name>
          </PlaceRef>
          <DepArrTime>$depTs</DepArrTime>
        </Origin>
        <Destination>
          <PlaceRef>
            <siri:StopPointRef>${escapeXml(destId)}</siri:StopPointRef>
            <Name><Text>${escapeXml(destId)}</Text></Name>
          </PlaceRef>
        </Destination>
        <Params>
          <IncludeTrackSections>false</IncludeTrackSections>
          <IncludeLegProjection>false</IncludeLegProjection>
          <NumberOfResults>5</NumberOfResults>
        </Params>
      </OJPTripRequest>
    </siri:ServiceRequest>
  </OJPRequest>
</OJP>"""
    }

    // -------------------------------------------------------------------------
    // Trip response parsing
    // -------------------------------------------------------------------------

    internal fun parseTripResponse(xml: String): ProviderResult {
        return try {
            val doc = newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

            val tripResults = doc.getElementsByTagNameNS("*", "TripResult")
            val journeys = mutableListOf<Journey>()

            for (i in 0 until tripResults.length) {
                val tripResult = tripResults.item(i) as? Element ?: continue
                val trip = firstChildElement(tripResult, "Trip") ?: continue

                val tripId = textContent(trip, "Id") ?: "ojp-trip-$i"

                val legs = mutableListOf<JourneyLeg>()
                val legElements = trip.getElementsByTagNameNS("*", "Leg")

                data class RawLeg(
                    val timedLeg: Element?,
                    val transferLeg: Element?,
                    val continuousLeg: Element?
                )

                val rawLegs = mutableListOf<RawLeg>()
                for (j in 0 until legElements.length) {
                    val legEl = legElements.item(j) as? Element ?: continue
                    if (legEl.parentNode != trip) continue
                    val tl = firstChildElement(legEl, "TimedLeg")
                    val cl = firstChildElement(legEl, "ContinuousLeg")
                    val trl = firstChildElement(legEl, "TransferLeg")
                    rawLegs.add(RawLeg(tl, trl, cl))
                }

                for (raw in rawLegs) {
                    when {
                        raw.timedLeg != null -> parseTimedLeg(raw.timedLeg)?.let { legs.add(it) }
                        raw.transferLeg != null -> parseWalkLeg(raw.transferLeg, legs)?.let { legs.add(it) }
                        raw.continuousLeg != null -> parseWalkLeg(raw.continuousLeg, legs)?.let { legs.add(it) }
                    }
                }

                if (legs.isEmpty()) continue

                val firstDep = legs.first().departure
                val lastArr = legs.last().arrival
                val durationMin = ChronoUnit.MINUTES.between(firstDep, lastArr).toInt().coerceAtLeast(0)
                val transferCount = legs.count { it.mode == LegMode.TRANSIT }.minus(1).coerceAtLeast(0)

                journeys.add(
                    Journey(
                        id = tripId,
                        departure = firstDep,
                        arrival = lastArr,
                        durationMinutes = durationMin,
                        transfers = transferCount,
                        legs = legs,
                        dataStatus = DataStatus.LIVE_SCHEDULE
                    )
                )
            }

            ProviderResult.Success(journeys)
        } catch (e: Exception) {
            log.error("OJP parse error: {}: {}", e.javaClass.simpleName, e.message)
            ProviderResult.Failure("Failed to parse OJP response")
        }
    }

    private fun parseTimedLeg(timedLeg: Element): JourneyLeg? {
        return try {
            val board = firstChildElement(timedLeg, "LegBoard") ?: return null
            val alight = firstChildElement(timedLeg, "LegAlight") ?: return null
            val service = firstChildElement(timedLeg, "Service")

            val origin = textIn(board, "StopPointName") ?: return null
            val destination = textIn(alight, "StopPointName") ?: return null

            val depTime = timeAt(board, "ServiceDeparture") ?: return null
            val arrTime = timeAt(alight, "ServiceArrival") ?: return null

            val serviceLabel = service?.let {
                textIn(it, "PublishedServiceName")
                    ?: textIn(it, "PublicCode")
                    ?: textIn(it, "PublishedLineName")
                    ?: it.getElementsByTagNameNS("*", "LineRef").item(0)?.textContent?.trim()?.ifBlank { null }
            }

            val intermediates = timedLeg.getElementsByTagNameNS("*", "LegIntermediate").let { nodes ->
                (0 until nodes.length).mapNotNull { i -> (nodes.item(i) as? Element)?.let { stopPlaceSloid(it) } }
            }

            val depPlatform = textIn(board, "PlannedQuay") ?: textIn(board, "EstimatedQuay")
            val arrPlatform = textIn(alight, "PlannedQuay") ?: textIn(alight, "EstimatedQuay")

            JourneyLeg(
                mode = LegMode.TRANSIT,
                origin = origin,
                destination = destination,
                departure = depTime,
                arrival = arrTime,
                serviceLabel = serviceLabel,
                departurePlatform = depPlatform,
                arrivalPlatform = arrPlatform,
                originSloid = stopPlaceSloid(board),
                destinationSloid = stopPlaceSloid(alight),
                intermediateSloids = intermediates
            )
        } catch (e: Exception) {
            log.debug("Failed to parse TimedLeg: {}", e.message)
            null
        }
    }

    private fun parseWalkLeg(walkEl: Element, priorLegs: List<JourneyLeg>): JourneyLeg? {
        return try {
            val legStart = firstChildElement(walkEl, "LegStart")
            val legEnd = firstChildElement(walkEl, "LegEnd")

            val origin = (legStart?.let { textIn(it, "Name") ?: textIn(it, "StopPointName") })
                ?: (if (priorLegs.isNotEmpty()) priorLegs.last().destination else "Transfer")

            val destination = (legEnd?.let { textIn(it, "Name") ?: textIn(it, "StopPointName") })
                ?: origin

            // Check if TimeWindowStart / TimeWindowEnd are explicitly given
            val windowStart = textContent(walkEl, "TimeWindowStart")?.let { parseDateTimeOrNull(it) }
            val windowEnd = textContent(walkEl, "TimeWindowEnd")?.let { parseDateTimeOrNull(it) }

            val walkDuration = (textContent(walkEl, "Duration") ?: textContent(walkEl, "WalkDuration"))
                ?.let { parseIsoDuration(it) }
                ?: Duration.ofMinutes(2)

            val depTime = windowStart
                ?: (if (priorLegs.isNotEmpty()) priorLegs.last().arrival else OffsetDateTime.now(clock))

            val arrTime = windowEnd ?: depTime.plus(walkDuration)

            JourneyLeg(
                mode = LegMode.WALK,
                origin = origin,
                destination = destination,
                departure = depTime,
                arrival = arrTime,
                serviceLabel = null,
                departurePlatform = null,
                arrivalPlatform = null
            )
        } catch (e: Exception) {
            log.debug("Failed to parse Walk/Transfer leg: {}", e.message)
            null
        }
    }

    // -------------------------------------------------------------------------
    // XML & parsing helpers
    // -------------------------------------------------------------------------

    /** StopPointRef of a LegBoard/LegAlight/LegIntermediate reduced to its stop place: ch:1:sloid:7000:3:5 -> ch:1:sloid:7000. */
    private fun stopPlaceSloid(stop: Element): String? =
        textIn(stop, "StopPointRef")?.takeIf { it.startsWith("ch:1:sloid:") }?.split(":")?.take(4)?.joinToString(":")

    private fun newDocumentBuilder() =
        DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }.newDocumentBuilder()

    private fun firstChildElement(parent: Element, localName: String): Element? =
        (parent.getElementsByTagNameNS("*", localName) as NodeList).item(0) as? Element

    private fun textIn(parent: Element, localName: String): String? {
        val nl = parent.getElementsByTagNameNS("*", localName)
        for (i in 0 until nl.length) {
            val e = nl.item(i) as? Element ?: continue
            val textEl = e.getElementsByTagNameNS("*", "Text").item(0)
            val raw = textEl?.textContent?.trim() ?: e.textContent?.trim()
            if (!raw.isNullOrBlank()) return raw
        }
        return null
    }

    private fun textContent(parent: Element, localName: String): String? {
        val nl = parent.getElementsByTagNameNS("*", localName)
        return (nl.item(0) as? Element)?.textContent?.trim()?.ifBlank { null }
    }

    private fun timeAt(parent: Element, containerLocalName: String): OffsetDateTime? {
        val container = firstChildElement(parent, containerLocalName) ?: return null
        val ttEl = container.getElementsByTagNameNS("*", "TimetabledTime").item(0)
        val estEl = container.getElementsByTagNameNS("*", "EstimatedTime").item(0)
        val text = (ttEl ?: estEl)?.textContent?.trim() ?: return null
        return parseDateTimeOrNull(text)
    }

    private fun parseDateTimeOrNull(text: String): OffsetDateTime? = try {
        if (text.endsWith("Z") || text.contains("+") || text.matches(Regex(".*-\\d{2}:\\d{2}$"))) {
            OffsetDateTime.parse(text)
        } else {
            OffsetDateTime.parse("${text}Z")
        }
    } catch (e: Exception) {
        null
    }

    private fun escapeXml(s: String): String = buildString(s.length) {
        for (ch in s) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    internal fun parseIsoDuration(iso: String): Duration =
        try { Duration.parse(iso) } catch (e: Exception) { Duration.ofMinutes(2) }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            engine {
                requestTimeout = 10_000
            }
        }
    }
}
