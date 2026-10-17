package com.github.f1rlefanz.cf_alarmfortimeoffice.shift

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.istWaehlbareSchicht
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Ein Tag ganz ohne Kalendereintrag, der den Wecker von [typ] bekommt (Umbau E5, #173). */
data class TagOhneEintrag(
    val datum: LocalDate,
    val weckzeit: LocalDateTime,
    val typ: ShiftDefinition
)

/**
 * Umbau E5 (#173): an welchen Tagen „Ohne Kalendereintrag“ wie <Typ> ([ShiftConfig.ohneKalendereintragWie])
 * einen Wecker bekommt, und zu welcher Weckzeit. Rein: keine Uhr, keine Zone, kein Speicher - die
 * Termine sind Ortszeit der Systemzone, wie das Repository sie liefert. Eingebaut in den Sync wird
 * die Funktion erst mit dem Paket „Wecker an Tagen ohne Kalendereintrag“ (#175).
 *
 * - **Leer** heisst: KEIN Termin ueberdeckt den Kalendertag, auch nicht teilweise. Ein Termin deckt
 *   jeden Tag von seinem Beginn bis zu seinem Ende ab - der Nachtdienst bis 07:00 also auch den
 *   Folgetag, ein mehrtaegiger oder ganztaegiger Termin jeden seiner Tage. Endet ein Termin genau um
 *   00:00, ist sein Endtag NICHT abgedeckt (wie in `AlarmUseCase.rohKrankTage`; ganztaegige Termine
 *   enden laut Repository ohnehin um 23:59 ihres letzten Tages).
 *   Nicht mit zaehlen nur [ignorierteTermine] (`Erkennung.ignorierteTermine`: allein von
 *   Ignorieren-Typen getroffen). Ein noch nicht eingeordneter Code und ein Krank-Termin sind
 *   Eintraege.
 * - **Die Loeschregel gilt hier umgekehrt:** eine unvollstaendige Liste laesst Tage leer AUSSEHEN.
 *   Deshalb nur Tage von heute ([jetzt]) bis VOR den Tag, der [horizontEnde] enthaelt (exklusiv,
 *   wie `horizontTag` in `syncAlarms()` und `SyncHorizonStore.istJenseitsDesAbrufs`). Eine LEERE
 *   [events]-Liste ergibt keinen Tag: die Kalender-Abwahl ruft `syncAlarms()` absichtlich mit einer
 *   leeren Liste auf, und sie hiesse sonst „jeder Tag ist leer“. Der Horizont deckt nur das
 *   Abruf-FENSTER ab: dass kein ausgewaehlter Kalender fehlt (`CalendarFetchOutcome.isComplete`),
 *   muss der Aufrufer pruefen - ein fehlender Kalender liesse jeden seiner Tage leer aussehen.
 * - **Freigegebene Tage** ([freieTage], die Vereinigung aus Hand und Krank laut Kalender) bekommen
 *   keinen Wecker; Gate und Backstop in `syncAlarms()`/`AlarmReceiver` bleiben davon unberuehrt.
 *   Ein Tag mit Krank-Termin ist ohnehin nicht leer - doppelt gehalten, kein Widerspruch.
 * - **Kein Tag**, wenn kein Verweis gesetzt ist, der Verweis ins Leere zeigt (WARN) oder der Typ
 *   keinen Wecker stellen wuerde: `weckerAn = false`, oder nicht [istWaehlbareSchicht] (aus,
 *   Ignorieren, Krank). Krank steht nicht in der Festlegung, folgt aber aus „wie <Typ>“: ein
 *   Krank-Eintrag stellt nie einen Wecker, er gibt den Tag frei - und die Auswahlliste des
 *   Verweises (#177) bietet nach #171 ohnehin nur waehlbare Typen an.
 * - **Die Weckzeit** ist die des Typs an diesem Datum - ohne Nachtschicht-Rueckrechnung, ohne
 *   Schichtbeginn (es gibt keinen Termin). Vor [jetzt] faellt sie weg (`isBefore` wie in `syncAlarms()`).
 *
 * **Bekannte Luecke am HEUTIGEN Tag, die der Einbau (#175) schliessen muss:** der Abruf beginnt bei
 * jetzt (`CalendarRepository`, `timeMin = now`). Ein heute schon BEENDETER Termin steht nicht in der
 * Liste - nach einem Nachtdienst bis 07:00 sieht heute um 08:00 leer aus, und eine spaetere Weckzeit
 * des Verweistyps (etwa 11:00) klingelte nach dem Nachtdienst. Diese Funktion hat kein Gedaechtnis und
 * kann das nicht sehen. Vorbilder fuer dieselbe Falle: `AlarmUseCase.zeitgebundeneEnden` (Krank) und
 * `ShiftSpanStore.mische` (beendete Spannen bleiben erhalten).
 */
fun tageOhneEintrag(
    events: List<CalendarEvent>,
    ignorierteTermine: List<CalendarEvent>,
    config: ShiftConfig,
    freieTage: Set<LocalDate>,
    jetzt: LocalDateTime,
    horizontEnde: LocalDateTime
): List<TagOhneEintrag> {
    if (events.isEmpty()) return emptyList()

    // Bewusst nicht ueber ohneKalendereintragWieTyp(): die liest einen Verweis ins Leere als
    // `null`, hier muss er sich von „kein Verweis“ unterscheiden (nur er ist eine Warnung wert).
    val verweis = config.ohneKalendereintragWie ?: return emptyList()
    val typ = config.definitions.firstOrNull { it.id == verweis }
    if (typ == null) {
        Logger.w(
            LogTags.SHIFT,
            "Ohne Kalendereintrag verweist auf einen Typ, den es nicht gibt (id=$verweis) - kein Wecker an leeren Tagen"
        )
        return emptyList()
    }
    if (!typ.weckerAn || !typ.istWaehlbareSchicht()) return emptyList()

    val heute = jetzt.toLocalDate()
    val horizontTag = horizontEnde.toLocalDate()
    if (!heute.isBefore(horizontTag)) return emptyList()

    val ignoriert = ignorierteTermine.mapTo(HashSet()) { it.calendarId to it.id }
    val abgedeckt: List<ClosedRange<LocalDate>> = events
        .filterNot { (it.calendarId to it.id) in ignoriert }
        .map { abgedeckteTage(it) }

    val tage = mutableListOf<TagOhneEintrag>()
    var datum = heute
    while (datum.isBefore(horizontTag)) {
        if (datum !in freieTage && abgedeckt.none { datum in it }) {
            val weckzeit = datum.atTime(typ.alarmTime)
            if (!weckzeit.isBefore(jetzt)) tage += TagOhneEintrag(datum, weckzeit, typ)
        }
        datum = datum.plusDays(1)
    }
    return tage
}

/** Die Kalendertage, die [termin] beruehrt; ein Ende genau um 00:00 zaehlt seinen Tag nicht mit. */
private fun abgedeckteTage(termin: CalendarEvent): ClosedRange<LocalDate> {
    val erster = termin.startTime.toLocalDate()
    var letzter = termin.endTime.toLocalDate()
    if (termin.endTime.toLocalTime() == LocalTime.MIDNIGHT) letzter = letzter.minusDays(1)
    return erster..maxOf(letzter, erster)
}
