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
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.NodeList
import java.io.ByteArrayInputStream

class OjpTransportProvider(
    private val apiKey: String,
    private val httpClient: HttpClient = defaultClient()
) : TransportProvider {

    private val log = LoggerFactory.getLogger(OjpTransportProvider::class.java)
    private val ojpEndpoint = "https://api.opentransportdata.swiss/ojp2020"
    private val timeoutMs = 8_000L

    override suspend fun search(request: SearchRequest): ProviderResult {
        val xml = buildTripRequest(request)

        val result = withTimeoutOrNull(timeoutMs) {
            try {
                val response: HttpResponse = httpClient.post(ojpEndpoint) {
                    header(HttpHeaders.Authorization, "Bearer $apiKey")
                    contentType(ContentType.Application.Xml)
                    accept(ContentType.Application.Xml)
                    setBody(xml)
                }
                when (response.status.value) {
                    200 -> {
                        val body = response.bodyAsText()
                        parseOjpResponse(body, request)
                    }
                    429 -> ProviderResult.RateLimited("OJP rate limit reached")
                    else -> ProviderResult.Failure("OJP returned HTTP ${response.status.value}")
                }
            } catch (e: Exception) {
                log.error("OJP provider error: ${e.javaClass.simpleName}")
                ProviderResult.Failure("OJP call failed: ${e.javaClass.simpleName}")
            }
        }

        return result ?: ProviderResult.Timeout("OJP did not respond within ${timeoutMs}ms")
    }

    private fun buildTripRequest(request: SearchRequest): String {
        val ts = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(request.earliestDeparture)
        return """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPRequest>
    <siri:ServiceRequest>
      <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
      <siri:RequestorRef>cff-plan-b</siri:RequestorRef>
      <ojp:OJPTripRequest xmlns:ojp="http://www.vdv.de/ojp">
        <siri:RequestTimestamp>$ts</siri:RequestTimestamp>
        <ojp:Origin>
          <ojp:PlaceRef>
            <ojp:StopPlaceRef>${request.origin}</ojp:StopPlaceRef>
            <ojp:LocationName><ojp:Text>${escapeXml(request.origin)}</ojp:Text></ojp:LocationName>
          </ojp:PlaceRef>
          <ojp:DepArrTime>$ts</ojp:DepArrTime>
        </ojp:Origin>
        <ojp:Destination>
          <ojp:PlaceRef>
            <ojp:StopPlaceRef>${request.destination}</ojp:StopPlaceRef>
            <ojp:LocationName><ojp:Text>${escapeXml(request.destination)}</ojp:Text></ojp:LocationName>
          </ojp:PlaceRef>
        </ojp:Destination>
        <ojp:Params>
          <ojp:IncludeTrackSections>false</ojp:IncludeTrackSections>
          <ojp:IncludeLegProjection>false</ojp:IncludeLegProjection>
          <ojp:NumberOfResults>5</ojp:NumberOfResults>
        </ojp:Params>
      </ojp:OJPTripRequest>
    </siri:ServiceRequest>
  </OJPRequest>
</OJP>"""
    }

    private fun escapeXml(s: String) = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    internal fun parseOjpResponse(xml: String, request: SearchRequest): ProviderResult {
        return try {
            val factory = DocumentBuilderFactory.newInstance().also { it.isNamespaceAware = true }
            val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

            val tripResults = doc.getElementsByTagNameNS("*", "TripResult")
            val journeys = mutableListOf<Journey>()

            for (i in 0 until tripResults.length) {
                val tripResult = tripResults.item(i) as? Element ?: continue
                val trip = firstChildElement(tripResult, "Trip") ?: continue
                val tripId = trip.getElementsByTagNameNS("*", "TripId").item(0)?.textContent?.trim()
                    ?: "ojp-trip-$i"

                val legs = mutableListOf<JourneyLeg>()
                val tripLegs = trip.getElementsByTagNameNS("*", "TripLeg")

                for (j in 0 until tripLegs.length) {
                    val tripLeg = tripLegs.item(j) as? Element ?: continue
                    val timedLeg = firstChildElement(tripLeg, "TimedLeg")
                    val transferLeg = firstChildElement(tripLeg, "TransferLeg")

                    when {
                        timedLeg != null -> parseTimedLeg(timedLeg)?.let { legs.add(it) }
                        transferLeg != null -> parseTransferLeg(transferLeg)?.let { legs.add(it) }
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
            log.error("OJP parse error: ${e.javaClass.simpleName}: ${e.message}")
            ProviderResult.Failure("Failed to parse OJP response")
        }
    }

    private fun parseTimedLeg(timedLeg: Element): JourneyLeg? {
        return try {
            val board = firstChildElement(timedLeg, "LegBoard") ?: return null
            val alight = firstChildElement(timedLeg, "LegAlight") ?: return null
            val service = firstChildElement(timedLeg, "Service")

            val origin = textIn(board, "StopPointName") ?: textIn(board, "Text") ?: return null
            val destination = textIn(alight, "StopPointName") ?: textIn(alight, "Text") ?: return null

            val depTime = timeAt(board, "ServiceDeparture") ?: timeAt(board, "TimetabledTime") ?: return null
            val arrTime = timeAt(alight, "ServiceArrival") ?: timeAt(alight, "TimetabledTime") ?: return null

            val serviceLabel = service?.let { textIn(it, "PublishedLineName") ?: textIn(it, "Text") }
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
                arrivalPlatform = arrPlatform
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTransferLeg(transferLeg: Element): JourneyLeg? {
        return try {
            val origin = textIn(transferLeg, "LegStart")
                ?: transferLeg.getElementsByTagNameNS("*", "StopPointName").item(0)?.textContent?.trim()
                ?: return null
            val destination = textIn(transferLeg, "LegEnd") ?: origin

            val depText = transferLeg.getElementsByTagNameNS("*", "TimeWindowStart").item(0)?.textContent?.trim()
                ?: return null
            val arrText = transferLeg.getElementsByTagNameNS("*", "TimeWindowEnd").item(0)?.textContent?.trim()
                ?: depText

            val dep = OffsetDateTime.parse(depText)
            val arr = OffsetDateTime.parse(arrText)

            JourneyLeg(
                mode = LegMode.WALK,
                origin = origin,
                destination = destination,
                departure = dep,
                arrival = arr,
                serviceLabel = null,
                departurePlatform = null,
                arrivalPlatform = null
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun firstChildElement(parent: Element, localName: String): Element? {
        val nl: NodeList = parent.getElementsByTagNameNS("*", localName)
        return nl.item(0) as? Element
    }

    private fun textIn(parent: Element, localName: String): String? {
        val nl = parent.getElementsByTagNameNS("*", localName)
        for (i in 0 until nl.length) {
            val e = nl.item(i) as? Element ?: continue
            // Prefer nested Text element, else direct text
            val textEl = e.getElementsByTagNameNS("*", "Text").item(0)
            val raw = textEl?.textContent?.trim() ?: e.textContent?.trim()
            if (!raw.isNullOrBlank()) return raw
        }
        return null
    }

    private fun timeAt(parent: Element, containerLocalName: String): OffsetDateTime? {
        val container = firstChildElement(parent, containerLocalName) ?: return null
        // Look for TimetabledTime inside
        val timeEl = container.getElementsByTagNameNS("*", "TimetabledTime").item(0)
            ?: container.getElementsByTagNameNS("*", "EstimatedTime").item(0)
        val text = timeEl?.textContent?.trim() ?: container.textContent?.trim() ?: return null
        return try {
            OffsetDateTime.parse(text)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        fun defaultClient(): HttpClient = HttpClient(CIO) {
            engine {
                requestTimeout = 10_000
            }
        }
    }
}
