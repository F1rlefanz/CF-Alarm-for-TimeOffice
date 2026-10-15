package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.PlzLand

/**
 * Alles, was der Ort-Bildschirm zeigt - fertig aufbereitet, damit der Bildschirm nichts rechnet.
 *
 * [genutzt] ist die Stufe, die gerade gilt (`null` = kein Ort). Die Uhrzeiten sind bereits
 * formatiert ("07:31"); `null` heisst: an diesem Tag gibt es das Ereignis nicht.
 */
data class OrtBildschirmZustand(
    val genutzt: OrtQuelle?,
    val daemmerungHeute: String?,
    val sonnenaufgangHeute: String?,
    val standortUebernommenAm: String?,
    val land: PlzLand,
    val plzEingabe: String,
    /** Aufgeloeste Region zur Eingabe; `null` bei leerer Eingabe. */
    val plzRegion: String?,
    val plzUnbekannt: Boolean,
    val zeitzone: String,
    /** `null`: fuer diese Zeitzone kennt die App keinen Ort. */
    val zeitzoneOrt: String?,
    /** Die Standortabfrage laeuft gerade (Knopf gesperrt, Hinweis darunter). */
    val standortLaeuft: Boolean = false,
    /** Warum der letzte Versuch nichts uebernommen hat; `null` = kein Fehler. */
    val standortFehler: StandortFehler? = null,
    /**
     * Die GESPEICHERTE PLZ, wenn sie gerade gilt. Kann von [plzEingabe] abweichen: eine unbekannte
     * Eingabe wird nicht gespeichert, es gilt weiter die alte - und die Aktuell-Karte nennt, was
     * gilt. `null` = wie [plzEingabe] (keine PLZ-Quelle aktiv, oder der Startzustand vor dem
     * ersten Laden).
     */
    val genutztPlz: String? = null,
    /** Regionsname zu [genutztPlz]; `null` = wie [plzRegion]. */
    val genutztName: String? = null
)

/** Warum "Standort übernehmen" nichts uebernommen hat - jeder Fall nennt den Ausweg PLZ. */
enum class StandortFehler {
    /** Berechtigung im Systemdialog abgelehnt oder entzogen. */
    ABGELEHNT,

    /** Standortermittlung in den Android-Einstellungen ausgeschaltet. */
    ABGESCHALTET,

    /** Kein Ergebnis in der Wartezeit, oder Speichern gescheitert. */
    FEHLGESCHLAGEN
}
