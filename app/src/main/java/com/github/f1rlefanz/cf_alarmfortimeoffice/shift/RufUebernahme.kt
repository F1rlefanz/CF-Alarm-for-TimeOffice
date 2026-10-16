package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import java.time.LocalDate

/**
 * Umbau E3, Sonderfall Rufbereitschaft (#172): steht am selben Tag wie eine Rufbereitschaft ein
 * Dienst im Kalender (ein nachgetragener Einsatz), gilt fuer den Tag der Wecker des Dienstes. Diese
 * Funktion nennt die Ruf-Termine, deren WECKER deshalb entfaellt - als Event-IDs der Treffer.
 *
 * - **Derselbe Tag** ist das Kalenderdatum des Termin-BEGINNS ([ShiftMatch.calendarEvent]),
 *   NICHT das der Weckzeit: beim Nachtdienst fallen beide auseinander (Weckzeit am Vortag des
 *   Beginns). Ein Nachtdienst zaehlt zu dem Tag, an dem er beginnt, ein ganztaegiger Ruftermin zu
 *   seinem Datum. `CalendarEvent.startTime` ist schon Ortszeit der Systemzone (so liefert sie das
 *   Repository, ganztaegig als 00:00 des Datums), deshalb rechnet hier keine Zone um.
 * - **Uebernehmen kann nur die Kategorie Dienst.** Frei, Urlaub, Fortbildung und eine zweite
 *   Rufbereitschaft uebernehmen nicht. Gefragt wird [com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition.kategorie],
 *   nicht die Flags.
 * - Ob der Dienst selbst einen Wecker stellt (`weckerAn`, verstrichene Weckzeit), spielt keine
 *   Rolle: der Tag folgt dem Dienst-Typ, so wie der Nutzer ihn eingestellt hat.
 *
 * Nur der Wecker entfaellt. Die Schichtspanne der Rufbereitschaft bleibt - aus ihr lesen die
 * stuendliche Ruf-Abfrage und das DND-Ende. Faellt der Einsatz wieder weg, liefert der naechste
 * Lauf den Ruf-Termin nicht mehr, und sein Wecker kommt zurueck.
 */
fun uebernommeneRufTermine(treffer: List<ShiftMatch>): Set<String> {
    val diensttage: Set<LocalDate> = treffer
        .filter { it.shiftDefinition.kategorie == ShiftKategorie.DIENST }
        .mapTo(HashSet()) { it.calendarEvent.startTime.toLocalDate() }
    if (diensttage.isEmpty()) return emptySet()
    return treffer
        .filter {
            it.shiftDefinition.kategorie == ShiftKategorie.RUFBEREITSCHAFT &&
                it.calendarEvent.startTime.toLocalDate() in diensttage
        }
        .mapTo(HashSet()) { it.calendarEvent.id }
}
