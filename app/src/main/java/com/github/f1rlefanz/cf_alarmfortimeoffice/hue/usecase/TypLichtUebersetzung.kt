package com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase

import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.data.HueScheduleRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypLicht

/**
 * Umbau Etappe 4 (#247, SPEC E8 und Abschnitt 4): uebersetzt die heutigen Hue-Regeln
 * (`HueConfigRepository`, eine Liste mit `shiftPattern`) in die Lichtregeln am Typ
 * ([ShiftDefinition.licht], #246).
 *
 * REIN: liest nichts und schreibt nichts, und noch ruft sie niemand auf. Lesen und Schreiben ist
 * Sache der Licht-Migration; dieses Objekt beantwortet nur „was steht danach am Typ“. Der
 * Altbestand bleibt unberuehrt (kopieren statt verschieben, SPEC 4).
 *
 * WER AUFRUFT, uebergibt einen STRIKT gelesenen Bestand: eine leere Liste heisst hier „es gibt keine
 * Regeln“ und setzt [LICHT_STUFE] endgueltig. Eine zu „leer“ degradierte Lesestoerung wuerde so zur
 * Schreibwahrheit, und die Regeln kaemen nie an (Skill cfalarm-persistenz-und-auth). Also nicht ueber
 * den `getConfiguration()`-Flow (faengt zu leer ab), und erst nach der ersten Entsperrung: davor
 * liefert auch `getScheduleRules()` still `[]`.
 *
 * Die Zuordnung (Festlegungen des Betreuers in #247):
 * - Eine Regel mit Schichtnamen geht an den ERSTEN Typ dieses Namens, ohne Gross/Klein - derselbe
 *   Massstab wie [passtAufSchicht] und `TypDimmQuelle.fuerSchicht`. Ihre id bleibt. Kein
 *   Durchfallen: traegt dieser Typ schon Regeln, geht sie nirgends hin, auch nicht an einen zweiten
 *   Typ desselben Namens. Krank und Ignorieren sind hier NICHT ausgenommen: die Regel geht
 *   verlustfrei mit und laeuft dort mangels Weckpunkt ohnehin nicht, wie heute.
 * - Eine Regel mit [HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN] (ohne Gross/Klein, und VOR dem Namen
 *   geprueft: „ALL“ meint nie einen Typ dieses Namens) wird an jeden Typ KOPIERT, der einen Wecker
 *   haben kann ([kannWecker], allein nach der Kategorie), hinter dessen eigene Regeln, in der
 *   Reihenfolge des Bestands. Die Kopie hat die id [kopieId]; `shiftPattern` bleibt wortgleich, es
 *   hat am Typ keine Bedeutung (#246).
 * - Ausgeschaltet bleibt ausgeschaltet (SPEC 4): an einem stummen Typ (`isSilent`) laeuft heute keine
 *   Regel, auch keine allgemeine (`AlarmReceiver`, `HueSmartScheduler`). Dort kommen ALLE Regeln mit
 *   `enabled = false` an - sonst ginge am Lichtwecker nach dem Update ploetzlich Licht an.
 *   Deaktivierte Regeln bleiben ueberall deaktiviert.
 * - Eine Regel, deren Name keinen Typ trifft, wird nicht uebernommen; sie laeuft heute auch nicht.
 * - Nur uebersetzt, was am Typ noch Standard ist (#234): ein Typ mit `licht != TypLicht()` (auch mit
 *   nur undeutbaren Regeln einer anderen Version) behaelt sein Licht ganz und bekommt nichts dazu.
 *   Ein leerer Bestand setzt nur [LICHT_STUFE]; eine Eingabe mit gesetzter Stufe kommt unveraendert
 *   zurueck.
 * - `MAX_RULES_PER_SHIFT` gilt beim Anlegen, nicht hier: verlustfrei geht vor.
 *
 * Geaendert wird eine Regel nur per `copy(id = ..., enabled = ...)`, nie neu gebaut (der Default der
 * id ist die Uhrzeit), geschrieben nur ueber [TypLicht.mitRegeln].
 *
 * IDEMPOTENT auf zwei Wegen: die gesetzte Stufe gibt die Eingabe zurueck, und ohne Stufe traegt jeder
 * Typ, der etwas bekommen hat, danach Regeln und behaelt sie. Ein zweiter Lauf ueber denselben Bestand
 * (Abbruch vor dem Speichern) ergibt dieselben ids, weil [kopieId] nur von Alt-id und Typ-id abhaengt.
 */
object TypLichtUebersetzung {

    /**
     * Die Stufe, die eine uebersetzte Konfiguration traegt ([ShiftConfig.lichtStufe]). Eine Eingabe
     * mit dieser oder einer hoeheren Stufe kommt unveraendert zurueck.
     */
    const val LICHT_STUFE = 1

    /**
     * Was mit dem Bestand geschehen ist - fuer das Log der Migration. Jede Regel des Bestands laesst
     * sich daraus erklaeren: eine Regel mit Namen zaehlt genau einmal in [uebernommen], [verwaist] oder
     * [schonBelegt]; eine allgemeine je Typ mit moeglichem Wecker einmal in [kopiert] oder
     * [schonBelegt], oder - gibt es keinen solchen Typ - einmal in [verwaist].
     *
     * @property uebernommen Regeln mit Namen, die an ihren Typ gegangen sind.
     * @property kopiert Kopien allgemeiner Regeln, eine je Typ.
     * @property stummAbgeschaltet davon (uebernommen und kopiert) die, die eingeschaltet waren und an
     *   einem stummen Typ ausgeschaltet ankommen. Schon ausgeschaltete zaehlen nicht.
     * @property verwaist Regeln ohne Ziel: ihr Name trifft keinen Typ, oder sie sind allgemein und
     *   kein Typ kann einen Wecker haben.
     * @property schonBelegt Zuordnungen, die entfallen, weil der Typ schon Regeln traegt (#234).
     */
    data class Zaehler(
        val uebernommen: Int = 0,
        val kopiert: Int = 0,
        val stummAbgeschaltet: Int = 0,
        val verwaist: Int = 0,
        val schonBelegt: Int = 0
    )

    data class Ergebnis(val config: ShiftConfig, val zaehler: Zaehler = Zaehler())

    /**
     * Die Konfiguration mit den Lichtregeln an jedem Typ, der noch keine traegt, und mit
     * [LICHT_STUFE]. Alles andere bleibt, wie es war. [altRegeln] in der Reihenfolge des Bestands,
     * auch die deaktivierten.
     */
    fun uebersetze(config: ShiftConfig, altRegeln: List<HueScheduleRule>): Ergebnis {
        if (config.lichtStufe >= LICHT_STUFE) return Ergebnis(config)
        if (altRegeln.isEmpty()) return Ergebnis(config.copy(lichtStufe = LICHT_STUFE))

        val typen = config.definitions
        val (allgemeine, mitNamen) = altRegeln.partition { it.istAllgemein() }

        var verwaist = 0
        val eigeneJeTyp = List(typen.size) { mutableListOf<HueScheduleRule>() }
        for (regel in mitNamen) {
            // `passtAufSchicht` ist hier der reine Namensvergleich: allgemeine Regeln sind abgetrennt.
            val ziel = typen.indexOfFirst { regel.passtAufSchicht(it.name) }
            if (ziel < 0) verwaist++ else eigeneJeTyp[ziel] += regel
        }
        if (typen.none { it.kannWecker() }) verwaist += allgemeine.size

        var uebernommen = 0
        var kopiert = 0
        var stummAbgeschaltet = 0
        var schonBelegt = 0
        val neu = typen.mapIndexed { index, typ ->
            val eigene = eigeneJeTyp[index]
            val kopien = if (typ.kannWecker()) allgemeine.map { it.copy(id = kopieId(it.id, typ.id)) } else emptyList()
            when {
                typ.licht != TypLicht() -> {
                    schonBelegt += eigene.size + kopien.size
                    typ
                }
                eigene.isEmpty() && kopien.isEmpty() -> typ
                else -> {
                    uebernommen += eigene.size
                    kopiert += kopien.size
                    val angekommen = (eigene + kopien).map { regel ->
                        if (typ.isSilent && regel.enabled) {
                            stummAbgeschaltet++
                            regel.copy(enabled = false)
                        } else regel
                    }
                    typ.copy(licht = typ.licht.mitRegeln(angekommen))
                }
            }
        }

        return Ergebnis(
            config = config.copy(definitions = neu, lichtStufe = LICHT_STUFE),
            zaehler = Zaehler(uebernommen, kopiert, stummAbgeschaltet, verwaist, schonBelegt)
        )
    }

    /**
     * Die id der Kopie einer allgemeinen Regel [altId] am Typ [typId]: `<Laenge der Alt-id>:<Alt-id>@<Typ-id>`.
     * Deterministisch, damit ein zweiter Lauf dieselbe id ergibt, und eindeutig je Paar: die Laenge
     * vorn sagt, wo die Alt-id endet, auch wenn eine der beiden ids `@` oder `:` enthaelt (ohne sie
     * ergaeben „x@y“ an „z“ und „x“ an „y@z“ beide „x@y@z“). Von einer heute vergebenen id
     * (`rule_...`) unterscheidet sie die fuehrende Ziffer. Eine wortgleiche id im Altbestand ist nur
     * per Hand-Import moeglich; dann behaelt die Regel mit Namen ihre id (Festlegung), wie heute schon
     * zwei Regeln derselben id.
     */
    fun kopieId(altId: String, typId: String): String = "${altId.length}:$altId@$typId"

    private fun HueScheduleRule.istAllgemein(): Boolean =
        shiftPattern.equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true)

    /**
     * Kann dieser Typ einen Wecker haben? Allein nach der Kategorie (Festlegung: alle ausser Krank
     * und Ignorieren) - nicht nach `weckerAn` oder `isEnabled`: die kann der Nutzer spaeter umstellen,
     * und dann soll die allgemeine Regel da sein, wie sie heute fuer ihn gilt.
     */
    private fun ShiftDefinition.kannWecker(): Boolean =
        kategorie != ShiftKategorie.KRANK && kategorie != ShiftKategorie.IGNORIEREN
}
