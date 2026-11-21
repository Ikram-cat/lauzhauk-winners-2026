package com.cffplanb.backend.provider

import com.cffplanb.backend.domain.SearchRequest
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.*

class OjpOccupancyAndPlacesTest {
    private val provider = OjpTransportProvider("k")

    private fun trip(boardExtra: String, alightExtra: String = "") = """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0"><OJPResponse><siri:ServiceDelivery><OJPTripDelivery><TripResult><Trip><Id>T1</Id>
 <Leg><Id>1</Id><TimedLeg>
  <LegBoard><siri:StopPointRef>ch:1:sloid:1120:0:1</siri:StopPointRef><StopPointName><Text>Lausanne</Text></StopPointName><PlannedQuay><Text>5</Text></PlannedQuay>
    <ServiceDeparture><TimetabledTime>2026-11-21T15:10:00Z</TimetabledTime>$boardExtra</ServiceDeparture></LegBoard>
  <LegAlight><siri:StopPointRef>ch:1:sloid:1008:0:1</siri:StopPointRef><StopPointName><Text>Genève</Text></StopPointName>
    <ServiceArrival><TimetabledTime>2026-11-21T16:00:00Z</TimetabledTime>$alightExtra</ServiceArrival></LegAlight>
  <Service><PublishedServiceName><Text>IC 5</Text></PublishedServiceName></Service>
 </TimedLeg></Leg></Trip></TripResult></OJPTripDelivery></siri:ServiceDelivery></OJPResponse></OJP>"""

    private fun leg(xml: String) = (provider.parseTripResponse(xml) as ProviderResult.Success).journeys.single().legs.single()

    private fun occ(first: String?, second: String?) = buildString {
        first?.let { append("<ExpectedDepartureOccupancy><FareClass>firstClass</FareClass><OccupancyLevel>$it</OccupancyLevel></ExpectedDepartureOccupancy>") }
        second?.let { append("<ExpectedDepartureOccupancy><FareClass>secondClass</FareClass><OccupancyLevel>$it</OccupancyLevel></ExpectedDepartureOccupancy>") }
    }

    @Test fun `occupancy levels map to LOW MEDIUM HIGH`() {
        val l = leg(trip(occ("manySeatsAvailable", "fewSeatsAvailable")))
        assertEquals("LOW", l.occupancyFirst); assertEquals("MEDIUM", l.occupancySecond)
        assertEquals("HIGH", leg(trip(occ("standingRoomOnly", "full"))).let { if (it.occupancyFirst == it.occupancySecond) it.occupancyFirst else null })
    }

    @Test fun `unknown or absent occupancy is null, per class`() {
        val unknown = leg(trip(occ("unknown", "manySeatsAvailable")))
        assertNull(unknown.occupancyFirst); assertEquals("LOW", unknown.occupancySecond)
        val none = leg(trip(""))
        assertNull(none.occupancyFirst); assertNull(none.occupancySecond)
    }

    @Test fun `estimated times are kept apart from the timetabled ones`() {
        val l = leg(trip("<EstimatedTime>2026-11-21T15:14:00Z</EstimatedTime>", "<EstimatedTime>2026-11-21T16:07:00Z</EstimatedTime>"))
        assertEquals(OffsetDateTime.parse("2026-11-21T15:10:00Z"), l.departure) // schedule untouched
        assertEquals(OffsetDateTime.parse("2026-11-21T15:14:00Z"), l.expectedDeparture)
        assertEquals(OffsetDateTime.parse("2026-11-21T16:07:00Z"), l.expectedArrival)
        val plain = leg(trip(""))
        assertNull(plain.expectedDeparture); assertNull(plain.expectedArrival)
    }

    @Test fun `a leading walk without times is anchored to the first timed leg, not to the parse time`() {
        val xml = trip("").replace("<Leg><Id>1</Id>", """<Leg><Id>0</Id><ContinuousLeg><LegStart><Name><Text>46.9, 7.4</Text></Name></LegStart><LegEnd><Name><Text>Lausanne</Text></Name></LegEnd><Duration>PT7M</Duration></ContinuousLeg></Leg><Leg><Id>1</Id>""")
        val j = (provider.parseTripResponse(xml) as ProviderResult.Success).journeys.single()
        assertEquals(OffsetDateTime.parse("2026-11-21T15:03:00Z"), j.legs[0].departure); assertEquals(OffsetDateTime.parse("2026-11-21T15:10:00Z"), j.legs[0].arrival)
        assertEquals(OffsetDateTime.parse("2026-11-21T15:03:00Z"), j.departure) // identical however often, whenever it is parsed
    }

    @Test fun `place and coordinate refs are not text-resolved and coordinates become a GeoPosition`() {
        val xml = provider.buildTripRequest("46.5168,6.6291", "ch:1:sloid:1008", OffsetDateTime.parse("2026-11-21T15:00:00Z"))
        assertTrue("<GeoPosition><siri:Longitude>6.6291</siri:Longitude><siri:Latitude>46.5168</siri:Latitude></GeoPosition>" in xml)
        assertTrue("<siri:StopPointRef>ch:1:sloid:1008</siri:StopPointRef>" in xml)
        assertEquals(46.5 to 6.6, provider.parseCoordinates("46.5, 6.6"))
        assertNull(provider.parseCoordinates("91,6")); assertNull(provider.parseCoordinates("Bern, 3")); assertNull(provider.parseCoordinates("ch:1:sloid:7000"))
    }

    // ---- resolvePlace through a mocked OJP ----

    private val clock = Clock.fixed(Instant.parse("2026-11-21T15:00:00Z"), ZoneOffset.UTC)
    private fun place(id: String, name: String, probability: Double? = null) = """<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri"><OJPResponse><siri:ServiceDelivery><OJPLocationInformationDelivery>
      <PlaceResult>${probability?.let { "<Probability>$it</Probability>" } ?: ""}<Place><StopPlace><StopPlaceRef>$id</StopPlaceRef><StopPlaceName><Text>$name</Text></StopPlaceName></StopPlace></Place></PlaceResult></OJPLocationInformationDelivery></siri:ServiceDelivery></OJPResponse></OJP>"""
    private val empty = """<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri"><OJPResponse><siri:ServiceDelivery><OJPLocationInformationDelivery/></siri:ServiceDelivery></OJPResponse></OJP>"""

    private fun ojp(respond: (String) -> Pair<HttpStatusCode, String>, calls: MutableList<String> = mutableListOf()) =
        OjpTransportProvider("k", clock, HttpClient(MockEngine { req ->
            val body = req.body.toByteArray().decodeToString(); calls += body
            respond(body).let { (st, b) -> respond(b, st) }
        }))

    @Test fun `text resolves to id and display name, then comes from the cache`() = runBlocking {
        val calls = mutableListOf<String>()
        val p = ojp({ HttpStatusCode.OK to place("ch:1:sloid:1008", "Genève") }, calls)
        val a = (p.resolvePlace("Genève") as PlaceResult.Found).place
        assertEquals(ResolvedPlace("Genève", "ch:1:sloid:1008", "Genève", "ch:1:sloid:1008"), a)
        p.resolvePlace("Genève"); assertEquals(1, calls.size)
    }

    private val board = """<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri"><OJPResponse><siri:ServiceDelivery><OJPStopEventDelivery><StopEventResponseContext><Places><Place><StopPlace><StopPlaceRef>ch:1:sloid:7100</StopPlaceRef><StopPlaceName><Text>Thun</Text></StopPlaceName></StopPlace></Place><Place><StopPlace><StopPlaceRef>ch:1:sloid:7000</StopPlaceRef><StopPlaceName><Text>Bern</Text></StopPlaceName></StopPlace></Place></Places></StopEventResponseContext><StopEventResult><StopEvent/></StopEventResult></OJPStopEventDelivery></siri:ServiceDelivery></OJPResponse></OJP>"""
    private val noEvents = """<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri"><OJPResponse><siri:ServiceDelivery><OJPStopEventDelivery><ErrorCondition><StopEventProblemType>STOPEVENT_NOEVENTFOUND</StopEventProblemType></ErrorCondition></OJPStopEventDelivery></siri:ServiceDelivery></OJPResponse></OJP>"""

    @Test fun `fuzzy matches below OJP's own score threshold are NotFound, good ones are kept`() = runBlocking {
        assertEquals(PlaceResult.NotFound, ojp({ HttpStatusCode.OK to place("ch:1:sloid:70582", "Noville, Pré des Fourches", 0.331) }).resolvePlace("Nowhereville-XYZ"))
        assertIs<PlaceResult.Found>(ojp({ HttpStatusCode.OK to place("ch:1:sloid:1120", "Lausanne", 0.892) }).resolvePlace("Lausane"))
        assertIs<PlaceResult.Found>(ojp({ HttpStatusCode.OK to place("ch:1:sloid:1120", "Lausanne") }).resolvePlace("Lausanne")) // no score given: accepted
    }

    @Test fun `a stop id is verified through its departure board and echoed, an id without one is NotFound`() = runBlocking {
        val calls = mutableListOf<String>()
        val ok = ojp({ HttpStatusCode.OK to board }, calls)
        assertEquals(ResolvedPlace("ch:1:sloid:7000", "ch:1:sloid:7000", "Bern", "ch:1:sloid:7000"), (ok.resolvePlace("ch:1:sloid:7000") as PlaceResult.Found).place)
        assertTrue("OJPStopEventRequest" in calls.single() && "InitialInput" !in calls.single())
        ok.resolvePlace("ch:1:sloid:7000"); assertEquals(1, calls.size) // verified once
        assertEquals(PlaceResult.NotFound, ojp({ HttpStatusCode.OK to noEvents }).resolvePlace("ch:1:sloid:99999999"))
        assertEquals(PlaceResult.NotFound, ojp({ HttpStatusCode.OK to empty }).resolvePlace("Nowhereville-XYZ"))
    }

    @Test fun `coordinates need no lookup at all`() = runBlocking {
        val calls = mutableListOf<String>()
        val r = (ojp({ HttpStatusCode.InternalServerError to "x" }, calls).resolvePlace("46.5168,6.6291") as PlaceResult.Found).place
        assertEquals(ResolvedPlace("46.5168,6.6291", null, null, "46.5168,6.6291"), r); assertTrue(calls.isEmpty())
    }

    @Test fun `upstream problems are not NotFound`() = runBlocking {
        assertIs<PlaceResult.RateLimited>(ojp({ HttpStatusCode.TooManyRequests to "" }).resolvePlace("Bern"))
        assertIs<PlaceResult.Failure>(ojp({ HttpStatusCode.Unauthorized to "" }).resolvePlace("Bern"))
        assertIs<PlaceResult.Failure>(ojp({ HttpStatusCode.InternalServerError to "" }).resolvePlace("Bern"))
    }

    @Test fun `a trip search with a stop id skips text resolution`() = runBlocking {
        val calls = mutableListOf<String>()
        val p = ojp({ HttpStatusCode.OK to "<OJP xmlns=\"http://www.vdv.de/ojp\"/>" }, calls)
        p.search(SearchRequest("ch:1:sloid:7000", "46.5,6.6", OffsetDateTime.parse("2026-11-21T15:00:00Z")))
        assertEquals(1, calls.size); assertTrue("OJPTripRequest" in calls[0] && "InitialInput" !in calls[0])
    }

    @Test fun `unresolvable origin and destination name their field`() = runBlocking {
        val p = ojp({ HttpStatusCode.OK to empty })
        assertEquals("origin", (p.search(SearchRequest("Nowhere", "Bern", OffsetDateTime.parse("2026-11-21T15:00:00Z"))) as ProviderResult.LocationResolutionError).field)
        val q = ojp({ b -> HttpStatusCode.OK to if ("Bern" in b) place("ch:1:sloid:7000", "Bern") else empty })
        assertEquals("destination", (q.search(SearchRequest("Bern", "Nowhere", OffsetDateTime.parse("2026-11-21T15:00:00Z"))) as ProviderResult.LocationResolutionError).field)
    }
}
