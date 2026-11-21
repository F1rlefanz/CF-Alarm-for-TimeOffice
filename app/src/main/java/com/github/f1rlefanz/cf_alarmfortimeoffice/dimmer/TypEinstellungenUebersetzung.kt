package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen

/**
 * Umbau Etappe 3 (#214, SPEC Abschnitt 4): uebersetzt den heutigen Bestand - Dimm-Regeln,
 * `dim_enabled`, beide „Nicht stoeren“-Schalter, die Dienstzeit-Ausnahmen und den Ruf-Cutoff - in
 * die Einstellungen am Typ und an der festen Zeile „Ohne Kalendereintrag“.
 *
 * REIN: liest nichts und schreibt nichts. Lesen (ueber strikte, nicht degradierende Wege) und
 * Schreiben ist `TypModellMigration` (Paket C); dieses Objekt beantwortet nur „was steht danach am
 * Typ“. Der Altbestand bleibt dabei unberuehrt (kopieren statt verschieben, SPEC 4).
 *
 * KEINE ZWEITE AUSWAHLLOGIK: Welche Regel ein Typ bekommt, sagt dieselbe Funktion, die heute den
 * Tag aufloest ([DimRuleUseCase.findRuleForShift]: deaktivierte Regeln zaehlen nicht, erster Treffer
 * gewinnt, Gross/Klein egal, UNIVERSAL als Rueckfall); die feste Zeile bekommt
 * [DimRuleUseCase.findRuleForFreeDay] (FREI, sonst UNIVERSAL). Eine eigene Nachbildung liefe
 * auseinander, sobald eine der beiden sich aendert.
 *
 * Bewusste Abweichung vom heutigen Verhalten (SPEC 4, festgelegt in #214): ein Typ der Kategorie
 * Frei OHNE eigene Regel bekommt die FREI-Regel, falls es eine gibt - heute bekaeme sein Tag
 * UNIVERSAL, weil er eine erkannte Schicht ist. Nach dem Umbau verhaelt er sich wie ein freier Tag.
 *
 * Nicht uebernommen wird, was heute nicht wirkt: deaktivierte Regeln und Regeln auf einen Namen,
 * den kein Typ traegt. Beide bleiben im Altbestand liegen.
 */
object TypEinstellungenUebersetzung {

    /**
     * Die Stufe, die eine uebersetzte Konfiguration traegt ([ShiftConfig.modellStufe]). Eine
     * Eingabe mit dieser oder einer hoeheren Stufe kommt unveraendert zurueck (idempotent).
     */
    const val MODELL_STUFE = 1

    /**
     * Der heutige Bestand, schon gelesen. Bewusst schlichte Werte statt `DndPrefs`: `dnd/` liest
     * von `dimmer/`, nie umgekehrt (Skill cfalarm-dimmer-und-dnd).
     *
     * @property regeln die Dimm-Regeln, wie `DimRuleRepository` sie haelt (auch deaktivierte).
     * @property dimmenAn `dim_enabled`.
     * @property dndSolangeGedimmt `dnd_follow_dimmer_enabled`.
     * @property dndWaehrendEintrag `dnd_during_shift_enabled`.
     * @property dndAusnahmen `dnd_shift_excluded_shifts` - Schichtnamen, exakt verglichen.
     * @property rufCutoffMinuten `dnd_oncall_cutoff_min`, Minuten seit Mitternacht (geklemmt gelesen).
     */
    data class Altbestand(
        val regeln: List<DimRule>,
        val dimmenAn: Boolean,
        val dndSolangeGedimmt: Boolean,
        val dndWaehrendEintrag: Boolean,
        val dndAusnahmen: Set<String>,
        val rufCutoffMinuten: Int
    )

    /**
     * Die Konfiguration mit Dimm- und „Nicht stoeren“-Einstellungen an jedem Typ und an der festen
     * Zeile, und mit [MODELL_STUFE]. Alles andere bleibt, wie es war - auch ein Verweis „wie <Typ>“
     * der festen Zeile ([ShiftConfig.ohneKalendereintragWie]); ihre EIGENEN Einstellungen werden
     * trotzdem gefuellt, damit ein spaeteres „Eigene Einstellungen“ den heutigen freien Tag vorfindet.
     */
    fun uebersetze(config: ShiftConfig, alt: Altbestand, auswahl: DimRuleUseCase): ShiftConfig {
        if (config.modellStufe >= MODELL_STUFE) return config

        val festeZeileRegel = auswahl.findRuleForFreeDay(alt.regeln)
        return config.copy(
            definitions = config.definitions.map { typ -> uebersetzeTyp(typ, alt, auswahl, festeZeileRegel) },
            ohneKalendereintrag = config.ohneKalendereintrag.copy(
                dimmen = dimmenAus(config.ohneKalendereintrag.dimmen, festeZeileRegel, alt.dimmenAn),
                nichtStoerenSolangeGedimmt = alt.dndSolangeGedimmt
            ),
            modellStufe = MODELL_STUFE
        )
    }

    private fun uebersetzeTyp(
        typ: ShiftDefinition,
        alt: Altbestand,
        auswahl: DimRuleUseCase,
        regelFuerFreienTag: DimRule?
    ): ShiftDefinition {
        // Krank und Ignorieren: kein Fenster, kein „Nicht stoeren“. Ein Krank-Tag ist ein
        // freigegebener Tag und bekommt das Verhalten der festen Zeile ueber die Freigabe
        // (`FreieTageStore`), nicht ueber eigene Fenster; ein ignorierter Termin ergibt keine
        // Schichtspanne. `an` folgt trotzdem dem Schalter, damit „aus bleibt aus“ ausnahmslos gilt.
        if (typ.kategorie == ShiftKategorie.KRANK || typ.kategorie == ShiftKategorie.IGNORIEREN) {
            return typ.copy(
                dimmen = dimmenAus(typ.dimmen, regel = null, an = alt.dimmenAn),
                nichtStoeren = typ.nichtStoeren.copy(solangeGedimmt = false, waehrendEintrag = false)
            )
        }

        val eigeneOderUniversal = auswahl.findRuleForShift(typ.name, alt.regeln)
        val regel =
            if (typ.kategorie == ShiftKategorie.FREI &&
                (eigeneOderUniversal == null || eigeneOderUniversal.shiftPattern == DimRule.SHIFT_UNIVERSAL)
            ) regelFuerFreienTag else eigeneOderUniversal

        // Exakt wie `DndShiftSpanResolver.buildShiftSpans` (`shiftName in excludedShifts`, Gross/Klein
        // zaehlt): die Spanne traegt dort den Namen der Definition.
        val waehrendEintrag = alt.dndWaehrendEintrag && typ.name !in alt.dndAusnahmen
        val nichtStoeren = typ.nichtStoeren.copy(solangeGedimmt = alt.dndSolangeGedimmt, waehrendEintrag = waehrendEintrag)

        return typ.copy(
            dimmen = dimmenAus(typ.dimmen, regel, alt.dimmenAn),
            nichtStoeren =
                if (typ.kategorie == ShiftKategorie.RUFBEREITSCHAFT) {
                    nichtStoeren.copy(endetSpaetestensGespeichert = alt.rufCutoffMinuten)
                } else nichtStoeren
        )
    }

    /**
     * Die Fenster der [regel] 1:1 (jedes Feld, auch Blockpositionen, Nachlauf, „Hell ab“ und
     * `startNurAmWecktag`), ihre Staerke an jedem Fenster, ihre Waerme am Typ.
     *
     * Eine Regel mit LEERER Fensterliste wird zu einem Typ ohne Fenster - heute die Unterdrueckung
     * des Tages; sie wird nie durch UNIVERSAL aufgefuellt (SPEC 4, `DimmerModellMigration`-Falle).
     * Ohne Regel: keine Fenster, Waerme `null` (= Vorgabe `dim_warmth`).
     *
     * Ueber [TypDimmen.mitFenster], nie `copy(fensterGespeichert = ...)`: undeutbare Fenster einer
     * anderen Version bleiben so erhalten (Skill-Nachtrag #213).
     */
    private fun dimmenAus(bisher: TypDimmen, regel: DimRule?, an: Boolean): TypDimmen {
        val fenster = regel?.let { r -> r.windows.map { TypDimmFenster(fenster = it, staerke = r.strength) } }.orEmpty()
        return bisher.copy(an = an, farbwaerme = regel?.warmth).mitFenster(fenster)
    }
}
