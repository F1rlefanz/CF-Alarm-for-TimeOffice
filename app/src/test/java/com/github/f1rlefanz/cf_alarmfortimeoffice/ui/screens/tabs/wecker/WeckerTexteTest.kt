package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ProbeFehler
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ProbeZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.SchlummerEingabe
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DienstzeitDnd
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DndStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.Kaertchen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.LichtStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichtFolgen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.TagesSchicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.WeckerEintrag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** Texte und reine Entscheidungen des Wecker-Tabs (#139). */
class WeckerTexteTest {

    // --- Zustandshinweis --------------------------------------------------------------------

    @Test
    fun `die Master-Pause hat Vorrang vor Automatik aus - es steht hoechstens ein Hinweis`() {
        assertEquals(WeckerZustand.MASTER_PAUSE, weckerZustand(masterPausePaused = true, autoAlarmEnabled = false))
        assertEquals(WeckerZustand.MASTER_PAUSE, weckerZustand(masterPausePaused = true, autoAlarmEnabled = true))
        assertEquals(WeckerZustand.AUTOMATIK_AUS, weckerZustand(masterPausePaused = false, autoAlarmEnabled = false))
        assertNull(weckerZustand(masterPausePaused = false, autoAlarmEnabled = true))
    }

    @Test
    fun `ungeladene Konfiguration behauptet kein Automatik aus`() {
        assertNull(weckerZustand(masterPausePaused = false, autoAlarmEnabled = null))
    }

    @Test
    fun `jeder Zustandshinweis hat eine Handlung, die die Richtung nennt`() {
        assertEquals("Pause beenden", zustandsHinweisText(WeckerZustand.MASTER_PAUSE).handlung)
        assertEquals("Einschalten", zustandsHinweisText(WeckerZustand.AUTOMATIK_AUS).handlung)
    }

    @Test
    fun `der Leerzustand sagt warum und nennt die echte Vorausschau`() {
        assertTrue(leerzustandText(WeckerZustand.MASTER_PAUSE, 14).contains("Pause"))
        assertTrue(leerzustandText(WeckerZustand.AUTOMATIK_AUS, 14).contains("Automatik"))
        val normal = leerzustandText(null, 21)
        assertTrue(normal, normal.contains("21 Tagen"))
        assertFalse("Keine feste 14 im Text", normal.contains("14"))
    }

    // --- Tagesliste -------------------------------------------------------------------------

    @Test
    fun `die Skip-Lage traegt den Weckzeitpunkt aus dem ViewModel-Zustand`() {
        val lage = AlarmSkipUiState(isNextAlarmSkipped = true, skippedAlarmId = 7, skippedAlarmTriggerTime = 123L)
            .alsSkipLage()
        assertTrue(lage.aktiv)
        assertEquals(7, lage.alarmId)
        assertEquals(123L, lage.weckzeitpunkt)
    }

    private fun definition(id: String, name: String, aktiv: Boolean = true) = ShiftDefinition(
        id = id,
        name = name,
        keywords = emptyList(),
        alarmTime = LocalTime.of(5, 0),
        isEnabled = aktiv
    )

    private val folgen = SchichtFolgen(
        dimmen = DimmStatus.Keine,
        licht = LichtStatus.Regeln(0, 0),
        dnd = DndStatus(DienstzeitDnd.AN, nachDimmer = false, rufbereitschaft = false)
    )

    @Test
    fun `Wirkungen werden vom Definitions-Schluessel auf den Schichtnamen umgeschluesselt`() {
        val wirkungen = weckerWirkungen(
            definitionen = listOf(definition("id-f", "F"), definition("id-s", "S2", aktiv = false)),
            folgenJeId = mapOf("id-f" to folgen, "id-s" to folgen)
        )
        assertEquals(setOf("F"), wirkungen.keys)
        assertEquals("id-f", wirkungen.getValue("F").definition.id)
    }

    @Test
    fun `eine Definition ohne Statuszeile bekommt keine Wirkung`() {
        assertTrue(weckerWirkungen(listOf(definition("id-f", "F")), emptyMap()).isEmpty())
    }

    @Test
    fun `die Tagesueberschrift hat Wochentag ohne Punkt und Tag Monat`() {
        assertEquals("Mi 14.10.", tagUeberschrift(LocalDate.of(2026, 10, 14)))
        assertEquals("So 01.11.", tagUeberschrift(LocalDate.of(2026, 11, 1)))
    }

    @Test
    fun `heute und morgen werden benannt, sonst nichts`() {
        val heute = LocalDate.of(2026, 10, 14)
        assertEquals("heute", relativerTag(heute, heute))
        assertEquals("morgen", relativerTag(heute.plusDays(1), heute))
        assertNull(relativerTag(heute.plusDays(2), heute))
    }

    @Test
    fun `Schichten mit Dienstzeit, ohne Manuell-Zusatz, unbekannte Zeit ohne Uhrzeit`() {
        val text = schichtenText(
            listOf(
                TagesSchicht("F (Manuell)", LocalTime.of(6, 0), LocalTime.of(14, 12)),
                TagesSchicht("AD1", null, null)
            )
        )
        assertEquals("F · 06:00–14:12, AD1", text)
    }

    private fun wecker(
        still: Boolean = false,
        manuell: Boolean = false,
        entfaellt: Boolean = false,
        schicht: String = "F"
    ) = WeckerEintrag.Wecker(
        alarm = AlarmInfo(id = 1, shiftId = "s", shiftName = schicht, triggerTime = 1L, formattedTime = ""),
        uhrzeit = LocalTime.of(5, 30),
        still = still,
        manuell = manuell,
        naechster = false,
        nichtWeckenMoeglich = false,
        entfaelltDurchFreigabe = entfaellt
    )

    @Test
    fun `die Art eines Weckers - nie Weckt fuer einen, der nicht klingelt`() {
        assertEquals("Weckt", weckerArt(wecker(), mehrereSchichten = false))
        assertEquals("Still: kein Ton", weckerArt(wecker(still = true), mehrereSchichten = false))
        assertTrue(weckerArt(wecker(entfaellt = true), mehrereSchichten = false).startsWith("Entfällt"))
        assertEquals("Weckt · von dir gestellt", weckerArt(wecker(manuell = true), mehrereSchichten = false))
        assertEquals(
            "Weckt · von dir gestellt · S2",
            weckerArt(wecker(manuell = true, schicht = "S2 (Manuell)"), mehrereSchichten = true)
        )
    }

    @Test
    fun `die Beschreibung im Bottom-Sheet nennt Herkunft und den Rueckweg`() {
        assertEquals("Weckt. Aus dem Dienstplan.", weckerBeschreibung(wecker()))
        assertEquals("Weckt. Von dir gestellt.", weckerBeschreibung(wecker(manuell = true)))
        assertTrue(weckerBeschreibung(WeckerEintrag.Uebersprungen(1L, LocalTime.NOON)).contains("„Doch wecken“"))
    }

    @Test
    fun `das Rufbereitschafts-Kaertchen nennt die Wirkung, nicht den Dienst`() {
        assertEquals("stündliche Kalender-Abfrage", kaertchenText(Kaertchen.RUFBEREITSCHAFT))
        assertEquals("Nicht stören", kaertchenText(Kaertchen.NICHT_STOEREN))
    }

    @Test
    fun `der Satz unter der Liste nennt beide Knoepfe mit ihren neuen Namen`() {
        assertTrue(NICHT_WECKEN_NUR_NAECHSTER.contains("„Nicht wecken“"))
        assertTrue(NICHT_WECKEN_NUR_NAECHSTER.contains("„Dienst fällt aus“"))
        assertTrue(ANDERER_TAG_KNOPF.startsWith("Dienst fällt"))
    }

    // --- Klang ------------------------------------------------------------------------------

    @Test
    fun `der Schlummer-Bereich kommt aus den Grenzen in AlarmPrefs`() {
        assertEquals(
            "${AlarmPrefs.MIN_SNOOZE_MINUTES} bis ${AlarmPrefs.MAX_SNOOZE_MINUTES} Minuten",
            SCHLUMMER_BEREICH
        )
    }

    @Test
    fun `jeder Schlummer-Hinweis nennt den Wert, der jetzt gilt`() {
        SchlummerEingabe.Hinweis.entries.forEach { hinweis ->
            val text = schlummerHinweisText(hinweis, 17)
            assertTrue(text, text.contains("17 Min"))
        }
        assertTrue(schlummerHinweisText(SchlummerEingabe.Hinweis.ZU_GROSS, 30).contains("${AlarmPrefs.MAX_SNOOZE_MINUTES}"))
    }

    @Test
    fun `jeder Probe-Fehler hat einen eigenen Text`() {
        val texte = ProbeFehler.entries.map { probeFehlerText(it) }
        texte.forEach { assertTrue(it.isNotBlank()) }
        assertEquals(texte.size, texte.toSet().size)
    }

    @Test
    fun `ein verweigerter Fokus beruhigt - der Wecker klingelt trotzdem`() {
        assertTrue(probeFehlerText(ProbeFehler.FOKUS_VERWEIGERT).contains("Wecker klingelt trotzdem"))
    }

    @Test
    fun `die laufende Probe zeigt Zeit und Pegel`() {
        val text = probeLaeuftText(ProbeZustand.Laeuft(gesamtMs = 35_000L, vergangenMs = 12_400L, pegel = 0.58f))
        assertEquals("Läuft · 0:12 von 0:35 · 58 %", text)
    }

    @Test
    fun `die Probe-Beschreibung verspricht den Stopp beim Verlassen`() {
        assertTrue(PROBE_BESCHREIBUNG.contains("wenn du die App verlässt"))
    }
}
