package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import android.content.Context
import android.content.SharedPreferences

/**
 * Wo der uebernommene Standort liegt - abstrahiert, damit [TageslichtRepository] ohne Android
 * testbar ist. Lesefehler WERFEN (das Repository degradiert sie auf "kein Ort"); Schreiben meldet
 * den `commit()`-Erfolg.
 */
internal interface StandortSpeicher {
    fun lies(): GespeicherterStandort?
    fun schreibe(standort: GespeicherterStandort): Boolean
    fun loesche(): Boolean
}

/**
 * Der auf 0,1° gerundete Standort fuer die Sonnenzeiten (#140), in einer EIGENEN
 * SharedPreferences-Datei `tageslicht_standort.xml` (CE-Storage).
 *
 * WARUM NICHT IM DataStore `settings`: Android-Backup und Geraetetransfer nehmen ganze Dateien
 * mit, ein einzelner Schluessel laesst sich aus einer DataStore-Datei nicht ausnehmen. Der
 * Standort beschreibt DIESES Geraet und soll weder in die Cloud noch auf ein neues Geraet - er ist
 * deshalb in `backup_rules.xml` und in BEIDEN Bloecken von `data_extraction_rules.xml`
 * ausgeschlossen (`BackupRulesConsistencyTest`). Land und PLZ liegen dagegen im `settings`-Store
 * und reisen mit. Der Konfigurations-Export liest nur die DataStores und sieht diese Datei nie.
 *
 * Gespeichert werden ganze ZEHNTELGRAD (`Int`): einen Pfad, der rohe Koordinaten ablegt, gibt es
 * damit nicht ([GespeicherterStandort.gerundet]).
 *
 * `getSharedPreferences()` steht NUR in Funktionen, nie in einem Property-Initializer
 * (`tools/invarianten/pruefe_code.py`, Regel "NICHTS am Application-Graphen ..."): die Datei liegt
 * im CE-Storage und ist im Direct Boot nicht lesbar. Gelesen wird ausschliesslich ueber
 * [TageslichtRepository], hinter dem Entsperr-Gate.
 */
object TageslichtStandortPrefs {

    /** Dateiname ohne `.xml` - die Backup-Regeln nennen `tageslicht_standort.xml`. */
    const val PREFS_NAME = "tageslicht_standort"

    private const val KEY_BREITE_ZEHNTEL = "breite_zehntel"
    private const val KEY_LAENGE_ZEHNTEL = "laenge_zehntel"
    private const val KEY_UEBERNOMMEN_AM = "uebernommen_am"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Wirft bei einem Lesefehler (etwa falsch typisierter Wert) - der Aufrufer degradiert. */
    fun lies(context: Context): GespeicherterStandort? {
        val p = prefs(context)
        if (!p.contains(KEY_BREITE_ZEHNTEL) || !p.contains(KEY_LAENGE_ZEHNTEL)) return null
        return ausWerten(
            breiteZehntel = p.getInt(KEY_BREITE_ZEHNTEL, 0),
            laengeZehntel = p.getInt(KEY_LAENGE_ZEHNTEL, 0),
            uebernommenAm = if (p.contains(KEY_UEBERNOMMEN_AM)) p.getLong(KEY_UEBERNOMMEN_AM, 0L) else null
        )
    }

    /** `commit()` statt `apply()`: der Aufrufer zeigt "übernommen" erst, wenn es auch stimmt. */
    fun schreibe(context: Context, standort: GespeicherterStandort): Boolean =
        prefs(context).edit()
            .putInt(KEY_BREITE_ZEHNTEL, standort.breiteZehntel)
            .putInt(KEY_LAENGE_ZEHNTEL, standort.laengeZehntel)
            .putLong(KEY_UEBERNOMMEN_AM, standort.uebernommenAm)
            .commit()

    fun loesche(context: Context): Boolean = prefs(context).edit().clear().commit()

    /**
     * Gespeicherte Rohwerte -> Standort. Fehlt eine Koordinate oder liegt sie ausserhalb der
     * Erdkugel (von Hand bearbeitet, kaputt), gibt es KEINEN Standort - die Kette faellt auf PLZ
     * oder Zeitzone durch, statt mit einem unsinnigen Ort Sonnenzeiten zu rechnen.
     */
    internal fun ausWerten(breiteZehntel: Int?, laengeZehntel: Int?, uebernommenAm: Long?): GespeicherterStandort? {
        if (breiteZehntel == null || laengeZehntel == null) return null
        if (breiteZehntel !in -900..900 || laengeZehntel !in -1800..1800) return null
        return GespeicherterStandort(breiteZehntel, laengeZehntel, uebernommenAm ?: 0L)
    }

    /** Der [StandortSpeicher] fuer das Repository - haelt nur den Context, liest beim Bau nichts. */
    internal fun speicher(context: Context): StandortSpeicher = object : StandortSpeicher {
        override fun lies() = lies(context)
        override fun schreibe(standort: GespeicherterStandort) = schreibe(context, standort)
        override fun loesche() = loesche(context)
    }
}
