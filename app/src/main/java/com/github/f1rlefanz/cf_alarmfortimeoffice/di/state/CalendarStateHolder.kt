// app/src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/di/state/CalendarStateHolder.kt

package com.github.f1rlefanz.cf_alarmfortimeoffice.di.state

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralized State Holder für die geladenen Kalender-Events.
 *
 * PURPOSE:
 * - Single Source of Truth für die Event-Liste, geteilt zwischen CalendarViewModel und
 *   ShiftViewModel (keine direkten VM-zu-VM-Abhängigkeiten).
 * - Thread-safe über StateFlow.
 *
 * HINWEIS (Audit): Die Kalender-Auswahl und -Verfügbarkeit lebt in [CalendarSelectionRepository]
 * (DataStore). Die frueher hier gespiegelten availableCalendars/selectedCalendarIds waren ein
 * toter Parallel-Zustand (Writer nie aufgerufen) und sind entfernt.
 */
@Singleton
class CalendarStateHolder @Inject constructor() {

    // Calendar Events (der einzige geteilte Zustand)
    private val _events = MutableStateFlow<List<CalendarEvent>>(emptyList())
    val events: StateFlow<List<CalendarEvent>> = _events.asStateFlow()

    /**
     * Ist [events] der VOLLSTAENDIGE Bestand - oder nur ein Ausschnitt?
     *
     * WARUM DAS HIERHER GEHOERT: Der Vordergrund-Ladevorgang laeuft lazy und legt pro Kalender nur
     * die ersten 10 Events ab; dazu kann ein Kalender ganz ausgefallen sein (Teilerfolg, siehe
     * `ICalendarUseCase.getCalendarEventsWithStatus`). Fuer die ANZEIGE ist beides in Ordnung.
     *
     * Fuer jeden Leser, der daraus auf "Termin geloescht" schliesst, ist es toedlich:
     * `ShiftViewModel.triggerAlarmCreationFromConfigUpdate()` gibt diese Liste an
     * `AlarmUseCase.syncAlarms()`, und dessen Delta-Sync entfernt jeden Alarm, dessen eventId
     * darin fehlt. Ein Ausschnitt hiess damit "die spaetesten Schichten wurden abgesagt" - bei
     * einem Dienstplan mit mehr als zehn Terminen in 14 Tagen loeschte jede Aenderung an der
     * Schicht-Konfiguration die letzten Wecker.
     *
     * Das Flag steht bewusst HIER und nicht als Pruefung im einzelnen Leser: der Holder ist
     * geteilter Zustand, und ein kuenftiger dritter Leser erbt die Falle sonst erneut - dieselbe
     * Ueberlegung wie beim zentralen Master-Pause-Backstop in `syncAlarms()`.
     */
    private val _eventsComplete = MutableStateFlow(false)
    val eventsComplete: StateFlow<Boolean> = _eventsComplete.asStateFlow()

    /**
     * @param complete nur `true`, wenn [events] nachweislich der vollstaendige Bestand ist (kein
     *   Lazy-Praefix, kein ausgefallener Kalender). Im Zweifel `false` - daraus folgt hoechstens
     *   ein spaeter nachgeholter Sync, aus einem falschen `true` ein geloeschter Wecker.
     */
    fun updateEvents(events: List<CalendarEvent>, complete: Boolean) {
        _events.value = events
        _eventsComplete.value = complete
    }

    fun clearEvents() {
        _events.value = emptyList()
        // Leer UND "vollstaendig" waere die gefaehrlichste Kombination: syncAlarms() liest eine
        // leere Liste als "keine Schichten" und raeumt.
        _eventsComplete.value = false
    }
}
