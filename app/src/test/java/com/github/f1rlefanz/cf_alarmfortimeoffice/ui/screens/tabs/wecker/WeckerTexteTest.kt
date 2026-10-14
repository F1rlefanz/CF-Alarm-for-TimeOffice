package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs.wecker

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.AlarmPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ProbeFehler
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.ProbeZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.SchlummerEingabe
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.LetzterSchichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.AlarmSkipUiState
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DienstzeitDnd
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DimmStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.DndStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.Kaertchen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.LichtStatus
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SchichtFolgen
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.TagesSchicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.SkipLage
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.WeckerEintrag
import com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.baueWeckerTage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

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

    private val berlin: ZoneId = ZoneId.of("Europe/Berlin")

    private fun ms(text: String): Long = LocalDateTime.parse(text).atZone(berlin).toInstant().toEpochMilli()

    @Test
    fun `Schichten mit Dienstzeit, ohne Manuell-Zusatz, unbekannte Zeit ohne Uhrzeit`() {
        val text = schichtenText(
            listOf(
                TagesSchicht("F (Manuell)", ms("2026-10-14T06:00"), ms("2026-10-14T14:12")),
                TagesSchicht("AD1", 0L, 0L)
            ),
            berlin
        )
        assertEquals("F · 06:00–14:12, AD1", text.replace('\u00A0', ' '))
    }

    @Test
    fun `ganztaegiger Dienst heisst ganztaegig, nicht 00-00 bis 00-00`() {
        val text = schichtenText(
            listOf(TagesSchicht("Abrufdienst", ms("2026-10-17T00:00"), ms("2026-10-18T00:00"))),
            berlin
        ).replace(' ', ' ')
        assertEquals("Abrufdienst · ganztägig", text)
    }

    @Test
    fun `Spannen nur ohne Pause und mit Automatik`() {
        val spanne = ShiftSpan("F", ms("2026-10-14T06:00"), ms("2026-10-14T14:00"), ms("2026-10-14T05:00"))
        val stand = LetzterSchichtStand(listOf(spanne), stand = 1L)
        assertEquals(listOf(spanne), spannenFuerTagesliste(null, stand))
        assertTrue(spannenFuerTagesliste(WeckerZustand.MASTER_PAUSE, stand).isEmpty())
        assertTrue(spannenFuerTagesliste(WeckerZustand.AUTOMATIK_AUS, stand).isEmpty())
        // Nicht lesbar / noch nicht gelesen: die Liste faellt auf die Wecker allein zurueck.
        assertTrue(spannenFuerTagesliste(null, null).isEmpty())
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
        assertEquals("Kein Ton", weckerArt(wecker(still = true), mehrereSchichten = false))
        assertTrue(weckerArt(wecker(entfaellt = true), mehrereSchichten = false).startsWith("Entfällt"))
        // Die Herkunft steht als Etikett daneben, nicht doppelt im Text.
        assertEquals("Weckt", weckerArt(wecker(manuell = true), mehrereSchichten = false))
        assertEquals("Weckt · S2", weckerArt(wecker(manuell = true, schicht = "S2 (Manuell)"), mehrereSchichten = true))
    }

    @Test
    fun `die Etiketten einer Weckerzeile tragen die Farben aus dem Mockup`() {
        assertEquals(emptyList<EtikettInhalt>(), weckerEtiketten(wecker()))
        assertEquals(listOf(EtikettInhalt("still", EtikettTon.GEDAEMPFT)), weckerEtiketten(wecker(still = true)))
        assertEquals(listOf(EtikettInhalt("von dir gestellt", EtikettTon.INFO)), weckerEtiketten(wecker(manuell = true)))
        assertEquals(
            listOf(EtikettInhalt("von dir gestellt", EtikettTon.INFO), EtikettInhalt("nächster", EtikettTon.ERFOLG)),
            weckerEtiketten(wecker(manuell = true).copy(naechster = true))
        )
        assertEquals(
            listOf(EtikettInhalt("weckt diesmal nicht", EtikettTon.WARNUNG)),
            weckerEtiketten(WeckerEintrag.Uebersprungen(1L, LocalTime.NOON))
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
        // Der Knopf unter der Liste nennt dieselbe Geste wie am Tag ...
        assertTrue(ANDERER_TAG_KNOPF.contains("Dienst fällt aus"))
        // ... und rahmt sie nicht als Ausweg fuer „spaetere“/„andere“ Dienste (Eigentuemer 14.10.2026).
        listOf(NICHT_WECKEN_NUR_NAECHSTER, NUR_EIN_WECKER_AUSLASSEN, ANDERER_TAG_KNOPF).forEach { text ->
            assertFalse(text, text.contains("späterer Dienst"))
            assertFalse(text, text.contains("anderen Tag"))
        }
        assertTrue(NICHT_WECKEN_NUR_NAECHSTER.contains("Krank oder frei?"))
        assertTrue(NUR_EIN_WECKER_AUSLASSEN.contains("Krank oder frei?"))
        // Die Erklaerung am Knopf nennt den Anlass zuerst - fuer jeden Tag, nicht nur spaetere.
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.startsWith("Krank, frei oder getauscht?"))
        assertTrue(NUR_EIN_WECKER_AUSLASSEN.contains("„Doch wecken“"))
        assertTrue(NUR_EIN_WECKER_AUSLASSEN.contains("„Dienst fällt aus“"))
    }

    @Test
    fun `beide Knoepfe sagen, dass das Weck-Licht mit dem Wecker entfaellt`() {
        // Die Tagesliste zeigt nach „Nicht wecken“ kein Kaertchen „Licht“ - der Text darf nicht
        // „lässt nur ihn aus“ behaupten (Eigentuemer 14.10.2026: was haengt am Wecker?).
        assertTrue(NICHT_WECKEN_NUR_NAECHSTER.contains("Weck-Licht bleibt aus"))
        assertFalse(NICHT_WECKEN_NUR_NAECHSTER.contains("nur ihn"))
        assertTrue(DIENST_FAELLT_AUS_ERKLAERUNG.contains("kein Weck-Licht"))
    }

    private val jetzt = 1_000_000_000_000L
    private val tag = 24 * 60 * 60 * 1000L

    private fun liste(vararg alarme: AlarmInfo, skip: SkipLage = SkipLage.KEIN) =
        baueWeckerTage(alarme.toList(), emptyList(), emptyList(), skip, emptyList(), jetzt, ZoneId.of("UTC"), emptyMap())

    private fun wecker(id: Int, abstand: Long, still: Boolean = false) = AlarmInfo(
        id = id, shiftId = "s$id", shiftName = "Früh", triggerTime = jetzt + abstand,
        formattedTime = "", isSilent = still
    )

    @Test
    fun `der Satz unter der Liste verspricht nur einen Knopf, den es gibt`() {
        // Normalfall: der naechste Wecker hat „Nicht wecken“.
        assertEquals(NICHT_WECKEN_NUR_NAECHSTER, nichtWeckenHinweis(liste(wecker(1, tag))))
        // Skip aktiv: der folgende Wecker heisst „nächster“, hat aber keinen Knopf.
        assertEquals(
            NUR_EIN_WECKER_AUSLASSEN,
            nichtWeckenHinweis(
                liste(wecker(2, 2 * tag), skip = SkipLage(aktiv = true, alarmId = 1, weckzeitpunkt = jetzt + tag))
            )
        )
        // Nur stille Eintraege: nirgends ein Knopf, also kein Satz.
        assertNull(nichtWeckenHinweis(liste(wecker(3, tag, still = true))))
        assertNull(nichtWeckenHinweis(emptyList()))
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
        assertTrue(PROBE_BESCHREIBUNG.contains("sobald du die App verlässt"))
    }
}
