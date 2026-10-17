package com.github.f1rlefanz.cf_alarmfortimeoffice.model

/**
 * Kategorie eines Schichttyps (Umbau „Schichttyp als Zentrale“, SPEC E1/E2).
 *
 * Die Kategorie SORTIERT nur und setzt Voreinstellungen - sie sperrt nichts (E2). Zwei Werte sind
 * an die heutigen Flags gebunden und MUESSEN mit ihnen deckungsgleich bleiben:
 * [KRANK] == [ShiftDefinition.isSickDay] (#142, gibt den ganzen Tag frei) und
 * [RUFBEREITSCHAFT] == [ShiftDefinition.isOnCall]. Die Flags bleiben die Sprache fuer alten Code
 * und fuer alle heutigen Leser (DND, Ruf-Abfrage, Erkennung, Editor); die Kategorie wird deshalb
 * nie neben ihnen gepflegt, sondern aus ihnen gelesen - siehe [ShiftDefinition.kategorie].
 *
 * [schluessel] ist das Speicherformat und darf sich nie aendern, auch wenn ein Enum-Name es tut.
 * Bewusst KEIN kotlinx-Enum-Serializer: ein unbekannter Wert (eine spaetere Version mit einer
 * achten Kategorie, danach zurueckgenommen) liesse dort die GANZE Schicht-Konfiguration als
 * unlesbar enden - hier wird er zu „unbekannt“ und faellt auf die Ableitung aus den Flags zurueck.
 */
enum class ShiftKategorie(val schluessel: String) {
    DIENST("dienst"),
    RUFBEREITSCHAFT("rufbereitschaft"),
    FREI("frei"),
    URLAUB("urlaub"),
    FORTBILDUNG("fortbildung"),
    KRANK("krank"),
    IGNORIEREN("ignorieren");

    companion object {
        /** Die Kategorie zu einem gespeicherten [schluessel], `null` fuer fehlend oder unbekannt. */
        fun vonSchluessel(schluessel: String?): ShiftKategorie? =
            entries.firstOrNull { it.schluessel == schluessel }

        /**
         * Die wirksame Kategorie: die Flags gehen vor, dann der gespeicherte Wert, dann [DIENST].
         *
         * - [isSickDay] → [KRANK], auch wenn [isOnCall] ebenfalls gesetzt ist (heute speicherbar).
         *   Das ist schon das wirksame Verhalten: `ShiftRecognitionEngine` zweigt Krank-Termine vor
         *   jeder Schicht ab, ein solcher Typ ergibt nie eine Schichtspanne, sein `isOnCall` wirkt
         *   nirgends.
         * - sonst [isOnCall] → [RUFBEREITSCHAFT].
         * - sonst der gespeicherte Wert - ausser [KRANK]/[RUFBEREITSCHAFT] ohne ihr Flag: dann hat
         *   alter Code (der Editor kennt nur die Flags) das Flag zurueckgenommen, und das gilt.
         * - fehlt er (Bestand vor dem Umbau) oder ist er unbekannt → [DIENST].
         */
        fun ableiten(isSickDay: Boolean, isOnCall: Boolean, gespeichert: ShiftKategorie?): ShiftKategorie = when {
            isSickDay -> KRANK
            isOnCall -> RUFBEREITSCHAFT
            gespeichert == null || gespeichert == KRANK || gespeichert == RUFBEREITSCHAFT -> DIENST
            else -> gespeichert
        }
    }
}
