package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.LightTargets
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.interfaces.UnresolvedRuleTarget
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition

/**
 * Umbau Etappe 4 (#249, SPEC E8): die EINE Quelle der Lichtregeln zur Laufzeit - die Regeln am Typ
 * ([ShiftDefinition.licht], #246). Der Regel-Speicher des Hue-Bereichs (`HueConfigRepository`)
 * wird fuer die Ausfuehrung weder gelesen noch beschrieben; er bleibt als Altbestand bis Etappe 9
 * liegen (kopieren statt verschieben, SPEC 4). Geschrieben wird seit #250 ueber [TypLichtSchreiben].
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
    private val wirksameTypen: List<ShiftDefinition> = wirksameTypen(config).map { config.definitions[it] }

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

/**
 * Die Stellen der wirksamen Typen in `config.definitions`: je Name (ohne Gross/Klein) der ERSTE.
 * Gemeinsamer Massstab von [TypLichtQuelle] (wer laeuft) und [TypLichtSchreiben] (wo geschrieben
 * wird) - zwei Herleitungen waeren zwei Wahrheiten.
 */
private fun wirksameTypen(config: ShiftConfig): List<Int> =
    config.definitions.indices.fold(emptyList()) { bisher, i ->
        val name = config.definitions[i].name
        if (bisher.any { config.definitions[it].name.equals(name, ignoreCase = true) }) bisher else bisher + i
    }

/**
 * Umbau Etappe 4 (#250, SPEC E8): Anlegen, Aendern, Loeschen und Ziel-Abgleich der Lichtregeln AM
 * TYP, als reine Funktionen auf einer Konfiguration. Der Aufrufer (`HueRuleUseCase`) fuehrt sie in
 * EINER Transaktion aus (`IShiftConfigRepository.updateShiftConfig`), nie als Lesen-dann-Speichern:
 * der Ziel-Abgleich laeuft im Hintergrund und ueberschriebe sonst einen gleichzeitig gespeicherten
 * Stand (Festlegung des Betreuers in #250).
 *
 * - Ein Typ wird vom bestehenden aus geschrieben (`copy`, `TypLicht.mitRegeln`), und nur ein Typ,
 *   dessen Regeln sich wirklich aendern - sonst wiche das neu kodierte JSON ab, und die Transaktion
 *   schriebe doch.
 * - Gearbeitet wird auf den GESPEICHERTEN Regeln (`TypLicht.regeln`), nicht auf der Projektion von
 *   [TypLichtQuelle] (dort ist `shiftPattern` ueberschrieben).
 * - Wo eine Regel liegt, entscheidet ihre id, gesucht mit demselben Vorrang wie beim Leser: erst die
 *   wirksamen Typen in gespeicherter Reihenfolge, dann die verdeckten. Je Typ trifft es die ERSTE
 *   Regel dieser id. Loeschen und Aendern treffen so genau eine Regel eines Typs, auch wenn eine id
 *   (Fremdbestand) mehrfach vorkommt; die Kopien einer frueher allgemeinen Regel tragen ohnehin
 *   eigene ids (`TypLichtUebersetzung.kopieId`).
 * - Welcher Typ beim Anlegen gemeint ist, sagt `shiftPattern` = Typname (der erste dieses Namens,
 *   wie beim Leser). „Alle Schichten“ ([HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN]) wird abgelehnt: am
 *   Typ gibt es keine allgemeine Regel, die Migration hat die alten als Kopien verteilt (#247).
 */
object TypLichtSchreiben {

    /** Ausgang einer Aenderung: neue Konfiguration samt betroffener Regel, oder ein Grund fuer den Nutzer. */
    sealed interface Ausgang {
        /** [regel] = die geschriebene bzw. entfernte Regel; `null` beim Loeschen einer unbekannten id. */
        data class Geschrieben(val config: ShiftConfig, val regel: HueScheduleRule?) : Ausgang
        data class Abgelehnt(val grund: String) : Ausgang
    }

    /** Ergebnis des Ziel-Abgleichs ueber alle Typen; Zaehler wie [HueTargetReconciler.Outcome]. */
    data class Abgleich(
        val config: ShiftConfig,
        val remapped: Int,
        val namesRefreshed: Int,
        val unresolved: List<UnresolvedRuleTarget>
    )

    internal const val GRUND_ALLE_SCHICHTEN =
        "Eine Lichtregel gehört jetzt zu einem Schichttyp. Wähle einen Typ statt „Alle Schichten“."

    /** Die Stelle des Typs, an dem eine Regel mit [shiftPattern] liegt; `null` = keiner. */
    fun zielTyp(config: ShiftConfig, shiftPattern: String): Int? =
        // Ohne trim, wie der Leser: `getAllRules()` setzt `shiftPattern` wortgleich auf den Typnamen.
        wirksameTypen(config).firstOrNull { config.definitions[it].name.equals(shiftPattern, ignoreCase = true) }

    /** Die Stelle des Typs, der die Regel [regelId] traegt (Vorrang wie beim Leser); `null` = keiner. */
    fun traeger(config: ShiftConfig, regelId: String): Int? {
        val wirksam = wirksameTypen(config)
        val reihenfolge = wirksam + config.definitions.indices.filterNot { it in wirksam }
        return reihenfolge.firstOrNull { i -> config.definitions[i].licht.regeln.any { it.id == regelId } }
    }

    /** Legt [regel] am Typ `regel.shiftPattern` an; [regel] traegt schon ihre endgueltige id. */
    fun neueRegel(config: ShiftConfig, regel: HueScheduleRule, hoechstensJeTyp: Int): Ausgang {
        grundGegenZiel(config, regel)?.let { return Ausgang.Abgelehnt(it) }
        val ziel = checkNotNull(zielTyp(config, regel.shiftPattern))
        if (config.definitions.any { typ -> typ.licht.regeln.any { it.id == regel.id } }) {
            return Ausgang.Abgelehnt("Diese Regel gibt es bereits.")
        }
        val regeln = config.definitions[ziel].licht.regeln
        if (regeln.size >= hoechstensJeTyp) return Ausgang.Abgelehnt(hoechstzahl(hoechstensJeTyp))
        return Ausgang.Geschrieben(config.mitRegelnAn(ziel, regeln + regel), regel)
    }

    /**
     * Ersetzt die Regel mit der id von [regel]. Nennt `shiftPattern` einen anderen Typ, zieht sie
     * dorthin um (in derselben Transaktion, ans Ende). Eine unbekannte id wird angelegt - wie bisher
     * (`IHueConfigRepository.updateScheduleRule` war ein Upsert).
     */
    fun geaenderteRegel(config: ShiftConfig, regel: HueScheduleRule, hoechstensJeTyp: Int): Ausgang {
        val bisher = traeger(config, regel.id) ?: return neueRegel(config, regel, hoechstensJeTyp)
        grundGegenZiel(config, regel)?.let { return Ausgang.Abgelehnt(it) }
        val ziel = checkNotNull(zielTyp(config, regel.shiftPattern))
        val alt = config.definitions[bisher].licht.regeln
        val stelle = alt.indexOfFirst { it.id == regel.id }
        if (ziel == bisher) {
            return Ausgang.Geschrieben(
                config.mitRegelnAn(bisher, alt.mapIndexed { i, r -> if (i == stelle) regel else r }),
                regel
            )
        }
        val zielRegeln = config.definitions[ziel].licht.regeln
        if (zielRegeln.size >= hoechstensJeTyp) return Ausgang.Abgelehnt(hoechstzahl(hoechstensJeTyp))
        val ohne = config.mitRegelnAn(bisher, alt.filterIndexed { i, _ -> i != stelle })
        return Ausgang.Geschrieben(ohne.mitRegelnAn(ziel, zielRegeln + regel), regel)
    }

    /** Entfernt GENAU EINE Regel mit [regelId] an genau einem Typ; eine unbekannte id aendert nichts. */
    fun ohneRegel(config: ShiftConfig, regelId: String): Ausgang.Geschrieben {
        val bei = traeger(config, regelId) ?: return Ausgang.Geschrieben(config, null)
        val alt = config.definitions[bei].licht.regeln
        val stelle = alt.indexOfFirst { it.id == regelId }
        return Ausgang.Geschrieben(config.mitRegelnAn(bei, alt.filterIndexed { i, _ -> i != stelle }), alt[stelle])
    }

    /**
     * Ziel-Abgleich nach einem Bridge-Wechsel AM TYP: dieselbe Logik ([HueTargetReconciler]) je
     * Typ, nur ein anderer Speicher. Abgeglichen werden alle Typen, auch verdeckte - ihre Daten
     * sollen stimmen, falls der verdeckende Typ umbenannt wird. Der Altbestand im Hue-Store wird
     * NICHT mehr abgeglichen (eingefroren, SPEC 4).
     */
    fun abgleichen(config: ShiftConfig, targets: LightTargets): Abgleich {
        var remapped = 0
        var namesRefreshed = 0
        val unresolved = mutableListOf<UnresolvedRuleTarget>()
        val definitionen = config.definitions.map { typ ->
            if (typ.licht.regeln.isEmpty()) return@map typ
            val ausgang = HueTargetReconciler.reconcile(typ.licht.regeln, targets)
            remapped += ausgang.remapped
            namesRefreshed += ausgang.namesRefreshed
            unresolved += ausgang.unresolved
            if (ausgang.rules == typ.licht.regeln) typ else typ.copy(licht = typ.licht.mitRegeln(ausgang.rules))
        }
        val neu = if (definitionen == config.definitions) config else config.copy(definitions = definitionen)
        return Abgleich(neu, remapped, namesRefreshed, unresolved)
    }

    /** Warum [regel] an keinem Typ landen kann; `null` = es gibt ihren Typ ([zielTyp]). */
    private fun grundGegenZiel(config: ShiftConfig, regel: HueScheduleRule): String? = when {
        regel.shiftPattern.trim().equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true) -> GRUND_ALLE_SCHICHTEN
        zielTyp(config, regel.shiftPattern) == null -> "Den Schichttyp „${regel.shiftPattern}“ gibt es nicht."
        else -> null
    }

    private fun hoechstzahl(n: Int) = "Für einen Schichttyp sind höchstens $n Lichtregeln möglich."

    private fun ShiftConfig.mitRegelnAn(stelle: Int, regeln: List<HueScheduleRule>): ShiftConfig =
        copy(definitions = definitions.mapIndexed { i, typ ->
            if (i == stelle) typ.copy(licht = typ.licht.mitRegeln(regeln)) else typ
        })
}
