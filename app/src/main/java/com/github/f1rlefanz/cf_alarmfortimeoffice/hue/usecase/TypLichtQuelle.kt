package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition

/**
 * Umbau Etappe 4 (#249, SPEC E8): die EINE Quelle der Lichtregeln zur Laufzeit - die Regeln am Typ
 * ([ShiftDefinition.licht], #246). Der Regel-Speicher des Hue-Bereichs (`HueConfigRepository`)
 * wird fuer die Ausfuehrung nicht mehr gelesen; er bleibt als Altbestand bis Etappe 9 liegen
 * (kopieren statt verschieben, SPEC 4), und das SCHREIBEN zieht erst #250 um.
 *
 * EINE NAHT, KEIN NEUER RECHENWEG (Festlegung des Betreuers in #249, Muster `TypDimmQuelle`):
 * Diese Klasse baut je Typ gewoehnliche [HueScheduleRule] mit `shiftPattern = Typname` und reicht
 * sie als flache Liste an `HueRuleUseCase.getAllRules()`. Auswahl (`passtAufSchicht`), Tageslicht,
 * Sonnenaufgang, Vorab-Auto-Aus und Szenen laufen damit wortgleich weiter.
 *
 * - Das gespeicherte `shiftPattern` einer Regel am Typ hat keine Bedeutung (#246) und wird hier
 *   IMMER ueberschrieben. Die Migration (#247) laesst es wortgleich stehen, auch `"ALL"` bei den
 *   Kopien einer allgemeinen Regel - ohne das Ueberschreiben feuerte jede Kopie bei JEDEM Wecker.
 * - Gleichnamige Typen (ohne Gross/Klein): der ERSTE gewinnt, wie `TypDimmQuelle.fuerSchicht` und
 *   die Zuordnung der Migration. Die Regeln der verdeckten Typen laufen nicht ([verdeckteRegeln]).
 * - Die feste Zeile „Ohne Kalendereintrag“ hat kein Licht (#246, E8: ohne Weckzeit kein Licht).
 * - Kein Rueckfall auf den Altbestand, auch nicht bei `lichtStufe = 0` (Festlegung #249, wie #217).
 *   Eine gescheiterte Migration steht als Karte im System-Status (#248).
 *
 * REIN und ohne Zustand: wer die Regeln braucht, baut die Quelle bei JEDEM Lauf aus einer frisch
 * gelesenen Konfiguration ([Leser]). Deshalb wirkt eine geaenderte Regel beim naechsten Lauf, ohne
 * Neustart.
 */
class TypLichtQuelle(config: ShiftConfig) {

    /** Je Name der erste Typ, in gespeicherter Reihenfolge. */
    private val wirksameTypen: List<ShiftDefinition> = config.definitions.fold(emptyList()) { bisher, typ ->
        if (bisher.any { it.name.equals(typ.name, ignoreCase = true) }) bisher else bisher + typ
    }

    /** Alle deutbaren Lichtregeln der wirksamen Typen, je Regel `shiftPattern = Typname`. */
    val regeln: List<HueScheduleRule> = wirksameTypen.flatMap { typ ->
        typ.licht.regeln.map { it.copy(shiftPattern = typ.name) }
    }

    /** Gespeicherte Regeln, die diese Version nicht deuten kann - sie laufen nicht (#246). */
    val unlesbareRegeln: Int = wirksameTypen.sumOf { it.licht.unlesbareRegeln }

    /** Regeln an Typen, die ein frueherer Typ gleichen Namens verdeckt - sie laufen nicht. */
    val verdeckteRegeln: Int = config.definitions.sumOf { it.licht.regeln.size } -
        wirksameTypen.sumOf { it.licht.regeln.size }

    /**
     * Liest die Schicht-Konfiguration STRIKT (Festlegung #249): ein Fehler ist ein Fehlschlag, nie
     * eine leere oder die Standard-Konfiguration - sonst wuerde ein Lesefehler zu „kein Licht
     * konfiguriert“. Hilt bindet `IShiftConfigRepository.getCurrentShiftConfig()` (`HueModule`),
     * das vor der ersten Entsperrung verweigert statt still leer zu liefern.
     *
     * Eine eigene Schnittstelle statt des Repositorys, damit Tests eine Konfiguration als Lambda
     * reichen koennen - und damit der Kotlin-Default am `HueRuleUseCase` ([NICHT_VERBUNDEN])
     * scheitert, statt still „keine Regeln“ zu melden.
     */
    fun interface Leser {
        suspend fun konfiguration(): Result<ShiftConfig>

        companion object {
            /** Kein Leser verbunden: jeder Lauf ist ein Fehlschlag, nie eine leere Regelliste. */
            val NICHT_VERBUNDEN: Leser = Leser {
                Result.failure(IllegalStateException("Keine Schicht-Konfiguration fuer die Lichtregeln verbunden"))
            }
        }
    }
}
