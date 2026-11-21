package com.cffplanb.backend.provider

import com.cffplanb.backend.domain.DataStatus
import com.cffplanb.backend.domain.Journey
import com.cffplanb.backend.domain.JourneyLeg
import com.cffplanb.backend.domain.LegMode
import com.cffplanb.backend.domain.SearchRequest
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

class MockTransportProvider : TransportProvider {

    override suspend fun search(request: SearchRequest): ProviderResult {
        val base = request.earliestDeparture

        // Journey 1: direct, +10min departure, arrives +60min
        val dep1 = base.plusMinutes(10)
        val arr1 = base.plusMinutes(60)
        val j1 = Journey(
            id = "mock-direct-1",
            departure = dep1,
            arrival = arr1,
            durationMinutes = ChronoUnit.MINUTES.between(dep1, arr1).toInt(),
            transfers = 0,
            legs = listOf(
                JourneyLeg(
                    mode = LegMode.TRANSIT,
                    origin = request.origin,
                    destination = request.destination,
                    departure = dep1,
                    arrival = arr1,
                    serviceLabel = "Demo rail service",
                    departurePlatform = "3A",
                    arrivalPlatform = null
                )
            ),
            dataStatus = DataStatus.MOCK
        )

        // Journey 2: with transfer, departs +5min, arrives +80min
        val dep2 = base.plusMinutes(5)
        val midArr2 = base.plusMinutes(35)
        val midDep2 = base.plusMinutes(40)
        val arr2 = base.plusMinutes(80)
        val j2 = Journey(
            id = "mock-transfer-2",
            departure = dep2,
            arrival = arr2,
            durationMinutes = ChronoUnit.MINUTES.between(dep2, arr2).toInt(),
            transfers = 1,
            legs = listOf(
                JourneyLeg(
                    mode = LegMode.TRANSIT,
                    origin = request.origin,
                    destination = "Morges",
                    departure = dep2,
                    arrival = midArr2,
                    serviceLabel = "Demo regional",
                    departurePlatform = "1",
                    arrivalPlatform = "2"
                ),
                JourneyLeg(
                    mode = LegMode.TRANSIT,
                    origin = "Morges",
                    destination = request.destination,
                    departure = midDep2,
                    arrival = arr2,
                    serviceLabel = "Demo express",
                    departurePlatform = "2",
                    arrivalPlatform = null
                )
            ),
            dataStatus = DataStatus.MOCK
        )

        // Journey 3: late direct, +40min departure, arrives +90min
        val dep3 = base.plusMinutes(40)
        val arr3 = base.plusMinutes(90)
        val j3 = Journey(
            id = "mock-direct-3",
            departure = dep3,
            arrival = arr3,
            durationMinutes = ChronoUnit.MINUTES.between(dep3, arr3).toInt(),
            transfers = 0,
            legs = listOf(
                JourneyLeg(
                    mode = LegMode.TRANSIT,
                    origin = request.origin,
                    destination = request.destination,
                    departure = dep3,
                    arrival = arr3,
                    serviceLabel = "Demo IC",
                    departurePlatform = "5",
                    arrivalPlatform = "7"
                )
            ),
            dataStatus = DataStatus.MOCK
        )

        return ProviderResult.Success(listOf(j1, j2, j3))
    }
}
