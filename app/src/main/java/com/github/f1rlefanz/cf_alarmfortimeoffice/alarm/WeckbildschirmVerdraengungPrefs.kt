package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger

/**
 * Gedaechtnis dafuer, dass der Weckbildschirm beim Klingeln verdraengt wurde (Gesichtsentsperrung
 * des Fairphone 6, trifft jede Wecker-App) - traegt nur den HINWEIS im Status-Tab, nicht das Gate
 * fuers Vorwecken ([VorweckEntscheidung]). **Wer hier wieder ein Gate einbaut, holt sich die Falle
 * zurueck: ein Merker, der im Direct Boot nicht lesbar ist, schuetzt ausgerechnet den Wecker nach
 * einem naechtlichen Neustart nicht.**
 *
 * SharedPreferences mit `commit()`, weil aus `onStop` der
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmFullScreenActivity] geschrieben wird und der
 * Prozesstod unmittelbar folgen kann. Zaehler statt Flag: erst [SCHWELLE] Verdraengungen in Folge
 * zeigen den Hinweis, [meldeSauberenLauf] stellt ihn zurueck.
 *
 * Hergang: .claude/skills/cfalarm-wecker-und-boot/reference/vorwecken.md.
 */
object WeckbildschirmVerdraengungPrefs {

    private const val PREFS_NAME = "weckbildschirm_verdraengung"
    private const val KEY_ANZAHL_IN_FOLGE = "anzahl_in_folge"

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
                .commit()
            Logger.w(
                LogTags.ALARM,
                "Weckbildschirm verdraengt - $neu. Mal in Folge (Hinweis ab $SCHWELLE)"
            )
        } catch (e: Exception) {
            // Folgenlos: kein Hinweis; der Wecker haengt nicht daran, ein Absturz in onStop schon.
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
     * Gefahrlos, seit der Zaehler kein Gate mehr fuers Vorwecken ist (1.39.5).
     */
    fun meldeSauberenLauf(context: Context) {
        try {
            if (anzahlInFolge(context) == 0) return
            prefs(context).edit()
                .putInt(KEY_ANZAHL_IN_FOLGE, 0)
                .commit()
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
}
