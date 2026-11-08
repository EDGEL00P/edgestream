package com.edgestream.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DailyForecastTest {
    @Test fun parsesDays() {
        val d = JSONObject(
            """{"time":["2026-11-08","2026-11-09"],"weather_code":[3,61],
               "temperature_2m_max":[24.5,19.0],"temperature_2m_min":[12.1,10.0]}""",
        )
        assertEquals(
            listOf(PublicApis.DayPoint("2026-11-08", 3, 24.5, 12.1), PublicApis.DayPoint("2026-11-09", 61, 19.0, 10.0)),
            PublicApis.parseDaily(d),
        )
    }

    @Test fun missingOrShortArraysAreSkipped() {
        assertEquals(emptyList<PublicApis.DayPoint>(), PublicApis.parseDaily(null))
        val short = JSONObject("""{"time":["2026-11-08","2026-11-09"],"weather_code":[3],"temperature_2m_max":[24.5],"temperature_2m_min":[12.1]}""")
        assertEquals(1, PublicApis.parseDaily(short).size)
    }
}
