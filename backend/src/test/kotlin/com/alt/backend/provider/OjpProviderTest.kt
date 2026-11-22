package com.alt.backend.provider

import com.alt.backend.domain.DataStatus
import com.alt.backend.domain.LegMode
import com.alt.backend.domain.SearchRequest
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OjpProviderTest {

    private val provider = OjpTransportProvider(apiKey = "test-key")
    private val baseRequest = SearchRequest(
        origin = "Lausanne",
        destination = "Genève",
        earliestDeparture = OffsetDateTime.of(2026, 11, 21, 14, 0, 0, 0, ZoneOffset.UTC)
    )

    private fun loadFixture(): String {
        return OjpProviderTest::class.java.classLoader
            .getResourceAsStream("ojp_fixture.xml")!!
            .bufferedReader()
            .readText()
    }

    @Test
    fun `parses three valid trips from fixture (cancelled leg is skipped)`() {
        val xml = loadFixture()
        val result = provider.parseTripResponse(xml)
        assertIs<ProviderResult.Success>(result)
        // Trip 1 (direct), Trip 2 (transfer), Trip 4 (optional fields missing)
        // Trip 3 (cancelled/no departure) has no valid legs and is skipped
        assertEquals(3, result.journeys.size)
    }

    @Test
    fun `first trip is direct IC702 Lausanne to Genève`() {
        val xml = loadFixture()
        val result = provider.parseTripResponse(xml) as ProviderResult.Success
        val trip1 = result.journeys.first { it.id == "TRIP-001-DIRECT" }

        assertEquals("TRIP-001-DIRECT", trip1.id)
        assertEquals(0, trip1.transfers)
        assertEquals(DataStatus.LIVE_SCHEDULE, trip1.dataStatus)
        assertEquals(1, trip1.legs.size)

        val leg = trip1.legs[0]
        assertEquals(LegMode.TRANSIT, leg.mode)
        assertEquals("Lausanne", leg.origin)
        assertEquals("Genève", leg.destination)
        assertEquals("IC702", leg.serviceLabel)
        assertEquals("3A", leg.departurePlatform)
        assertEquals("ch:1:sloid:7000031", leg.originSloid)
        assertEquals("ch:1:sloid:7000008", leg.destinationSloid)
    }

    @Test
    fun `second trip has three legs including walk transfer`() {
        val xml = loadFixture()
        val result = provider.parseTripResponse(xml) as ProviderResult.Success
        val trip2 = result.journeys.first { it.id == "TRIP-002-TRANSFER" }

        assertEquals(3, trip2.legs.size)
        assertEquals(LegMode.TRANSIT, trip2.legs[0].mode)
        assertEquals(LegMode.WALK, trip2.legs[1].mode)
        assertEquals(LegMode.TRANSIT, trip2.legs[2].mode)

        // 2 transit legs -> 1 transfer
        assertEquals(1, trip2.transfers)
    }

    @Test
    fun `trip with optional fields missing parses successfully with nulls`() {
        val xml = loadFixture()
        val result = provider.parseTripResponse(xml) as ProviderResult.Success
        val trip4 = result.journeys.first { it.id == "TRIP-004-OPTIONAL-MISSING" }

        assertEquals(1, trip4.legs.size)
        val leg = trip4.legs[0]
        assertEquals("Lausanne", leg.origin)
        assertEquals("Genève", leg.destination)
        assertNull(leg.serviceLabel)
        assertNull(leg.departurePlatform)
        assertNull(leg.arrivalPlatform)
    }

    @Test
    fun `trip timestamps are UTC offset-aware`() {
        val xml = loadFixture()
        val result = provider.parseTripResponse(xml) as ProviderResult.Success
        val trip = result.journeys.first { it.id == "TRIP-001-DIRECT" }

        // departure at 15:10 UTC
        assertEquals(15, trip.departure.withOffsetSameInstant(ZoneOffset.UTC).hour)
        assertEquals(10, trip.departure.withOffsetSameInstant(ZoneOffset.UTC).minute)

        // arrival at 16:00 UTC
        assertEquals(16, trip.arrival.withOffsetSameInstant(ZoneOffset.UTC).hour)
        assertEquals(0, trip.arrival.withOffsetSameInstant(ZoneOffset.UTC).minute)
    }

    @Test
    fun `can parse real OJP response from tmp if available`() {
        val realFile = File("/tmp/ojp_ok.xml")
        if (realFile.exists()) {
            val xml = realFile.readText()
            val result = provider.parseTripResponse(xml)
            assertIs<ProviderResult.Success>(result)
            assertTrue(result.journeys.isNotEmpty())
            result.journeys.forEach { j ->
                assertEquals(DataStatus.LIVE_SCHEDULE, j.dataStatus)
                assertTrue(j.legs.isNotEmpty())
            }
        }
    }

    @Test
    fun `returns failure on malformed xml`() {
        val result = provider.parseTripResponse("<not valid xml<<<")
        assertIs<ProviderResult.Failure>(result)
    }

    @Test
    fun `returns success with empty list on xml with no trips`() {
        val emptyXml = """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPResponse>
    <siri:ServiceDelivery>
      <OJPTripDelivery>
        <siri:Status>true</siri:Status>
      </OJPTripDelivery>
    </siri:ServiceDelivery>
  </OJPResponse>
</OJP>"""
        val result = provider.parseTripResponse(emptyXml)
        assertIs<ProviderResult.Success>(result)
        assertEquals(0, result.journeys.size)
    }

    @Test
    fun `cancelled leg with missing ServiceDeparture yields no legs and trip is skipped`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPResponse>
    <siri:ServiceDelivery>
      <OJPTripDelivery>
        <TripResult>
          <Id>R1</Id>
          <Trip>
            <Id>TRIP-NO-DEP</Id>
            <Leg>
              <Id>1</Id>
              <TimedLeg>
                <LegBoard>
                  <siri:StopPointRef>ch:1:sloid:7000031</siri:StopPointRef>
                  <StopPointName><Text>Lausanne</Text></StopPointName>
                  <!-- no ServiceDeparture -->
                </LegBoard>
                <LegAlight>
                  <siri:StopPointRef>ch:1:sloid:7000008</siri:StopPointRef>
                  <StopPointName><Text>Genève</Text></StopPointName>
                  <ServiceArrival>
                    <TimetabledTime>2026-11-21T16:00:00Z</TimetabledTime>
                  </ServiceArrival>
                </LegAlight>
              </TimedLeg>
            </Leg>
          </Trip>
        </TripResult>
      </OJPTripDelivery>
    </siri:ServiceDelivery>
  </OJPResponse>
</OJP>"""
        val result = provider.parseTripResponse(xml)
        assertIs<ProviderResult.Success>(result)
        assertEquals(0, result.journeys.size)
    }

    @Test
    fun `location response parser extracts sloid from StopPlaceRef`() {
        val locXml = """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPResponse>
    <siri:ServiceDelivery>
      <OJPLocationInformationDelivery>
        <PlaceResult>
          <Place>
            <StopPlace>
              <StopPlaceRef>ch:1:sloid:1120</StopPlaceRef>
              <StopPlaceName><Text xml:lang="de">Lausanne</Text></StopPlaceName>
            </StopPlace>
          </Place>
        </PlaceResult>
      </OJPLocationInformationDelivery>
    </siri:ServiceDelivery>
  </OJPResponse>
</OJP>"""
        val result = provider.parseLocationResponse(locXml)
        assertEquals("ch:1:sloid:1120", result)
    }

    @Test
    fun `location response parser returns null when no sloid present`() {
        val locXml = """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPResponse>
    <siri:ServiceDelivery>
      <OJPLocationInformationDelivery/>
    </siri:ServiceDelivery>
  </OJPResponse>
</OJP>"""
        val result = provider.parseLocationResponse(locXml)
        assertNull(result)
    }

    @Test
    fun `ISO-8601 duration parsing handles all formats`() {
        assertEquals(java.time.Duration.ofMinutes(5), provider.parseIsoDuration("PT5M"))
        assertEquals(java.time.Duration.ofMinutes(54).plusSeconds(54), provider.parseIsoDuration("PT54M54S"))
        assertEquals(java.time.Duration.ofHours(1).plusMinutes(17), provider.parseIsoDuration("PT1H17M"))
        // Fallback for invalid input
        assertEquals(java.time.Duration.ofMinutes(2), provider.parseIsoDuration("INVALID"))
    }

    @Test
    fun `mock provider returns three journeys with MOCK status`() = runTest {
        val mock = MockTransportProvider()
        val request = SearchRequest(
            origin = "Lausanne",
            destination = "Genève",
            earliestDeparture = OffsetDateTime.now(ZoneOffset.UTC)
        )
        val result = mock.search(request)
        assertIs<ProviderResult.Success>(result)
        assertEquals(3, result.journeys.size)
        result.journeys.forEach { j ->
            assertEquals(DataStatus.MOCK, j.dataStatus)
            assertTrue(j.legs.isNotEmpty())
        }
    }

    @Test
    fun `mock provider departure times are relative to request time`() = runTest {
        val mock = MockTransportProvider()
        val base = OffsetDateTime.of(2026, 6, 15, 10, 0, 0, 0, ZoneOffset.ofHours(2))
        val request = SearchRequest("A", "B", base)
        val result = mock.search(request) as ProviderResult.Success

        result.journeys.forEach { j ->
            assertTrue(
                !j.departure.toInstant().isBefore(base.toInstant()),
                "Journey ${j.id} departs before earliestDeparture"
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Live integration test — skipped when OJP_API_KEY is absent
    // ---------------------------------------------------------------------------

    @Test
    fun `LIVE - search Lausanne to Geneve returns at least one journey`() {
        val key = System.getenv("OJP_API_KEY")?.trim()
        if (key.isNullOrEmpty()) {
            println("[SKIP] OJP_API_KEY not set — skipping live integration test")
            return
        }

        val liveProvider = OjpTransportProvider(apiKey = key)
        val request = SearchRequest(
            origin = "Lausanne",
            destination = "Genève",
            earliestDeparture = OffsetDateTime.now(ZoneOffset.UTC)
        )

        val result = runBlocking {
            liveProvider.search(request)
        }

        assertIs<ProviderResult.Success>(result)
        assertTrue(result.journeys.isNotEmpty(), "Expected at least one live journey")
        result.journeys.forEach { j ->
            assertEquals(DataStatus.LIVE_SCHEDULE, j.dataStatus)
            assertTrue(j.legs.isNotEmpty(), "Journey ${j.id} has no legs")
            assertTrue(j.legs.first().origin.contains("Lausanne", ignoreCase = true))
            assertTrue(j.legs.last().destination.contains("Genève", ignoreCase = true) ||
                    j.legs.last().destination.contains("Geneve", ignoreCase = true))
        }
    }

    @Test
    fun `location suggestions parse stops and addresses`() {
        val xml = javaClass.getResource("/ojp_locations_fixture.xml")!!.readText()
        val r = provider.parseLocationSuggestions(xml)
        assertEquals(3, r.size)
        assertEquals(LocationSuggestion("ch:1:sloid:1120", "Lausanne (Lausanne)", "stop", 6.62909, 46.51679), r[0])
        assertEquals("address", r[1].kind)
        assertEquals("Bahnhofstrasse 1 (Zürich)", r[1].name)
        assertNull(r[2].x)
        assertNull(r[2].y)
        assertEquals(emptyList(), provider.parseLocationSuggestions("<broken"))
    }
}
