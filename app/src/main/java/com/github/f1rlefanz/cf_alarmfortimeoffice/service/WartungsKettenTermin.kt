package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Der gemerkte Termin des naechsten REGULAEREN Laufs der 6h-Wartungskette.
 *
 * WOFUER: Bis 1.47.1 stellte jeder Eintritt in den Hauptbereich die Kette auf "jetzt + 6 h" -
 * wer oft nachsah, schob den faelligen Lauf immer weiter vor sich her (12.10.2026 am Fairphone:
 * der 20:17-Lauf kam nie, der naechste stand auf Wiedereintritt 19:29:36 + 6 h). Um den Termin
 * BEHALTEN zu koennen, muss man ihn kennen: der AlarmManager verraet einen gestellten Zeitpunkt
 * nicht. Zweiter Leser ist die Status-Karte "Hintergrunddienst" ("Naechster Abgleich im
 * Hintergrund"), damit die Kette eine Oberflaeche hat.
 *
 * WARUM DEVICE-PROTECTED SHAREDPREFERENCES und kein DataStore: [AlarmMaintenanceService.scheduleNext]
 * laeuft auch aus dem `BootReceiver` VOR der ersten Entsperrung - dort ist nur der
 * Device-Protected Storage les- und schreibbar, und ein CE-DataStore lieferte sogar still "leer"
 * und hielte das fuer den ganzen Prozess fest (Skill cfalarm-persistenz-und-auth). Ausserdem ist
 * `scheduleNext()` synchron; SharedPreferences sind es auch. Eigene Datei, damit nichts anderes
 * mitgeschrieben wird.
 *
 * BACKUP: Die Datei liegt unter `/data/user_de/` und damit ausserhalb der Domain `sharedpref`,
 * die `backup_rules.xml`/`data_extraction_rules.xml` einschliessen (wie `direct_boot_alarms.xml`).
 * Sie reist also nicht auf ein neues Geraet - richtig so, ein fremder Termin waere dort falsch.
 *
 * JEDER ZUGRIFF WIRFT NIE: Der Merker ist Hilfsinformation. Ein Lesefehler heisst "kein Termin
 * gemerkt" (dann gilt wie frueher jetzt + 6 h), ein Schreibfehler nur ein Log. Wichtig auch fuer
 * die JVM-Tests, die `scheduleNext()`/`cancelNext()` gegen einen gemockten Context laufen lassen:
 * dort liefert `createDeviceProtectedStorageContext()` null, und das darf das Stellen bzw. Kappen
 * der Kette nicht verhindern.
 *
 * Bewusst ein `object` mit Funktionen und OHNE Property-Initializer: nichts hiervon wird beim
 * Bauen des Application-Graphen beruehrt (CLAUDE.md, Direct Boot).
 */
internal object WartungsKettenTermin {

    /** Dateiname (ohne `.xml`) im Device-Protected Storage. */
    const val DATEI = "wartung_kette"

    private const val SCHLUESSEL_REGULAER = "naechster_regulaerer_lauf"

    private fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DATEI, Context.MODE_PRIVATE)

    /** Der gemerkte Termin (Epoch-Millis), oder `null`, wenn keiner da oder nicht lesbar ist. */
    fun lies(context: Context): Long? =
        try {
            prefs(context).getLong(SCHLUESSEL_REGULAER, 0L).takeIf { it > 0L }
        } catch (e: Exception) {
            Logger.w(LogTags.MAINTENANCE, "Termin der Wartungskette nicht lesbar - es gilt jetzt + 6 h", e)
            null
        }

    /**
     * Merkt den soeben GESTELLTEN Termin. Erst nach dem Stellen aufrufen: ein Merker ohne
     * gestellten Alarm wuerde die Anzeige und das spaetere Behalten auf einen Lauf verweisen, den
     * es nicht gibt.
     */
    fun merke(context: Context, termin: Long) {
        try {
            // apply() genuegt: dieselbe Instanz liest den Wert sofort aus dem Speicher, und
            // Android wartet beim Beenden eines Dienstes auf ausstehende Schreibvorgaenge. Ein
            // verlorener Wert waere ein aelterer Termin - schlimmstenfalls kaeme der Lauf frueher.
            prefs(context).edit { putLong(SCHLUESSEL_REGULAER, termin) }
        } catch (e: Exception) {
            Logger.w(LogTags.MAINTENANCE, "Termin der Wartungskette nicht gemerkt - nur die Anzeige fehlt", e)
        }
    }

    /** Vergisst den Termin - die Kette ist gekappt (Master-Pause), es gibt keinen naechsten Lauf. */
    fun vergiss(context: Context) {
        try {
            prefs(context).edit { remove(SCHLUESSEL_REGULAER) }
        } catch (e: Exception) {
            Logger.w(LogTags.MAINTENANCE, "Termin der Wartungskette nicht geloescht", e)
        }
    }
}
