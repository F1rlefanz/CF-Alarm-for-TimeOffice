package com.github.f1rlefanz.cf_alarmfortimeoffice.model

/**
 * Die Wahl „Tage ohne Eintrag verhalten sich“ an der festen Zeile „Ohne Kalendereintrag“
 * (Umbau „Schichttyp als Zentrale“, Etappe 1, SPEC E5 und 3.3 Punkt 2): „Eigene Einstellungen“
 * oder „Wie <Typ>“. Reine Funktionen auf [ShiftConfig]; gespeichert wird im `ShiftViewModel`
 * ueber `updateShiftConfig()`, wie jeder andere Schreibweg.
 *
 * Die Wahl ist [ShiftConfig.ohneKalendereintragWie] - ein Feld der Konfiguration und KEIN Eintrag
 * in `definitions`, damit eine aeltere App sie ueberliest (SPEC 4). Wirken laesst sie in Etappe 1
 * nur den Wecker (`AlarmUseCase`, `tageOhneEintrag`); Dimmen, „Nicht stoeren“ und Licht am Typ
 * kommen erst mit den spaeteren Etappen.
 */
object OhneEintragWahl {

    /**
     * Die Typen, auf die „Ohne Kalendereintrag“ verweisen darf, in gespeicherter Reihenfolge: alle
     * ausser Ignorieren (Issue #177, Prototyp `wieOptionen`). Ein Ignorieren-Typ hat nie eine
     * Weckzeit, und seine Termine werden uebergangen - „wie Ignorieren“ waere kein Verhalten.
     *
     * Krank und ausgeschaltete Typen stehen bewusst zur Wahl (SPEC E2: die Kategorie sperrt nichts;
     * dieselbe Auswahl bietet der Einordnen-Dialog). Dass sie in Etappe 1 keinen Wecker stellen,
     * sagt die Beschriftung ([weckt]), die Auswahl verbietet es nicht.
     */
    fun verweisbareTypen(definitions: List<ShiftDefinition>): List<ShiftDefinition> =
        definitions.filter { it.kategorie != ShiftKategorie.IGNORIEREN }

    /**
     * Der Typ, bei dem die Wahl gerade steht, oder `null` fuer „Eigene Einstellungen“. Ein Verweis
     * ins Leere (Typ geloescht, etwa durch einen Import) und ein Verweis auf einen Typ, der
     * inzwischen Ignorieren ist, lesen sich ebenfalls als `null` - was die Anzeige haekchen darf,
     * muss auch zur Wahl stehen.
     */
    fun gewaehlterTyp(config: ShiftConfig): ShiftDefinition? {
        val verweis = config.ohneKalendereintragWie ?: return null
        return verweisbareTypen(config.definitions).firstOrNull { it.id == verweis }
    }

    /**
     * Stellt die Wahl auf [typId] (`null` = „Eigene Einstellungen“).
     *
     * @return die neue Konfiguration, oder `null`, wenn nichts zu tun ist: der Typ steht schon so,
     *         ist unbekannt oder ein Ignorieren-Typ. „Eigene Einstellungen“ raeumt auch einen
     *         Verweis ins Leere ab (gespeichert `!= null`, obwohl angezeigt „eigene“).
     */
    fun waehle(config: ShiftConfig, typId: String?): ShiftConfig? {
        val ziel = typId?.let { id ->
            verweisbareTypen(config.definitions).firstOrNull { it.id == id } ?: return null
        }
        if (config.ohneKalendereintragWie == ziel?.id) return null
        return config.copy(ohneKalendereintragWie = ziel?.id)
    }

    /**
     * Die Konfiguration ohne [definition] - und wenn „Ohne Kalendereintrag“ auf sie verwies, im
     * SELBEN Ergebnis zurueck auf „Eigene Einstellungen“. Ein zweiter Schreibvorgang dafuer
     * ginge zwischen dem Loeschen und dem Zuruecksetzen durch einen Sync, der einen Wecker fuer
     * einen Typ stellt, den es nicht mehr gibt.
     */
    fun ohneTyp(config: ShiftConfig, definition: ShiftDefinition): ShiftConfig =
        config.copy(
            definitions = config.definitions.filterNot { it.id == definition.id },
            ohneKalendereintragWie = config.ohneKalendereintragWie?.takeUnless { it == definition.id }
        )

    /**
     * Stellt „Ohne Kalendereintrag“ wie [typ] an einem leeren Tag einen Wecker? Dieselbe Bedingung
     * wie `tageOhneEintrag` (Wecker an UND [istWaehlbareSchicht]); `OhneEintragWahlTest` haelt beide
     * gegeneinander, denn die Beschriftung der Wahl darf nichts anderes behaupten als der Sync tut.
     */
    fun weckt(typ: ShiftDefinition): Boolean = typ.weckerAn && typ.istWaehlbareSchicht()
}
