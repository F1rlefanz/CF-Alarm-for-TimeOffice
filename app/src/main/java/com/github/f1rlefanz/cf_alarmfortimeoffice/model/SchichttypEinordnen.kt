package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import java.time.LocalTime
import java.util.UUID

/**
 * Reine Funktionen fuer den Einordnen-Dialog (Umbau „Schichttyp als Zentrale“, SPEC 3.2, E2, E4):
 * ein im Kalender gefundener Code wird ein neuer Typ einer Kategorie oder ignoriert.
 * „Gehoert zu einem bestehenden Typ“ ist [ShiftConfig.withCodeAssignedTo] und wird hier nicht
 * nachgebaut.
 *
 * Nichts hier speichert. [neuerTypAusCode] liefert einen Typ, den erst der Editor nach Bestaetigung
 * speichert - sonst wuerde eine geratene Weckzeit scharf. [ignorieren] liefert die neue
 * Konfiguration, die der Aufrufer speichert (wie bei [ShiftConfig.withCodeAssignedTo]).
 */
object SchichttypEinordnen {

    /**
     * Was eine Kategorie bei der NEUANLAGE vorgibt. Ein spaeterer Kategoriewechsel
     * ([ShiftDefinition.mitKategorie]) aendert davon nichts (E2). Nur Wecker an/aus und Weckzeit:
     * stummer Wecker, Dimmen und „Nicht stoeren“ am Typ kommen erst mit Etappe 2 und 3.
     */
    data class Voreinstellung(val weckerAn: Boolean, val weckzeit: LocalTime)

    /** Name eines Ignorieren-Typs, den [ignorieren] anlegt, weil es noch keinen aktiven gibt. */
    const val NAME_NEUER_IGNORIEREN_TYP = "Ignoriert"

    /**
     * Die Voreinstellung je Kategorie: Wecker an nur bei Dienst und Fortbildung. Weckzeiten wie im
     * Prototyp (Dienst 06:00, sonst 07:00) - geraten, deshalb speichert erst der Editor.
     * Ohne `else`: eine neue Kategorie muss hier bewusst entschieden werden.
     */
    fun voreinstellung(kategorie: ShiftKategorie): Voreinstellung = when (kategorie) {
        ShiftKategorie.DIENST -> Voreinstellung(weckerAn = true, weckzeit = LocalTime.of(6, 0))
        ShiftKategorie.FORTBILDUNG -> Voreinstellung(weckerAn = true, weckzeit = LocalTime.of(7, 0))
        ShiftKategorie.RUFBEREITSCHAFT,
        ShiftKategorie.FREI,
        ShiftKategorie.URLAUB,
        ShiftKategorie.KRANK,
        ShiftKategorie.IGNORIEREN -> Voreinstellung(weckerAn = false, weckzeit = LocalTime.of(7, 0))
    }

    /**
     * Ein neuer, NICHT gespeicherter Typ aus einem Kalender-Code: Name = Code, Muster = [Code],
     * aktiv, eine frische `id`, Voreinstellung der [kategorie]. Die Kategorie kommt ueber
     * [ShiftDefinition.mitKategorie], damit die Flags `isSickDay`/`isOnCall` dazu passen.
     * Ein Zeichen-Code wie „+“ (#150) ist ein gueltiges Muster.
     *
     * @return `null` fuer einen leeren Code.
     */
    fun neuerTypAusCode(
        code: String,
        kategorie: ShiftKategorie,
        neueId: () -> String = { UUID.randomUUID().toString() }
    ): ShiftDefinition? {
        val muster = code.trim()
        if (muster.isEmpty()) return null
        val vorgabe = voreinstellung(kategorie)
        return ShiftDefinition(
            id = neueId(),
            name = muster,
            keywords = listOf(muster),
            alarmTime = vorgabe.weckzeit,
            isEnabled = true,
            weckerAn = vorgabe.weckerAn
        ).mitKategorie(kategorie)
    }

    /**
     * Ordnet [code] einem Typ der Kategorie Ignorieren zu - wie [ShiftConfig.withCodeAssignedTo],
     * der Code steht danach also an KEINEM anderen Typ mehr.
     *
     * Ziel ist der erste AKTIVE Ignorieren-Typ. Ein ausgeschalteter wird nicht genommen:
     * [ShiftConfig.withCodeAssignedTo] schaltet das Ziel ein und haette damit still auch alle seine
     * alten Codes wieder ignoriert. Gibt es keinen aktiven, wird einer mit Wecker aus angelegt -
     * OHNE Muster, damit [ShiftConfig.withCodeAssignedTo] den Code eintraegt und sicher eine
     * Aenderung meldet (mit dem Code schon am neuen Typ fiele „nichts zu tun“ auf `null`).
     *
     * @return die neue Konfiguration, oder `null` wenn nichts zu tun war (leerer Code, oder der
     *         Code steht schon allein am aktiven Ignorieren-Typ).
     */
    fun ignorieren(
        config: ShiftConfig,
        code: String,
        neueId: () -> String = { UUID.randomUUID().toString() }
    ): ShiftConfig? {
        if (code.isBlank()) return null
        val vorhanden = config.definitions.firstOrNull {
            it.isEnabled && it.kategorie == ShiftKategorie.IGNORIEREN
        }
        if (vorhanden != null) return config.withCodeAssignedTo(code, vorhanden.id)

        val vorgabe = voreinstellung(ShiftKategorie.IGNORIEREN)
        val neu = ShiftDefinition(
            id = neueId(),
            name = NAME_NEUER_IGNORIEREN_TYP,
            keywords = emptyList(),
            alarmTime = vorgabe.weckzeit,
            isEnabled = true,
            weckerAn = vorgabe.weckerAn
        ).mitKategorie(ShiftKategorie.IGNORIEREN)
        return config.copy(definitions = config.definitions + neu).withCodeAssignedTo(code, neu.id)
    }
}
