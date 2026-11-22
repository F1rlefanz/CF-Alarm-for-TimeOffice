package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmFenster
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.TypDimmen

/**
 * Umbau Etappe 3 (#217, SPEC E6, E7, Abschnitt 4): die EINE Dimm-Fenster-Quelle zur Laufzeit -
 * die Fenster am Typ und an der festen Zeile „Ohne Kalendereintrag“. Der Regel-Speicher
 * (`DimRuleRepository`) wird vom Scheduler nicht mehr gelesen; er bleibt als Altbestand bis
 * Etappe 9 liegen (kopieren statt verschieben, SPEC 4).
 *
 * KEIN NEUER RESOLVER: Dieser Adapter baut je Typ eine gewoehnliche [DimRule] und reicht sie in
 * dieselben Lambdas von [DimWindowResolver.buildRuleSpans], die bisher die Regelliste bedient
 * haben. Auswahl unter mehreren Eintraegen eines Tages, Blockpositionen, Anker, Nachlauf und
 * Tageslicht bleiben damit wortgleich (Festlegung des Betreuers in #217).
 *
 * Die Semantik, wortgleich mit dem Rueckbau, gegen den die Uebersetzung (#214) geprueft ist:
 * - Ein Typ OHNE wirksames Fenster (`an = false` oder keine deutbaren Fenster) ist eine Regel mit
 *   LEERER Fensterliste - er unterdrueckt das Dimmen seines Tages, auch wenn ein zweiter Eintrag
 *   desselben Tages Fenster hat. Das ist die harmlose Richtung, wie die leere Regel bisher.
 * - Nur ein Schichtname, den KEIN Typ traegt, liefert `null` (kein Treffer, wie bisher eine
 *   Schicht ohne Regel und ohne UNIVERSAL).
 * - Freie und freigegebene Tage bekommen die feste Zeile; steht sie auf „wie <Typ>“, dessen
 *   Fenster. Dort liegt keine Schichtspanne: Fenster mit Anker an Weckzeit oder Dienstende
 *   loesen an solchen Tagen nicht auf, `ALARM_SONST_CLOCK` findet den Wecker ueber die Zeitleiste.
 *
 * Die Staerke steht JE FENSTER in der Regel ([DimRule.fensterStaerken]), die Waerme kommt vom Typ,
 * sonst ist sie die Vorgabe `dim_warmth` ([vorgabeWaerme]). Beide sind hier auf den Bereich des
 * Overlays geklemmt - ein Import umgeht jede Eingabepruefung; geschrieben wird davon nichts.
 */
class TypDimmQuelle(config: ShiftConfig, vorgabeWaerme: Int) {

    /** Je Typ seine Regel, in gespeicherter Reihenfolge (erster Treffer gewinnt, wie `findRuleForShift`). */
    private val typRegeln: List<Pair<String, DimRule>> = config.definitions.map { typ ->
        typ.name to regelAus(id = REGEL_ID_PRAEFIX + typ.id, muster = typ.name, dimmen = typ.dimmen, vorgabeWaerme)
    }

    /**
     * Die Regel freier und freigegebener Tage. Der Verweis „wie <Typ>“ wird so aufgeloest, wie die
     * Typ-Seite ihn anzeigt ([OhneEintragWahl.gewaehlterTyp]): ein Verweis ins Leere oder auf einen
     * Ignorieren-Typ steht dort als „Eigene Einstellungen“ - und wirkt dann auch so.
     */
    private val festeZeile: DimRule = OhneEintragWahl.gewaehlterTyp(config)
        ?.let { typ -> regelAus(REGEL_ID_PRAEFIX + typ.id, DimRule.SHIFT_FREE, typ.dimmen, vorgabeWaerme) }
        ?: regelAus(REGEL_ID_FESTE_ZEILE, DimRule.SHIFT_FREE, config.ohneKalendereintrag.dimmen, vorgabeWaerme)

    /** Alle Regeln dieser Quelle - fuer die Frage, ob irgendein Fenster Tageslicht braucht. */
    val regeln: List<DimRule> = typRegeln.map { it.second } + festeZeile

    /**
     * Hat irgendein Typ oder die feste Zeile ein wirksames Fenster? Der Keep-alive der Tick-Kette
     * (`DimScheduleUseCase.fallbackTick`) haengt daran; `dim_enabled` wird nicht mehr gelesen.
     */
    val hatWirksamesFenster: Boolean = regeln.any { it.windows.isNotEmpty() }

    /** Die Regel des Typs, der [schichtName] traegt - Gross/Klein egal wie `findRuleForShift`. */
    fun fuerSchicht(schichtName: String): DimRule? =
        typRegeln.firstOrNull { (name, _) -> name.equals(schichtName, ignoreCase = true) }?.second

    /** Die Regel eines Tages ohne Schichtspanne (frei, freigegeben, ohne Kalendereintrag). */
    fun fuerFreienTag(): DimRule = festeZeile

    companion object {
        private const val REGEL_ID_PRAEFIX = "typ:"
        private const val REGEL_ID_FESTE_ZEILE = "ohne-kalendereintrag"

        /**
         * Hat eine Aenderung der Konfiguration die Dimm- oder „Nicht stoeren“-Einstellungen eines
         * Typs oder der festen Zeile veraendert (auch: Typ angelegt oder geloescht, Verweis „wie
         * <Typ>“ umgestellt)? Dann verschieben sich Fenstergrenzen, und beide Zeitketten muessen neu
         * armiert werden (Pflichtregel, `ZeitkettenArmierer`). Verglichen wird je Typ-id - eine
         * reine UMBENENNUNG ist keine Aenderung der Fenster: die Spannen tragen nach dem Sync den
         * neuen Namen, und ihr Nachzug hat einen eigenen Weg (`ShiftViewModel.zieheRegelmusterNach`).
         *
         * Ohne Vorher-Stand ist nicht zu sagen, was sich geaendert hat: dann `true` (lieber einmal
         * zu viel armieren, als eine Kette auf einem ueberholten Plan stehen lassen).
         */
        fun dimmenOderNichtStoerenGeaendert(vorher: ShiftConfig?, nachher: ShiftConfig): Boolean =
            vorher == null || signatur(vorher) != signatur(nachher)

        private fun signatur(config: ShiftConfig): Any = Triple(
            config.definitions.associate { it.id to (it.dimmen to it.nichtStoeren) },
            config.ohneKalendereintrag,
            config.ohneKalendereintragWie
        )

        private fun regelAus(id: String, muster: String, dimmen: TypDimmen, vorgabeWaerme: Int): DimRule {
            val wirksam: List<TypDimmFenster> = if (dimmen.an) dimmen.fenster else emptyList()
            val staerken = wirksam.map { it.staerke.coerceIn(0, DimOverlayPrefs.STRENGTH_MAX) }
            return DimRule(
                id = id,
                name = muster,
                shiftPattern = muster,
                enabled = true,
                windows = wirksam.map { it.fenster },
                strength = staerken.firstOrNull() ?: TypDimmFenster.STANDARD_STAERKE,
                warmth = (dimmen.farbwaerme ?: vorgabeWaerme).coerceIn(0, DimOverlayPrefs.WARMTH_MAX),
                fensterStaerken = staerken
            )
        }
    }
}
