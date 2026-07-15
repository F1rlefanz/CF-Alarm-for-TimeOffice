package com.github.f1rlefanz.cf_alarmfortimeoffice.hue

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeSchedule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeScheduleCommand
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.BridgeScheduleCreate
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinnt die Drahtform der bridge-seitigen Zeitplaene.
 *
 * WARUM DAS EIN TEST IST: Genau dieses JSON wurde am 15.07.2026 gegen die echte Bridge
 * verifiziert (BSB002, apiversion 1.78.0) und dort akzeptiert. Die Feldnamen sind ein
 * Fremdvertrag - die Bridge kennt nur `address`/`method`/`body`/`localtime`/`autodelete`.
 * Ein Umbenennen des Kotlin-Feldes waere aus Sicht des Compilers harmlos, wuerde die Bridge
 * den Zeitplan aber mit Fehler 7 ablehnen. Da das Auto-Aus seit v1.11.0 ausschliesslich
 * ueber die Bridge laeuft, bliebe das Licht danach an.
 */
class BridgeScheduleSerializationTest {

    private val gson = Gson()

    /** Genau die Nutzlast, die die App zur Weckzeit fuer eine Gruppe absetzt. */
    private fun sampleCreate() = BridgeScheduleCreate(
        name = "CFAlarm Auto-Off G11",
        description = "Auto-Off Fruehschicht +30min",
        command = BridgeScheduleCommand(
            address = "/api/0Dy3PfxOHMzcRoCASadMNrJXQNSvfssgTasdVWps/groups/11/action",
            method = "PUT",
            body = mapOf("on" to false)
        ),
        localtime = "PT00:30:00",
        autodelete = true
    )

    @Test
    fun `Anlege-Payload entspricht der von der Bridge akzeptierten Form`() {
        val expected = """
            {"name":"CFAlarm Auto-Off G11",
             "description":"Auto-Off Fruehschicht +30min",
             "command":{"address":"/api/0Dy3PfxOHMzcRoCASadMNrJXQNSvfssgTasdVWps/groups/11/action",
                        "method":"PUT",
                        "body":{"on":false}},
             "localtime":"PT00:30:00",
             "autodelete":true}
        """.trimIndent().replace(Regex("\\s*\\n\\s*"), "")

        assertEquals(expected, gson.toJson(sampleCreate()))
    }

    @Test
    fun `command bleibt trotz 40-Zeichen-Username akzeptiert`() {
        // Die offizielle Doku (API 1.0, 2013) nennt fuer `command` ein Limit von 90 Zeichen.
        // Real sind es mit einem echten Username ~111 - und die Bridge nimmt es an (verifiziert
        // 15.07.2026, Firmware 1.78.0). Der Test haelt fest, dass wir bewusst darueber liegen:
        // schlaegt das kuenftig fehl, ist es die Bridge, die sich geaendert hat, nicht wir.
        val commandJson = gson.toJson(sampleCreate().command)
        assertTrue(
            "Command ist erwartungsgemaess laenger als die dokumentierten 90 Zeichen: ${commandJson.length}",
            commandJson.length > 90
        )
    }

    @Test
    fun `Antwort der Bridge wird auf unsere Felder abgebildet`() {
        // Wortwoertlich die Antwort der echten Bridge auf GET /schedules/<id>.
        val json = """
            {
              "name": "CFAlarm Auto-Off G11",
              "description": "Auto-Off Fruehschicht +30min",
              "command": {
                "address": "/api/0Dy3PfxOHMzcRoCASadMNrJXQNSvfssgTasdVWps/groups/11/action",
                "body": { "on": false },
                "method": "PUT"
              },
              "localtime": "PT00:30:00",
              "time": "PT00:30:00",
              "created": "2026-07-15T14:51:30",
              "status": "enabled",
              "autodelete": true,
              "starttime": "2026-07-15T14:51:30",
              "recycle": false
            }
        """.trimIndent()

        val schedule = gson.fromJson(json, BridgeSchedule::class.java)

        assertEquals("CFAlarm Auto-Off G11", schedule.name)
        assertEquals("PT00:30:00", schedule.localtime)
        assertEquals("enabled", schedule.status)
        assertEquals(true, schedule.autodelete)
    }

    @Test
    fun `fremder Zeitplan ohne unsere Felder bleibt lesbar`() {
        // Auf der Bridge liegen auch fremde Eintraege (real: "Hue dimmer switch 1"). Wir muessen
        // sie lesen koennen, um sie beim Aufraeumen zuverlaessig zu VERSCHONEN.
        val schedule = gson.fromJson("""{"name":"Hue dimmer switch 1"}""", BridgeSchedule::class.java)

        assertEquals("Hue dimmer switch 1", schedule.name)
        assertNull(schedule.localtime)
        assertNull(schedule.autodelete)
    }
}
