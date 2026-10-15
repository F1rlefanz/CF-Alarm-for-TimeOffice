package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import kotlinx.coroutines.flow.Flow

/**
 * Ein uebernommener Standort, gespeichert in ganzen ZEHNTELGRAD (#140). Der Typ `Int` erzwingt
 * die Rundung auf 0,1° (etwa 10 km): einen Pfad, der rohe Koordinaten speichert, gibt es nicht.
 */
data class GespeicherterStandort(
    val breiteZehntel: Int,
    val laengeZehntel: Int,
    /** Epoch-ms der Uebernahme - nur fuer die Anzeige "Übernommen am". */
    val uebernommenAm: Long
) {
    companion object {
        /** Die EINE Rundung - vor dem Schreiben, nie danach. */
        fun gerundet(breite: Double, laenge: Double, jetzt: Long) = GespeicherterStandort(
            breiteZehntel = Math.round(breite * 10).toInt(),
            laengeZehntel = Math.round(laenge * 10).toInt(),
            uebernommenAm = jetzt
        )
    }
}

/**
 * Was der Nutzer fuer den Ort eingestellt hat - roh, noch nicht aufgeloest (das macht
 * [OrtsAufloeser]). [land] `null` = keins gespeichert oder unbekannter Schluessel.
 */
data class TageslichtEinstellungen(
    val standort: GespeicherterStandort?,
    val land: PlzLand?,
    val plz: String?
) {
    companion object {
        /** Nichts eingestellt - zugleich die Degradation bei einem Lesefehler (= kein Ort). */
        val LEER = TageslichtEinstellungen(standort = null, land = null, plz = null)
    }
}

/** Speicher fuer den Ort. Liest selbst nur hinter dem Entsperr-Gate ([TageslichtQuelle.stand]). */
interface ITageslichtRepository : TageslichtQuelle {

    /** Fuer die UI. Lesefehler -> [TageslichtEinstellungen.LEER] (Degradation = kein Ort). */
    val einstellungen: Flow<TageslichtEinstellungen>

    /** Leere [plz] = loeschen. */
    suspend fun setzePlz(land: PlzLand, plz: String)

    /** Rundet auf 0,1° VOR dem Schreiben ([GespeicherterStandort.gerundet]). */
    suspend fun speichereStandort(breite: Double, laenge: Double, jetzt: Long)

    suspend fun entferneStandort()
}

/** Ergebnis einer einmaligen Standortabfrage auf Knopfdruck. */
sealed interface StandortErgebnis {
    data class Gefunden(val breite: Double, val laenge: Double) : StandortErgebnis
    data object KeineBerechtigung : StandortErgebnis
    data object Abgeschaltet : StandortErgebnis
    data object Zeitueberschreitung : StandortErgebnis
}

/** Einmalige, grobe Ortsbestimmung - NUR aus dem Vordergrund, nur auf Knopfdruck. Nie werfend. */
interface IStandortAbfrage {
    suspend fun einmalig(): StandortErgebnis
}
