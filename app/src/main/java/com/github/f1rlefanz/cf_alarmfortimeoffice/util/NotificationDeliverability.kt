package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationManagerCompat

/**
 * Beantwortet die Frage, die diese App bisher nur zur HAELFTE gestellt hat: kann eine
 * Benachrichtigung DIESES Kanals den Nutzer wirklich erreichen?
 *
 * WARUM ES DAS GIBT: Bis v1.26.2 prueften alle Stellen ausschliesslich
 * [NotificationManagerCompat.areNotificationsEnabled] - also die APP-Ebene. Ein
 * `getNotificationChannel(...)` gab es nirgends im Produktivcode. Android bietet dem Nutzer aber
 * direkt aus einer Benachrichtigung heraus einen Zwei-Tipp-Weg, einen EINZELNEN Kanal
 * abzuschalten oder auf "Lautlos" (IMPORTANCE_LOW) herunterzustufen. Genau danach verliert der
 * Wecker-Kanal Heads-up UND Full-Screen-Intent - der Wecker klingelt, aber der Weck-Bildschirm
 * kommt nie hoch -, waehrend `areNotificationsEnabled()` weiterhin `true` liefert und die
 * Status-Karte "Erlaubt" meldet. Projektregel: "Blockierte Benachrichtigungen sind ein Wecker
 * ohne Oberflaeche".
 *
 * DEGRADATIONSRICHTUNG: im Zweifel [Zustellbarkeit.ERREICHBAR]. Ein nicht existierender Kanal
 * ist ausdruecklich NICHT blockiert - er entsteht beim ersten `createNotificationChannel()`, und
 * wer daraus "unzustellbar" machte, wuerde die allererste Meldung jeder Art unterdruecken.
 * Lieber einmal zu viel warnen als still zu sein. Die einzige Ausnahme steht in
 * `CalendarUnavailableNotifier`: der "habe ich schon gesagt"-Merker wird erst nach einem
 * tatsaechlich durchgelaufenen `notify()` geschrieben.
 *
 * Die Entscheidung selbst ist eine reine Funktion ([beurteile]) und ohne Android testbar; der
 * Android-Teil ([bestimme]) liest nur die drei Eingaben zusammen.
 */
object NotificationDeliverability {

    /**
     * Kanal-ID des Wecker-Vordergrunddienstes.
     *
     * ACHTUNG, ZWEITE STELLE: `AlarmSoundService` haelt dieselbe ID als privates `CHANNEL_ID`.
     * Sie laesst sich von dort nicht lesen (private companion const), deshalb steht sie hier
     * erneut - und deshalb prueft `NotificationDeliverabilityTest` die Quelldatei des Service auf
     * genau diesen Literal. Wer die ID dort aendert, faellt im Test auf, nicht am Wecktag.
     */
    const val WECKER_KANAL_ID = "alarm_sound_service"

    /** Entspricht `NotificationManager.IMPORTANCE_NONE` - hier ohne Android-Bezug, s. [beurteile]. */
    const val WICHTIGKEIT_KEINE = 0

    /** Entspricht `NotificationManager.IMPORTANCE_HIGH`: Voraussetzung fuer Heads-up und Vollbild. */
    const val WICHTIGKEIT_HOCH = 4

    /** Kanal existiert nicht (noch nicht angelegt) - vgl. [beurteile]. */
    const val KANAL_FEHLT = -1000

    enum class Zustellbarkeit {
        /** Die Meldung erreicht den Nutzer wie vorgesehen. */
        ERREICHBAR,

        /** Benachrichtigungen sind fuer die GANZE App aus - kein Kanal hilft dagegen. */
        APP_BLOCKIERT,

        /** Nur dieser Kanal ist aus (IMPORTANCE_NONE). */
        KANAL_BLOCKIERT,

        /** Die Kanalgruppe, in der der Kanal liegt, ist gesperrt. */
        GRUPPE_BLOCKIERT,

        /**
         * Der Kanal ist an, aber unterhalb der geforderten Wichtigkeit - die Meldung erscheint
         * lautlos in der Leiste. Fuer den Wecker heisst das: kein Heads-up, kein Vollbild.
         */
        KANAL_LEISE;

        val erreicht: Boolean get() = this == ERREICHBAR
    }

    /**
     * PURE, TESTBAR: Was ist aus diesen drei Beobachtungen zu schliessen?
     *
     * @param appErlaubt Ergebnis von `areNotificationsEnabled()`.
     * @param kanaeleUnterstuetzt `false` vor API 26 - dort gibt es keine Kanaele, und die
     *   App-Ebene ist die ganze Wahrheit.
     * @param kanalWichtigkeit Importance des Kanals, oder [KANAL_FEHLT], wenn es ihn (noch) nicht
     *   gibt.
     * @param gruppeGesperrt `true`, wenn der Kanal in einer vom Nutzer gesperrten Gruppe liegt.
     * @param mindestwichtigkeit Ab welcher Importance die Meldung ihren Zweck erfuellt. Fuer den
     *   Wecker [WICHTIGKEIT_HOCH] (darunter ignoriert das System den Full-Screen-Intent), sonst
     *   genuegt "ueberhaupt an".
     */
    fun beurteile(
        appErlaubt: Boolean,
        kanaeleUnterstuetzt: Boolean,
        kanalWichtigkeit: Int,
        gruppeGesperrt: Boolean,
        mindestwichtigkeit: Int = WICHTIGKEIT_KEINE + 1
    ): Zustellbarkeit = when {
        !appErlaubt -> Zustellbarkeit.APP_BLOCKIERT
        !kanaeleUnterstuetzt -> Zustellbarkeit.ERREICHBAR
        // Ein fehlender Kanal ist kein blockierter: er entsteht beim ersten Post.
        kanalWichtigkeit == KANAL_FEHLT -> Zustellbarkeit.ERREICHBAR
        kanalWichtigkeit <= WICHTIGKEIT_KEINE -> Zustellbarkeit.KANAL_BLOCKIERT
        gruppeGesperrt -> Zustellbarkeit.GRUPPE_BLOCKIERT
        kanalWichtigkeit < mindestwichtigkeit -> Zustellbarkeit.KANAL_LEISE
        else -> Zustellbarkeit.ERREICHBAR
    }

    /**
     * Liest App-Ebene, Kanal und Kanalgruppe und gibt das Urteil zurueck.
     *
     * Jeder Lesefehler endet in [Zustellbarkeit.ERREICHBAR]: der Helfer darf nie der Grund sein,
     * dass eine Meldung ausbleibt.
     */
    fun bestimme(
        context: Context,
        kanalId: String,
        mindestwichtigkeit: Int = WICHTIGKEIT_KEINE + 1
    ): Zustellbarkeit = try {
        val appErlaubt = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            beurteile(appErlaubt, kanaeleUnterstuetzt = false, KANAL_FEHLT, false, mindestwichtigkeit)
        } else {
            val manager = context.getSystemService(NotificationManager::class.java)
            val kanal = manager?.getNotificationChannel(kanalId)
            val gruppeGesperrt = kanal?.group?.let { gruppenId ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    manager.getNotificationChannelGroup(gruppenId)?.isBlocked == true
                } else {
                    false
                }
            } == true
            beurteile(
                appErlaubt = appErlaubt,
                kanaeleUnterstuetzt = true,
                kanalWichtigkeit = kanal?.importance ?: KANAL_FEHLT,
                gruppeGesperrt = gruppeGesperrt,
                mindestwichtigkeit = mindestwichtigkeit
            )
        }
    } catch (e: Exception) {
        Logger.w(LogTags.SYSTEM, "Zustellbarkeit von '$kanalId' nicht pruefbar - nehme erreichbar an: ${e.message}")
        Zustellbarkeit.ERREICHBAR
    }

    /** Kurzform von [bestimme] fuer Aufrufer, die nur ja/nein brauchen. */
    fun kannZustellen(
        context: Context,
        kanalId: String,
        mindestwichtigkeit: Int = WICHTIGKEIT_KEINE + 1
    ): Boolean = bestimme(context, kanalId, mindestwichtigkeit).erreicht
}
