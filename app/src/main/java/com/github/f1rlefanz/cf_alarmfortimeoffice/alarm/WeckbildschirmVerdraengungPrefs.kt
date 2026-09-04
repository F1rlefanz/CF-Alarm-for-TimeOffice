package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Gedaechtnis dafuer, dass der Weckbildschirm beim Klingeln verdraengt wurde.
 *
 * WOFUER: Auf dem Fairphone 6 (Android 16) startet die herstellereigene Gesichtsentsperrung
 * `com.android.settings/.anc.unlock.UnlockActivity` als gewoehnliche Activity rund 100 ms nach
 * dem Weckbildschirm und draengt ihn hinter den Sperrbildschirm. Der Wecker klingelt weiter, hat
 * aber keine Bedienoberflaeche mehr, bis der Nutzer selbst entsperrt. Am 29.08.2026 mit der
 * vorinstallierten Google Uhr gegengeprueft - es trifft JEDE Wecker-App auf diesem Geraet, ist
 * also ein Geraetedefekt und nicht unserer.
 *
 * WARUM DER FULL-SCREEN-INTENT NICHT NACHGEREICHT WIRD: Vier Messlaeufe haben belegt, dass er sich
 * nicht nachreichen laesst - weder ueber eine zweite Notification (auch nicht mit Verzoegerung 0)
 * noch als Update der bestehenden. Das System wertet ihn ausschliesslich beim ERSTEN Posten aus.
 *
 * DER SATZ "ES GIBT APP-SEITIG NICHTS ZU GEWINNEN" STAND HIER UND WAR FALSCH (korrigiert am
 * 04.09.2026). Er galt fuer das NACHREICHEN - nicht fuer den Zeitpunkt des ersten Postens. Genau
 * dort setzt [VorweckEntscheidung] an: erst den Bildschirm selbst wecken, die Gesichtsentsperrung
 * vorbeiziehen lassen und die Notification 600 ms spaeter posten. Diese Datei haelt deshalb ZWEI
 * Merker: den Zaehler fuer den Hinweis (zuruecksetzbar) und [jeVerdraengt] als Gate fuer das
 * Vorwecken (bleibend). Warum das getrennt sein MUSS, steht bei `KEY_JE_VERDRAENGT`.
 *
 * WARUM SharedPreferences UND NICHT DataStore: Geschrieben wird aus `onStop` der
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmFullScreenActivity] - einem
 * Lebenszyklus-Callback, dem der Prozesstod unmittelbar folgen kann. Dieselbe Ueberlegung wie
 * beim Schlummer-Merker: synchron mit `commit()`, nicht `apply()`.
 *
 * WARUM EIN ZAEHLER UND KEIN FLAG: Ein einzelner Aussetzer soll den Nutzer nicht behelligen.
 * Erst [SCHWELLE] aufeinanderfolgende Weckvorgaenge machen daraus einen Zustand, den er kennen
 * sollte. Und weil [meldeSauberenLauf] bei jedem unauffaelligen Wecker zurueckstellt, verschwindet
 * der Hinweis von allein, sobald es aufhoert - etwa weil der Nutzer die Gesichtsentsperrung
 * entfernt oder Fairphone es repariert.
 */
object WeckbildschirmVerdraengungPrefs {

    private const val PREFS_NAME = "weckbildschirm_verdraengung"
    private const val KEY_ANZAHL_IN_FOLGE = "anzahl_in_folge"

    /**
     * Wurde auf DIESEM Geraet jemals eine Verdraengung gemessen?
     *
     * ZWEI FRAGEN, ZWEI MERKER - und das ist der Kern: [KEY_ANZAHL_IN_FOLGE] beantwortet "passiert
     * es GERADE" (fuer den Hinweis, deshalb bei jedem sauberen Wecker zurueckgestellt), dieser hier
     * beantwortet "ist dieses GERAET betroffen" (fuer das Vorwecken, deshalb bleibend).
     *
     * WARUM DAS NOETIG WURDE - am Geraet gemessen, 04.09.2026: mit dem Zaehler als Gate schaltete
     * sich das Vorwecken durch seinen eigenen Erfolg ab. Der geschuetzte Lauf um 16:42 war sauber,
     * [meldeSauberenLauf] setzte den Zaehler auf 0 - und der naechste Wecker waere wieder ungeschuetzt
     * gewesen, also verdraengt, Zaehler 1, der uebernaechste wieder geschuetzt. Jeder ZWEITE Wecker
     * ohne Bedienoberflaeche, dauerhaft.
     */
    private const val KEY_JE_VERDRAENGT = "je_verdraengt"

    /**
     * Ab wie vielen Weckvorgaengen in Folge der Hinweis erscheint.
     *
     * Zwei, nicht eins: der erste Fall kann ein Zufall sein (ein Systemdialog, ein eingehender
     * Anruf). Zwei in Folge sind es nicht mehr. Dieselbe Ueberlegung wie bei der Warnung ueber
     * einen dauerhaft unerreichbaren Kalender, die auch erst beim zweiten Lauf greift.
     */
    const val SCHWELLE = 2

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Ein Weckvorgang, bei dem der Weckbildschirm verdraengt wurde. */
    fun zaehleVerdraengung(context: Context) {
        try {
            val neu = anzahlInFolge(context) + 1
            prefs(context).edit()
                .putInt(KEY_ANZAHL_IN_FOLGE, neu)
                .putBoolean(KEY_JE_VERDRAENGT, true)
                .commit()
            Logger.w(
                LogTags.ALARM,
                "Weckbildschirm verdraengt - $neu. Mal in Folge (Hinweis ab $SCHWELLE)"
            )
        } catch (e: Exception) {
            // Folgenlos: der Hinweis erscheint dann eben nicht. Der Wecker selbst haengt nicht
            // daran, und ein Absturz im onStop des Weckbildschirms waere ungleich schlimmer.
            Logger.e(LogTags.ALARM, "Verdraengungs-Zaehler nicht schreibbar", e)
        }
    }

    /**
     * Ein Weckvorgang, bei dem der Weckbildschirm stehen geblieben ist - der Zaehler faellt auf 0.
     *
     * Bewusst hart zurueckgestellt statt heruntergezaehlt: Der Hinweis behauptet einen ZUSTAND
     * ("auf diesem Geraet passiert das"), nicht eine Statistik. Sobald ein Wecker sauber
     * durchlaeuft, stimmt die Behauptung nicht mehr.
     *
     * [KEY_JE_VERDRAENGT] bleibt dabei ausdruecklich STEHEN - der saubere Lauf ist ja in der Regel
     * das Verdienst des Vorweckens. Wer ihn hier mit zuruecksetzt, schaltet den Schutz durch seinen
     * eigenen Erfolg ab (am 04.09.2026 genau so gemessen).
     */
    fun meldeSauberenLauf(context: Context) {
        try {
            if (anzahlInFolge(context) == 0) return
            prefs(context).edit().putInt(KEY_ANZAHL_IN_FOLGE, 0).commit()
            Logger.i(LogTags.ALARM, "Weckbildschirm blieb stehen - Verdraengungs-Zaehler zurueckgesetzt")
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Verdraengungs-Zaehler nicht zuruecksetzbar", e)
        }
    }

    fun anzahlInFolge(context: Context): Int = try {
        prefs(context).getInt(KEY_ANZAHL_IN_FOLGE, 0)
    } catch (e: Exception) {
        // Degradation bewusst nach UNTEN: im Zweifel KEIN Hinweis. Ein faelschlich gezeigter
        // Hinweis wuerde dem Nutzer raten, seine Gesichtsentsperrung zu entfernen - das darf
        // nicht aus einem Lesefehler folgen.
        Logger.e(LogTags.ALARM, "Verdraengungs-Zaehler nicht lesbar - gilt als 0", e)
        0
    }

    /** Soll der Hinweis angezeigt werden? */
    fun hinweisFaellig(context: Context): Boolean = anzahlInFolge(context) >= SCHWELLE

    /**
     * Ist dieses Geraet von der Verdraengung betroffen? Gate fuer das Vorwecken
     * ([com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.VorweckEntscheidung]).
     *
     * Bleibend, im Gegensatz zu [hinweisFaellig] - siehe [KEY_JE_VERDRAENGT]. Der Oder-Zweig ueber
     * den Zaehler ist die Migration fuer Bestandsgeraete: dort steht der Zaehler schon, den neuen
     * Merker gibt es noch nicht, und ohne ihn muesste erst wieder ein Wecker verdraengt werden,
     * bevor der Schutz greift.
     *
     * Degradation nach UNTEN wie beim Zaehler: ein Lesefehler heisst "nicht betroffen" und damit
     * unveraendertes Verhalten. Ein faelschlich eingeschaltetes Vorwecken kostet zwar nur 600 ms,
     * aber es soll aus einer Messung folgen, nicht aus einer Panne.
     */
    fun jeVerdraengt(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_JE_VERDRAENGT, false) || anzahlInFolge(context) >= 1
    } catch (e: Exception) {
        Logger.e(LogTags.ALARM, "Verdraengungs-Merker nicht lesbar - gilt als NICHT betroffen", e)
        false
    }
}
