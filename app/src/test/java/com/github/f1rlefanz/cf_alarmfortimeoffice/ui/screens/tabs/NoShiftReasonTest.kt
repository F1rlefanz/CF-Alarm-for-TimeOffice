package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Deckt HomeTabContents pure Erklaerfunktionen ab.
 *
 * Vorher stand im else-Zweig der "Naechste Schicht"-Karte EIN konstanter Satz ("Keine Schicht
 * erkannt") fuer sechs voellig verschiedene Ursachen - ein Nutzer (gemeldet: ein Kollege auf einer
 * anderen Station, dessen Erkennungsmuster nicht passten) konnte daraus nicht ablesen, ob der
 * Kalender, die Anmeldung oder seine Stichwoerter das Problem sind. Diese Tests halten die
 * Rangfolge der Ursachen und die Nennung der echten Termintitel fest.
 */
class NoShiftReasonTest {

    private fun reason(
        hasSelectedCalendars: Boolean = true,
        calendarAuthorizationValid: Boolean = true,
        kalenderNichtErreichbar: Boolean = false,
        unavailableCalendarCount: Int = 0,
        errorMessage: String? = null,
        eventCount: Int = 5,
        shiftConfigLoaded: Boolean = true,
        enabledShiftTypeCount: Int = 5,
        recognizedShiftCount: Int = 2,
        krankTerminCount: Int = 0
    ) = noShiftReason(
        hasSelectedCalendars = hasSelectedCalendars,
        calendarAuthorizationValid = calendarAuthorizationValid,
        kalenderNichtErreichbar = kalenderNichtErreichbar,
        unavailableCalendarCount = unavailableCalendarCount,
        errorMessage = errorMessage,
        eventCount = eventCount,
        shiftConfigLoaded = shiftConfigLoaded,
        enabledShiftTypeCount = enabledShiftTypeCount,
        recognizedShiftCount = recognizedShiftCount,
        krankTerminCount = krankTerminCount
    )

    @Test
    fun `nicht abrufbarer Kalender schlaegt Fehlermeldung und leere Terminliste`() {
        // Die Vollstaendigkeits-Sperren halten in dieser Lage jeden Alarm-Sync an. "Keine Termine"
        // waere dann eine FOLGE davon und wuerde den Nutzer im Kalender suchen lassen, statt bei
        // dem einen Kalender, der nicht antwortet.
        assertEquals(
            NoShiftReason.CALENDAR_UNAVAILABLE,
            reason(unavailableCalendarCount = 1, errorMessage = "irgendwas", eventCount = 0)
        )
    }

    @Test
    fun `verlorene Autorisierung schlaegt den nicht abrufbaren Kalender`() {
        // Der Totalausfall ALLER Kalender aus Anmeldegruenden laeuft ueber
        // calendarAuthorizationValid und hat seine eigene, handlungsfaehige Meldung ("neu
        // anmelden"); fehlen dagegen alle nur, stehen sie als nicht abrufbar da. Ein
        // Anmeldeproblem darf nicht als Teilerfolg erscheinen - sonst bekaeme der Nutzer den
        // Rat, einen Kalender abzuwaehlen, obwohl in Wahrheit die Anmeldung haengt.
        assertEquals(
            NoShiftReason.AUTHORIZATION_LOST,
            reason(calendarAuthorizationValid = false, unavailableCalendarCount = 2)
        )
    }

    @Test
    fun `ohne nicht abrufbaren Kalender bleiben die uebrigen Gruende unveraendert`() {
        assertEquals(
            NoShiftReason.NO_EVENTS,
            reason(unavailableCalendarCount = 0, eventCount = 0)
        )
    }

    @Test
    fun `kein Kalender gewaehlt schlaegt jede andere Ursache`() {
        // Auch wenn gleichzeitig Autorisierung fehlt und ein Fehler anliegt: ohne Kalender ist
        // alles andere Folge, nicht Ursache.
        assertEquals(
            NoShiftReason.NO_CALENDAR_SELECTED,
            reason(
                hasSelectedCalendars = false,
                calendarAuthorizationValid = false,
                errorMessage = "irgendwas",
                eventCount = 0
            )
        )
    }

    @Test
    fun `verlorene Autorisierung vor Fehlermeldung und leerer Terminliste`() {
        assertEquals(
            NoShiftReason.AUTHORIZATION_LOST,
            reason(calendarAuthorizationValid = false, errorMessage = "401", eventCount = 0)
        )
    }

    @Test
    fun `Fehlermeldung wird genannt statt verschluckt`() {
        assertEquals(NoShiftReason.LOAD_ERROR, reason(errorMessage = "Netzwerkfehler"))

        val text = noShiftExplanation(NoShiftReason.LOAD_ERROR, errorMessage = "Netzwerkfehler")
        assertTrue("Der konkrete Fehler muss im Text auftauchen: $text", text.contains("Netzwerkfehler"))
    }

    @Test
    fun `leere Fehlermeldung gilt nicht als Fehler`() {
        // calendarState.error kann ein leerer String sein - das darf keine leere Fehlerzeile
        // erzeugen, sondern muss zur naechsten Ursache durchfallen.
        assertEquals(NoShiftReason.NO_EVENTS, reason(errorMessage = "   ", eventCount = 0))
    }

    @Test
    fun `keine Termine`() {
        assertEquals(NoShiftReason.NO_EVENTS, reason(eventCount = 0))
    }

    @Test
    fun `Schichtkonfiguration noch nicht geladen ist kein Keine-Schichttypen`() {
        assertEquals(
            NoShiftReason.SHIFT_CONFIG_NOT_LOADED,
            reason(shiftConfigLoaded = false, enabledShiftTypeCount = 0)
        )
    }

    @Test
    fun `keine aktiven Schichttypen`() {
        assertEquals(
            NoShiftReason.NO_SHIFT_TYPES,
            reason(enabledShiftTypeCount = 0, recognizedShiftCount = 0)
        )
    }

    @Test
    fun `Termine da aber kein Muster passt nennt die echten Termintitel`() {
        assertEquals(
            NoShiftReason.NO_PATTERN_MATCH,
            reason(eventCount = 3, recognizedShiftCount = 0)
        )

        val text = noShiftExplanation(
            NoShiftReason.NO_PATTERN_MATCH,
            sampleEventTitles = listOf("Dienst A1", "Dienst B2")
        )
        assertTrue("Titel 1 fehlt: $text", text.contains("Dienst A1"))
        assertTrue("Titel 2 fehlt: $text", text.contains("Dienst B2"))
    }

    @Test
    fun `ohne Termintitel bleibt der Satz lesbar`() {
        val text = noShiftExplanation(NoShiftReason.NO_PATTERN_MATCH, sampleEventTitles = emptyList())
        assertTrue("Kein leeres Klammerpaar erwartet: $text", !text.contains("()"))
        assertTrue(text.isNotBlank())
    }

    @Test
    fun `beide Schichttypen-Saetze nennen die Einstellungen als Ort - nicht mehr den Wecker-Bereich`() {
        // Seit #139 steht "Schichttypen verwalten" in den Einstellungen. Ein Satz, der noch auf
        // den Wecker-Bereich zeigt, schickt den Nutzer an eine Stelle, an der der Knopf fehlt.
        val texte = listOf(
            noShiftExplanation(NoShiftReason.NO_SHIFT_TYPES),
            noShiftExplanation(NoShiftReason.NO_PATTERN_MATCH, sampleEventTitles = listOf("Dienst A1"))
        )
        for (text in texte) {
            assertTrue("Ort fehlt: $text", text.contains(SCHICHTTYPEN_ORT))
            assertTrue("Knopfname fehlt: $text", text.contains("„Schichttypen verwalten“"))
            assertTrue("Einstellungen fehlen: $text", text.contains("Einstellungen"))
            assertFalse("Alter Ort steht noch drin: $text", text.contains("„Wecker“"))
        }
    }

    @Test
    fun `auch die String-Ressourcen schicken nicht mehr in den Wecker-Bereich`() {
        // Der Leerzustand der Hue-Regel (hue_shift_none_body) war beim Umzug uebersehen worden.
        val datei = listOf(File("app/src/main/res/values/strings.xml"), File("src/main/res/values/strings.xml"))
            .firstOrNull { it.isFile } ?: error("strings.xml nicht gefunden")
        datei.readLines().filter { it.contains("Schichttypen verwalten") }.forEach { zeile ->
            assertTrue("Einstellungen fehlen: $zeile", zeile.contains("Einstellungen"))
            assertFalse("Alter Ort steht noch drin: $zeile", zeile.contains("„Wecker“"))
        }
    }

    @Test
    fun `erkannte Schichten aber keine kuenftige heisst Vergangenheit`() {
        // upcomingShift filtert auf startTime.isAfter(now) - recognizedShifts kann also nicht-leer
        // sein, obwohl oben nichts angezeigt wird. Das ist der letzte Fall, kein "unbekannt".
        assertEquals(NoShiftReason.ONLY_PAST_SHIFTS, reason(recognizedShiftCount = 4))
        assertTrue(
            noShiftExplanation(NoShiftReason.ONLY_PAST_SHIFTS).contains("Vergangenheit")
        )
    }

    @Test
    fun `Automatik-Hinweis ist ein Zusatz und ersetzt den Grund nicht`() {
        val withAutomatic = noShiftExplanation(NoShiftReason.NO_EVENTS, autoAlarmEnabled = true)
        val withoutAutomatic = noShiftExplanation(NoShiftReason.NO_EVENTS, autoAlarmEnabled = false)

        assertTrue(
            "Der eigentliche Grund muss auch bei ausgeschalteter Automatik erhalten bleiben",
            withoutAutomatic.startsWith(withAutomatic)
        )
        assertTrue(withoutAutomatic.contains("Automatische Alarme"))
        assertTrue(
            "Bei eingeschalteter Automatik darf kein Hinweis erscheinen",
            !withAutomatic.contains("Automatische Alarme")
        )
    }

    @Test
    fun `nicht erreichbarer Kalender schlaegt Fehlermeldung und leere Terminliste`() {
        // 30.09.2026, Fairphone im Flugmodus: nach dem Abgleich ist die Terminliste leer. Sobald
        // die Snackbar den Fehler geraeumt hat, hiess es sonst "im gewaehlten Kalender steht
        // nichts" - eine Behauptung ueber den Dienstplan, die niemand geprueft hat.
        assertEquals(
            NoShiftReason.KALENDER_NICHT_ERREICHBAR,
            reason(kalenderNichtErreichbar = true, errorMessage = null, eventCount = 0)
        )
        assertEquals(
            NoShiftReason.KALENDER_NICHT_ERREICHBAR,
            reason(kalenderNichtErreichbar = true, errorMessage = "Keine Internetverbindung", eventCount = 0)
        )
    }

    @Test
    fun `ohne Kalenderauswahl ist auch die Erreichbarkeit belanglos`() {
        assertEquals(
            NoShiftReason.NO_CALENDAR_SELECTED,
            reason(hasSelectedCalendars = false, kalenderNichtErreichbar = true)
        )
    }

    @Test
    fun `nicht erreichbar nennt Karte und Knopf wortgleich und behauptet keinen Zugriffsverlust`() {
        val text = noShiftExplanation(NoShiftReason.KALENDER_NICHT_ERREICHBAR)
        assertTrue("Kartenbeschriftung fehlt: $text", text.contains("\"Kalender-Events\""))
        assertTrue("Knopfbeschriftung fehlt: $text", text.contains("\"Mit Google Kalender abgleichen\""))
        assertTrue("Die Folge fuer die Wecker muss drinstehen: $text", text.contains("Wecker"))
        assertFalse("Keine Positionsangabe: $text", text.contains("darunter"))
        assertFalse(
            "Das ist genau die Fehldiagnose, die behoben wird: $text",
            text.contains("abgelaufen") || text.contains("Autorisierung") || text.contains("erneuern")
        )
    }

    @Test
    fun `Kalender nicht gefunden nennt Ursache, Folge und den Ort des Auswegs`() {
        val text = KALENDER_NICHT_GEFUNDEN_TEXT
        assertTrue("Ursache fehlt: $text", text.contains("gelöscht") && text.contains("freigegeben"))
        assertTrue("Folge fuer die Wecker fehlt: $text", text.contains("Wecker"))
        assertTrue("Ort des Auswegs fehlt: $text", text.contains("System-Status unter \"Kalender\""))
        assertFalse(
            "Genau diese Fehldiagnose wird behoben: $text",
            text.contains("Autorisierung") || text.contains("erneuern")
        )
    }

    /**
     * Seit v1.43.6 koennen ALLE ausgewaehlten Kalender fehlen, und schon vorher konnten mehrere
     * nicht abrufbar sein. Beide Texte stammen aus der Zeit, als nur EINER fehlen konnte - ein Satz
     * ueber "den" oder "einen" Kalender stimmte bei zweien nicht.
     */
    @Test
    fun `nicht abrufbar und nicht gefunden stimmen auch fuer mehrere Kalender`() {
        assertTrue(
            noShiftExplanation(NoShiftReason.CALENDAR_UNAVAILABLE)
                .startsWith("Mindestens ein ausgewählter Kalender ist nicht abrufbar")
        )
        assertTrue(KALENDER_NICHT_GEFUNDEN_TEXT.startsWith("Google findet keinen ausgewählten Kalender mehr"))
    }

    @Test
    fun `jeder Grund hat einen eigenen nicht leeren Text`() {
        val texts = NoShiftReason.entries.map { noShiftExplanation(it, errorMessage = "X") }
        texts.forEach { assertTrue("Leerer Erklaertext", it.isNotBlank()) }
        assertEquals("Zwei Gruende duerfen nicht denselben Text zeigen", texts.size, texts.distinct().size)
    }

    // --- #142: nur Krankmeldungen im Kalender ---

    @Test
    fun `Stehen nur Krankmeldungen im Kalender, heisst es nicht kein Muster passt`() {
        assertEquals(
            NoShiftReason.NUR_KRANKMELDUNG,
            reason(eventCount = 3, recognizedShiftCount = 0, krankTerminCount = 3)
        )
        val text = noShiftExplanation(NoShiftReason.NUR_KRANKMELDUNG, vorausschauTage = 14)
        assertTrue(text.contains("nur Krankmeldungen"))
        assertFalse("Kein Rat, \"K\" als Schicht-Muster einzutragen: $text", text.contains("Erkennungsmuster"))
    }

    @Test
    fun `Steht neben Krankmeldungen ein unerkannter Termin, bleibt die Muster-Frage`() {
        assertEquals(
            NoShiftReason.NO_PATTERN_MATCH,
            reason(eventCount = 4, recognizedShiftCount = 0, krankTerminCount = 3)
        )
    }

    @Test
    fun `Krankmeldung wird wie in der Engine erkannt - nur aktive Krank-Typen`() {
        val krank = com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition(
            id = "sick",
            name = "Krank",
            keywords = listOf("K"),
            alarmTime = java.time.LocalTime.MIDNIGHT,
            isSickDay = true
        )
        assertTrue(istKrankTermin("K", listOf(krank)))
        assertFalse(istKrankTermin("F", listOf(krank)))
        assertFalse(istKrankTermin("K", listOf(krank.copy(isEnabled = false))))
        assertFalse(istKrankTermin("K", listOf(krank.copy(isSickDay = false))))
    }
}
