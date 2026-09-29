package com.naomi.assistant

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

/** Public transport directions, as Transitous answers them and as she says them. */
class TransitTest {

    private val sydney = ZoneId.of("Australia/Sydney")

    private fun leg(mode: String, from: String, to: String, start: String, seconds: Int, route: String? = null, headsign: String? = null) =
        """{"mode":"$mode","from":{"name":"$from"},"to":{"name":"$to"},"startTime":"$start","duration":$seconds""" +
            (route?.let { ""","routeShortName":"$it"""" } ?: "") + (headsign?.let { ""","headsign":"$it"""" } ?: "") + "}"

    private val answer = JSONObject("""{"itineraries":[
        {"endTime":"2026-09-29T11:15:00Z","legs":[
            ${leg("WALK", "START", "Nth Beaches Hospital, Frenchs Forest Rd", "2026-09-29T10:56:00Z", 720)},
            ${leg("BUS", "Nth Beaches Hospital, Frenchs Forest Rd", "Naree Rd after Rabbett St", "2026-09-29T11:08:00Z", 0, "141", "Austlink Minna Cl")},
            ${leg("WALK", "Naree Rd after Rabbett St", "END", "2026-09-29T11:08:00Z", 420)}]},
        {"endTime":"2026-09-29T11:13:00Z","legs":[
            ${leg("WALK", "START", "Frenchs Forest Rd opp Inverness Ave", "2026-09-29T10:55:00Z", 600)},
            ${leg("BUS", "Frenchs Forest Rd opp Inverness Ave", "Rabbett St at Forest Way", "2026-09-29T11:05:00Z", 180, "160X", "Chatswood")},
            ${leg("WALK", "Rabbett St at Forest Way", "END", "2026-09-29T11:08:00Z", 300)}]},
        {"endTime":"2026-09-29T11:30:00Z","legs":[${leg("WALK", "START", "END", "2026-09-29T10:55:00Z", 2100)}]}
    ]}""")

    @Test
    fun theTripThatArrivesFirstIsTaken() {
        val trip = TransitClient.best(answer)!!
        assertEquals("160X", trip.legs[1].route)
        assertNull("walking all the way isn't a transit trip", TransitClient.best(JSONObject("""{"itineraries":[
            {"endTime":"2026-09-29T11:30:00Z","legs":[${leg("WALK", "START", "END", "2026-09-29T10:55:00Z", 2100)}]}]}""")))
    }

    @Test
    fun theWayThereIsSaidStepByStep() {
        assertEquals(
            "Walk about 10 minutes to Frenchs Forest Road opposite Inverness Avenue and catch the 160X bus towards " +
                "Chatswood at 9:05 PM. Ride 3 minutes to Rabbett Street at Forest Way, then it's a 5-minute walk to " +
                "the Woolworths at Forestway Shopping Centre. You'd get there around 9:13 PM.",
            TransitClient.spoken(TransitClient.best(answer)!!, "the Woolworths at Forestway Shopping Centre", sydney, now = null)
        )
    }

    @Test
    fun whenToLeave() {
        val trip = TransitClient.best(answer)!! // walk 10 minutes, bus at 11:05 UTC
        assertEquals("You'll want to head out now to make it.", TransitClient.leaveTip(trip, java.time.Instant.parse("2026-09-29T10:54:00Z")))
        assertNull(TransitClient.leaveTip(trip, java.time.Instant.parse("2026-09-29T10:50:00Z")))
        assertEquals("You've got about 15 minutes before you need to leave.",
            TransitClient.leaveTip(trip, java.time.Instant.parse("2026-09-29T10:40:00Z")))
    }

    @Test
    fun herLinesAroundTheWayAddNoFacts() {
        assertEquals("Bus it is, Ozzy!" to "Grab a reusable bag on your way out.",
            CloudBrain.parseFrame("""{"before": "Bus it is, Ozzy!", "after": "Grab a reusable bag on your way out."}"""))
        assertEquals("a line with a number of its own is dropped", null to "Enjoy the ride!",
            CloudBrain.parseFrame("""{"before": "It's only 10 minutes away!", "after": "Enjoy the ride!"}"""))
        assertEquals("no question after: the map offer comes next", "Easy one." to null,
            CloudBrain.parseFrame("""{"before": "Easy one.", "after": "Want a snack for the trip?"}"""))
        assertEquals("describing the request isn't a reaction; a line gets its full stop", null to "Enjoy the ride.",
            CloudBrain.parseFrame("""{"before": "Ozzy wants to know how to get to Woolworths by bus", "after": "Enjoy the ride"}"""))
        assertEquals(null to null, CloudBrain.parseFrame("not json"))
    }

    @Test
    fun aChangeOfBusAndARideWithoutATime() {
        val trip = TransitClient.best(JSONObject("""{"itineraries":[{"endTime":"2026-09-29T11:40:00Z","legs":[
            ${leg("BUS", "Skyline Shops, Frenchs Forest Rd E", "Warringah Mall", "2026-09-29T11:00:00Z", 900, "136", "Manly")},
            ${leg("WALK", "Warringah Mall", "Warringah Mall Stand C", "2026-09-29T11:15:00Z", 120)},
            ${leg("BUS", "Warringah Mall Stand C", "Dee Why Pde", "2026-09-29T11:20:00Z", 0, "B1", "Mona Vale")}]}]}"""))!!
        assertEquals(
            "Catch the 136 bus towards Manly from Skyline Shops, Frenchs Forest Road E at 9:00 PM. Ride 15 minutes to " +
                "Warringah Mall, walk 2 minutes to Warringah Mall Stand C, then change to the B1 bus towards Mona Vale at " +
                "9:20 PM, get off at Dee Why Parade, and you're there. You'd get there around 9:40 PM.",
            TransitClient.spoken(trip, "Dee Why", sydney, now = null)
        )
    }
}
