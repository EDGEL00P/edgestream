package com.edgestream.tv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceSearchTest {
    // Trimmed from a real Open-Meteo geocoding response for "Springfield".
    private val response = JSONObject(
        """{"results":[
          {"id":4409896,"name":"Springfield","latitude":37.21533,"longitude":-93.29824,"country_code":"US","admin1":"Missouri","country":"United States"},
          {"id":4250542,"name":"Springfield","latitude":39.80172,"longitude":-89.64371,"country_code":"US","admin1":"Illinois","country":"United States"},
          {"id":4250543,"name":"Springfield","latitude":39.80172,"longitude":-89.64371,"country_code":"US","admin1":"Illinois","country":"United States"},
          {"id":2209055,"name":"Singapore","latitude":1.28967,"longitude":103.85007,"country_code":"SG","admin1":"Singapore","country":"Singapore"}
        ]}""",
    )

    @Test fun everyMatchIsOfferedOnceWithWhatTellsThemApart() {
        val places = PublicApis.parsePlaces(response)
        assertEquals(listOf("Missouri, United States", "Illinois, United States", "Singapore"), places.map { it.detail })
    }

    @Test fun noResultsMeansNoMatches() {
        assertEquals(emptyList<PublicApis.Place>(), PublicApis.parsePlaces(JSONObject("""{"generationtime_ms":0.5}""")))
    }

    @Test fun pickedPlaceSurvivesSaveAndLoad() {
        val place = PublicApis.parsePlaces(response)[1]
        assertEquals(place, PublicApis.Place.fromJson(place.toJson()))
        assertEquals(39.80172, place.toLocation().lat, 0.0)
        assertNull(PublicApis.Place.fromJson("not json"))
    }
}
