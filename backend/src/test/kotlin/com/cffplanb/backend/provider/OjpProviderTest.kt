package com.cffplanb.backend.provider

import com.cffplanb.backend.domain.DataStatus
import com.cffplanb.backend.domain.LegMode
import com.cffplanb.backend.domain.SearchRequest
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class OjpProviderTest {

    private val provider = OjpTransportProvider(apiKey = "test-key")
    private val baseRequest = SearchRequest(
        origin = "Lausanne",
        destination = "Genève",
        earliestDeparture = OffsetDateTime.of(2026, 11, 21, 15, 0, 0, 0, ZoneOffset.ofHours(1))
    )

    private fun loadFixture(): String {
        return OjpProviderTest::class.java.classLoader
            .getResourceAsStream("ojp_fixture.xml")!!
            .bufferedReader()
            .readText()
    }

    @Test
    fun `parses two trips from fixture`() {
        val xml = loadFixture()
        val result = provider.parseOjpResponse(xml, baseRequest)
        assertIs<ProviderResult.Success>(result)
        assertEquals(2, result.journeys.size)
    }

    @Test
    fun `first trip is direct IC 702 Lausanne to Genève`() {
        val xml = loadFixture()
        val result = provider.parseOjpResponse(xml, baseRequest) as ProviderResult.Success
        val trip1 = result.journeys.first { it.id == "TRIP-001-DIRECT" }

        assertEquals("TRIP-001-DIRECT", trip1.id)
        assertEquals(0, trip1.transfers)
        assertEquals(DataStatus.LIVE_SCHEDULE, trip1.dataStatus)
        assertEquals(1, trip1.legs.size)

        val leg = trip1.legs[0]
        assertEquals(LegMode.TRANSIT, leg.mode)
        assertEquals("Lausanne", leg.origin)
        assertEquals("Genève", leg.destination)
        assertEquals("IC 702", leg.serviceLabel)
        assertEquals("3A", leg.departurePlatform)
    }

    @Test
    fun `second trip has three legs including walk transfer`() {
        val xml = loadFixture()
        val result = provider.parseOjpResponse(xml, baseRequest) as ProviderResult.Success
        val trip2 = result.journeys.first { it.id == "TRIP-002-TRANSFER" }

        assertEquals(3, trip2.legs.size)
        assertEquals(LegMode.TRANSIT, trip2.legs[0].mode)
        assertEquals(LegMode.WALK, trip2.legs[1].mode)
        assertEquals(LegMode.TRANSIT, trip2.legs[2].mode)

        // 2 transit legs → 1 transfer
        assertEquals(1, trip2.transfers)
    }

    @Test
    fun `trip timestamps are offset-aware`() {
        val xml = loadFixture()
        val result = provider.parseOjpResponse(xml, baseRequest) as ProviderResult.Success
        val trip = result.journeys.first { it.id == "TRIP-001-DIRECT" }

        // departure at 16:10+01:00
        assertEquals(16, trip.departure.hour)
        assertEquals(10, trip.departure.minute)
        assertEquals(ZoneOffset.ofHours(1), trip.departure.offset)

        // arrival at 17:00+01:00
        assertEquals(17, trip.arrival.hour)
        assertEquals(0, trip.arrival.minute)
    }

    @Test
    fun `returns failure on malformed xml`() {
        val result = provider.parseOjpResponse("<not valid xml<<<", baseRequest)
        assertIs<ProviderResult.Failure>(result)
    }

    @Test
    fun `returns success with empty list on xml with no trips`() {
        val emptyXml = """<?xml version="1.0" encoding="UTF-8"?>
<OJP xmlns="http://www.vdv.de/ojp" xmlns:siri="http://www.siri.org.uk/siri" version="2.0">
  <OJPResponse>
    <siri:ServiceDelivery>
      <ojp:OJPTripDelivery xmlns:ojp="http://www.vdv.de/ojp">
        <siri:Status>true</siri:Status>
      </ojp:OJPTripDelivery>
    </siri:ServiceDelivery>
  </OJPResponse>
</OJP>"""
        val result = provider.parseOjpResponse(emptyXml, baseRequest)
        assertIs<ProviderResult.Success>(result)
        assertEquals(0, result.journeys.size)
    }

    @Test
    fun `mock provider returns three journeys with MOCK status`() = kotlinx.coroutines.test.runTest {
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
            assert(j.legs.isNotEmpty())
        }
    }

    @Test
    fun `mock provider departure times are relative to request time`() = kotlinx.coroutines.test.runTest {
        val mock = MockTransportProvider()
        val base = OffsetDateTime.of(2026, 6, 15, 10, 0, 0, 0, ZoneOffset.ofHours(2))
        val request = SearchRequest("A", "B", base)
        val result = mock.search(request) as ProviderResult.Success

        result.journeys.forEach { j ->
            assert(!j.departure.toInstant().isBefore(base.toInstant())) {
                "Journey ${j.id} departs before earliestDeparture"
            }
        }
    }
}
