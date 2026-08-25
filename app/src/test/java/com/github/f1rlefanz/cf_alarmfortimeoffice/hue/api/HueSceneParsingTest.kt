package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.api

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScene
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.waehleNutzbareSzenen
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Was `GET /api/<user>/scenes` liefert, und was davon in der Auswahl landet.
 *
 * ZWEI FEHLERKLASSEN STEHEN DAHINTER, beide schon einmal real gewesen:
 *
 *  1. **Die Fehlerhuelle.** Die V1-API antwortet auch bei ABLEHNUNG mit HTTP 200 - dann kommt
 *     ein JSON-ARRAY statt einer Map (`[{"error":{"type":1,...}}]`). Wer das einfach
 *     deserialisiert, bekommt "0 Szenen" und zeigt dem Nutzer eine leere Auswahl, ohne Hinweis
 *     auf die noetige Neukopplung. Genau dagegen steht der Huellen-Waechter in
 *     `HueApiClient.getScenes()`.
 *  2. **Das stille Wegfiltern.** Drei Gruende nehmen dem Nutzer Szenen aus der Liste. Passiert
 *     das unbemerkt, sucht er eine Szene, die es fuer ihn nie geben wird. Deshalb zaehlt
 *     [waehleNutzbareSzenen] die Gruende einzeln - und deshalb steht die Entscheidung in einer
 *     REINEN Funktion statt inline im Repository, wo sie nur am Geraet zu sehen war.
 *
 * Die Rohdaten hier sind gekuerzte, aber echte Antworten der Bridge des Nutzers (BSB002,
 * apiversion 1.78.0, gemessen 25.08.2026: 73 Szenen, davon 2 `recycle` und 6 LightScene).
 */
class HueSceneParsingTest {

    private val gson = Gson()

    private fun parse(body: String): Map<String, HueScene> {
        val type = object : TypeToken<Map<String, HueSceneDto>>() {}.type
        val roh: Map<String, HueSceneDto> = gson.fromJson(body, type) ?: emptyMap()
        return roh.mapValues { (id, dto) -> dto.toDomain(id) }
    }

    // --- Das Parsen ---------------------------------------------------------------------------

    @Test
    fun `die Szenen-Id kommt aus dem SCHLUESSEL, nicht aus dem Rumpf`() {
        // Der Grund fuer HueSceneDto: `HueScene.id` ist nicht-nullbar deklariert, aber im Rumpf
        // steht keine Id. Gson erzwingt Kotlins Non-Null NICHT - ohne den Umweg stuende dort
        // null, und zwar ohne Fehler.
        val szenen = parse(
            """{"XdNnkD3KHIMU7DC":{"name":"Hell","type":"GroupScene","group":"3",
               "lights":["6"],"recycle":false}}"""
        )

        val szene = szenen.getValue("XdNnkD3KHIMU7DC")
        assertEquals("XdNnkD3KHIMU7DC", szene.id)
        assertEquals("Hell", szene.name)
        assertEquals("3", szene.group)
        assertTrue(szene.isGroupScene)
    }

    @Test
    fun `eine Bridge ohne Szenen antwortet mit einem leeren Objekt - kein Fehler`() {
        assertTrue(parse("{}").isEmpty())
    }

    @Test
    fun `fehlende Felder werden null, nicht zum Absturz`() {
        val szene = parse("""{"abc":{}}""").getValue("abc")

        assertEquals("abc", szene.id)
        assertNull(szene.name)
        assertNull(szene.group)
        assertFalse("Ohne Gruppe ist es keine GroupScene", szene.isGroupScene)
    }

    // --- Die Fehlerhuelle ----------------------------------------------------------------------

    @Test
    fun `eine Fehlerhuelle wird VOR dem Parsen erkannt`() {
        val huelle = """[{"error":{"type":1,"address":"/scenes","description":"unauthorized user"}}]"""

        assertTrue(
            "Ein Array statt einer Map ist bei einem GET immer eine Ablehnung",
            HueV1Envelope.looksLikeEnvelope(huelle)
        )
        val urteil = HueV1Envelope.parseAll(huelle)
        assertTrue("Die Huelle MUSS als Fehlschlag durchgereicht werden", urteil.isFailure)
    }

    @Test
    fun `eine echte Szenen-Antwort ist KEINE Huelle`() {
        assertFalse(
            HueV1Envelope.looksLikeEnvelope("""{"XdNnkD3KHIMU7DC":{"name":"Hell"}}""")
        )
        assertFalse("Auch die leere Antwort nicht", HueV1Envelope.looksLikeEnvelope("{}"))
    }

    // --- Der Filter ----------------------------------------------------------------------------

    @Test
    fun `die drei Ausschlussgruende werden einzeln gezaehlt`() {
        val ergebnis = waehleNutzbareSzenen(
            listOf(
                HueScene(id = "a", name = "Nachtlicht", type = "GroupScene", group = "1"),
                HueScene(id = "b", name = null, type = "GroupScene", group = "1"),
                HueScene(id = "c", name = "  ", type = "GroupScene", group = "1"),
                HueScene(id = "d", name = "Auto", type = "GroupScene", group = "1", recycle = true),
                HueScene(id = "e", name = "Entspannen", type = "LightScene", group = null),
                HueScene(id = "f", name = "Lesen", type = "GroupScene", group = "82")
            )
        )

        assertEquals(listOf("a", "f"), ergebnis.nutzbar.map { it.id })
        assertEquals("leerer Name zaehlt wie kein Name", 2, ergebnis.ohneNamen)
        assertEquals(1, ergebnis.automatischVerwaltet)
        assertEquals(1, ergebnis.ohneRaum)
        assertEquals("Die Summe muss die Rohmenge ergeben", 6, ergebnis.gesamt)
    }

    @Test
    fun `nichts faellt weg, wenn alles in Ordnung ist`() {
        val ergebnis = waehleNutzbareSzenen(
            listOf(HueScene(id = "a", name = "Nachtlicht", type = "GroupScene", group = "1"))
        )

        assertEquals(1, ergebnis.nutzbar.size)
        assertEquals(0, ergebnis.ohneNamen + ergebnis.automatischVerwaltet + ergebnis.ohneRaum)
    }

    @Test
    fun `eine leere Rohliste bleibt leer und meldet keine Ausschluesse`() {
        val ergebnis = waehleNutzbareSzenen(emptyList())

        assertTrue(ergebnis.nutzbar.isEmpty())
        assertEquals(0, ergebnis.gesamt)
    }

    @Test
    fun `die gemessene Verteilung der echten Bridge geht sauber auf`() {
        // 73 Szenen, davon 2 recycle und 6 ohne Raum - am Geraet gezaehlt. Die App zeigte
        // daraufhin 66, nicht 65: EINE Szene erfuellt beide Gruende. Der Filter darf sie nur
        // EINMAL zaehlen, sonst laege die Summe ueber der Rohmenge und das Log loege.
        val roh = buildList {
            repeat(66) { add(HueScene(id = "ok$it", name = "S$it", group = "1")) }
            repeat(5) { add(HueScene(id = "ls$it", name = "L$it", group = null)) }
            add(HueScene(id = "beides", name = "B", group = null, recycle = true))
            add(HueScene(id = "rec", name = "R", group = "1", recycle = true))
        }

        val ergebnis = waehleNutzbareSzenen(roh)

        assertEquals(66, ergebnis.nutzbar.size)
        assertEquals(73, ergebnis.gesamt)
        assertEquals(
            "Der Doppelgrund zaehlt beim ERSTEN zutreffenden - hier recycle",
            2,
            ergebnis.automatischVerwaltet
        )
        assertEquals(5, ergebnis.ohneRaum)
    }
}
