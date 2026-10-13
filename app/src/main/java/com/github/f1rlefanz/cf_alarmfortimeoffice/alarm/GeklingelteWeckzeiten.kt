package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import android.content.SharedPreferences
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Die Weckzeiten der letzten Tage, an denen wirklich ein Wecker GEKLINGELT hat.
 *
 * WOFUER (Fairphone, 13.10.2026): Ein Dimm-Fenster mit dem Ende „bis zur Weckzeit, spätestens um X“
 * sucht seine Weckzeit in einer Zeitleiste aus Schichtspannen und Alarm-Bestand. Schichtspannen
 * überleben die Weckzeit (drei Tage Rückschau im `ShiftSpanStore`), ein MANUELLER Wecker nicht:
 * der Alarm-Bestand wirft beim Laden alles Verstrichene weg. Jede Neuberechnung zwischen Weckzeit
 * und Schranke - an dem Morgen das Auto-Update um 05:36 - öffnete das Nachtfenster deshalb erneut
 * bis 07:00, obwohl der Wecker um 05:30 geklingelt hatte. Mit diesem Merker verhält sich ein
 * manueller Wecker wie jeder Schichtwecker.
 *
 * GEMERKT WIRD NUR, WAS AUSGELÖST HAT: geschrieben vom `AlarmReceiver` NACH der Skip-Prüfung. Ein
 * übersprungener, freigegebener oder vorher gelöschter Wecker steht deshalb nie hier - ein Merker
 * beim Stellen würde das Fenster auch an einem Morgen beenden, an dem gar nichts klingelte.
 * Schichtwecker (auch stille) landen ebenfalls hier; das ist harmlos, ihre Weckzeit steht ohnehin
 * in der Schichtspanne. Ein Schlummern, das über denselben Receiver feuert, schreibt mit - die
 * frühere, echte Weckzeit derselben Nacht gewinnt trotzdem, weil das Fenster die FRÜHESTE nimmt.
 *
 * WARUM DEVICE-PROTECTED SHAREDPREFERENCES: der Receiver feuert auch vor der ersten Entsperrung
 * (Direct Boot), und genau dann ist nur dieser Speicher beschreibbar. Synchron, eigene Datei,
 * gleiches Muster wie `WartungsKettenTermin`. Die Datei liegt unter `/data/user_de/` und reist
 * damit nicht per Backup auf ein anderes Gerät.
 *
 * JEDER ZUGRIFF WIRFT NIE: Lesefehler = „nichts gemerkt“ (dann gilt das Verhalten von vor dem Fix),
 * Schreibfehler = nur ein Log. Der Wecker selbst hängt an nichts hiervon.
 *
 * Bewusst ein `object` ohne Property-Initializer: nichts hiervon wird beim Bauen des
 * Application-Graphen berührt (CLAUDE.md, Direct Boot).
 */
object GeklingelteWeckzeiten {

    /** Dateiname (ohne `.xml`) im Device-Protected Storage. */
    const val DATEI = "geklingelte_weckzeiten"

    private const val SCHLUESSEL = "zeiten"

    /** Dieselbe Rückschau wie die Schichtspannen - beide Quellen sollen sich gleich verhalten. */
    const val RUECKSCHAU_MS = ShiftSpanStore.RETENTION_MS

    /** Deckel gegen unbegrenztes Wachsen (Schlummern schreibt mit); drei Tage brauchen weit weniger. */
    internal const val MAX_EINTRAEGE = 32

    private val sperre = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DATEI, Context.MODE_PRIVATE)

    /** Reine Funktion: neue Liste nach dem Merken von [zeitpunkt], gekürzt auf die Rückschau. */
    internal fun mitNeuemEintrag(alt: List<Long>, zeitpunkt: Long, jetzt: Long): List<Long> =
        behalte(alt + zeitpunkt, jetzt).takeLast(MAX_EINTRAEGE)

    /** Reine Funktion: was innerhalb der Rückschau liegt, aufsteigend und ohne Dubletten. */
    internal fun behalte(zeiten: List<Long>, jetzt: Long): List<Long> =
        zeiten.filter { it > jetzt - RUECKSCHAU_MS }.distinct().sorted()

    internal fun kodiere(zeiten: List<Long>): String = zeiten.joinToString(",")

    internal fun dekodiere(text: String?): List<Long> =
        text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }

    /**
     * Auf die volle Minute abgerundet: Weckzeiten sind ganze Minuten (`HH:mm`), der Broadcast kommt
     * aber ein paar Millisekunden bis Sekunden danach. Ungerundet verschob sich das Fensterende
     * beim Herausfallen des Weckers aus dem Bestand - und mit ihm die Fenster-Identitaet, an der
     * ein Korrektur-Override haengt (`isOverrideStale`); ein „Heller/Dunkler" im Nachlauf waere
     * dann mitten darin verworfen worden.
     */
    internal fun aufMinute(zeitpunkt: Long): Long = zeitpunkt - Math.floorMod(zeitpunkt, 60_000L)

    /** Merkt [zeitpunkt] (abgerundet, siehe [aufMinute]) als Weckzeit, an der gerade ein Wecker klingelt. */
    fun merke(context: Context, zeitpunkt: Long = System.currentTimeMillis()) {
        try {
            synchronized(sperre) {
                val p = prefs(context)
                val neu = mitNeuemEintrag(dekodiere(p.getString(SCHLUESSEL, null)), aufMinute(zeitpunkt), zeitpunkt)
                // commit(), nicht apply(): der Receiver-Prozess kann direkt nach dem Wecken enden,
                // und der Merker muss die nächste Fensterberechnung erreichen.
                p.edit().putString(SCHLUESSEL, kodiere(neu)).commit()
            }
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM_RECEIVER, "Geklingelte Weckzeit nicht gemerkt - ein Dimm-Fenster kann danach bis zur Uhrzeit-Grenze laufen", e)
        }
    }

    /** Die gemerkten Weckzeiten innerhalb der Rückschau; bei jedem Fehler leer. */
    fun lies(context: Context, jetzt: Long = System.currentTimeMillis()): List<Long> =
        try {
            behalte(dekodiere(prefs(context).getString(SCHLUESSEL, null)), jetzt)
        } catch (e: Exception) {
            Logger.w(LogTags.DIMMER, "Geklingelte Weckzeiten nicht lesbar - Fenster enden nur an bekannten Weckern", e)
            emptyList()
        }
}
