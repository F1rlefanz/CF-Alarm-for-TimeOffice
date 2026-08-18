package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.CalendarFetchOutcome
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Haelt fest, dass die Alarm-Pipeline NUR auf einer nachweislich vollstaendigen Eventliste
 * laufen darf - [CalendarViewModel.isEventListCompleteForAlarmSync].
 *
 * REALER BEFUND (Pruefrunde 14.08.2026, Dimension "Kalender-Datenfluss"): Der Vordergrund-
 * Ladevorgang laeuft im Normalfall LAZY und holt pro Kalender nur die ersten 10 Events; genau
 * diese abgeschnittene Liste ging unveraendert an `AlarmUseCase.syncAlarms()`. Dessen Delta-Sync
 * loescht jeden bestehenden Alarm, dessen eventId in der uebergebenen Liste fehlt - er kann
 * "Termin geloescht" nicht von "Termin lag hinter dem 10er-Praefix" unterscheiden. Bei mehr als
 * zehn Schichten in 14 Tagen (fuer einen Schichtplan der Normalfall) loeschte damit JEDES
 * App-Oeffnen die spaetesten Wecker - System-Alarm, Repository und Direct-Boot-Spiegel -, samt
 * "Schicht entfaellt"-Notification.
 *
 * Zweite, gleichartige Quelle der Unvollstaendigkeit: ein Kalender, der nicht geantwortet hat.
 * Ein Teilerfolg bleibt bewusst `Result.success` (siehe resolveCalendarAuthorizationOutcome) -
 * fuer die Anzeige richtig, als Loeschgrundlage toedlich.
 */
class CalendarViewModelAlarmSyncCompletenessTest {

    @Test
    fun `abgeschnittenes Praefix darf NICHT synchronisiert werden`() {
        // 10 von 13 Events geladen - die 3 spaetesten Schichten fehlen.
        assertFalse(
            "Ein Praefix ist keine Loeschgrundlage - sonst verschwinden die spaetesten Wecker " +
                "bei jedem App-Start",
            CalendarViewModel.isEventListCompleteForAlarmSync(
                loadedEventCount = 10,
                totalEventCount = 13,
                failedCalendars = 0,
                loadAll = false
            )
        )
    }

    @Test
    fun `vollstaendige Lazy-Liste darf synchronisiert werden`() {
        // Weniger Events als die Seitengroesse: das Praefix IST der Bestand.
        assertTrue(
            CalendarViewModel.isEventListCompleteForAlarmSync(
                loadedEventCount = 7,
                totalEventCount = 7,
                failedCalendars = 0,
                loadAll = false
            )
        )
    }

    @Test
    fun `voller Abruf gilt immer als vollstaendig`() {
        // Im loadAll-Zweig wird totalEventCount gar nicht mitgezaehlt (bleibt 0) - die geladene
        // Liste ist der Bestand. Ohne die explizite loadAll-Ausnahme wuerde hier trotzdem
        // korrekt true herauskommen; der Test haelt fest, dass der Zweig gemeint ist.
        assertTrue(
            CalendarViewModel.isEventListCompleteForAlarmSync(
                loadedEventCount = 42,
                totalEventCount = 0,
                failedCalendars = 0,
                loadAll = true
            )
        )
    }

    @Test
    fun `ein fehlgeschlagener Kalender verhindert den Sync auch bei vollem Abruf`() {
        // Der gefaehrlichste Fall: von zwei Kalendern antwortet der private, der
        // Dienstplan-Feed nicht. Die Liste ist dann "vollstaendig" im Sinne der Zaehlung,
        // aber es fehlen ALLE Schicht-Termine.
        assertFalse(
            "Ein nicht antwortender Kalender darf nie als 'diese Termine gibt es nicht mehr' " +
                "gelesen werden",
            CalendarViewModel.isEventListCompleteForAlarmSync(
                loadedEventCount = 2,
                totalEventCount = 2,
                failedCalendars = 1,
                loadAll = true
            )
        )
    }

    @Test
    fun `CalendarFetchOutcome ist genau dann vollstaendig, wenn kein Kalender gescheitert ist`() {
        assertTrue(
            CalendarFetchOutcome(emptyList(), requestedCalendars = 2).isComplete
        )
        assertFalse(
            "Teilerfolg ist bewusst Result.success - aber NICHT vollstaendig",
            CalendarFetchOutcome(emptyList(), requestedCalendars = 2, failedCalendarIds = setOf("kaputt")).isComplete
        )
    }
}
