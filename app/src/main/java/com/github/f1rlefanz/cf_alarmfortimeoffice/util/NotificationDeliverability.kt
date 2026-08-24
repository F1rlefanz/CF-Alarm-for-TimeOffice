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
     * Kanal-ID des Wecker-Vordergrunddienstes - VERSIONIERT, und das ist der ganze Witz daran.
     *
     * WARUM DAS "_v2" DRANHAENGT: Bis v1.9.7 legte `AlarmSoundService` seinen Kanal mit
     * IMPORTANCE_LOW an. Der Umstieg auf IMPORTANCE_HIGH samt `setBypassDnd(true)` und
     * `VISIBILITY_PUBLIC` geschah unter DERSELBEN ID - und lief damit ins Leere: laut
     * `NotificationManager.createNotificationChannel` wird die Importance eines BESTEHENDEN Kanals
     * "only be changed if the new importance is lower than the current value", und "all other
     * fields are ignored for channels that already exist". Auf jeder Installation, die vor v1.9.7
     * einmal geweckt hat, stand der Wecker-Kanal deshalb bis v1.29.0 weiter auf LOW - das System
     * ignoriert den Full-Screen-Intent, der Wecker klingelt ohne Weck-Bildschirm und ohne Stopp-
     * und Schlummer-Knopf.
     *
     * WARUM NICHT LOESCHEN UND UNTER DERSELBEN ID NEU ANLEGEN: Android stellt einen geloeschten
     * Kanal bei Wiederanlage mit genau seinen alten Einstellungen wieder her
     * ("the deleted channel will be un-deleted with all of the same settings it had before").
     * Nur eine NEUE ID entkommt dem Altbestand. Die alte wird zusaetzlich einmalig geloescht,
     * damit in den Systemeinstellungen kein toter Zwilling stehenbleibt - siehe
     * [ALTE_WECKER_KANAL_ID].
     *
     * ACHTUNG, ZWEITE STELLE: `AlarmSoundService` haelt dieselbe ID als privates `CHANNEL_ID`.
     * Sie laesst sich von dort nicht lesen (private companion const), deshalb steht sie hier
     * erneut - und deshalb prueft `NotificationDeliverabilityTest` die Quelldatei des Service auf
     * genau diesen Literal. Wer die ID dort aendert, faellt im Test auf, nicht am Wecktag.
     */
    const val WECKER_KANAL_ID = "alarm_sound_service_v2"

    /**
     * Die abgeloeste, unversionierte Kanal-ID. Sie wird nur noch geloescht, nie wieder bespielt.
     *
     * Dieselbe Falle steht unversioniert weiterhin an fuenf anderen Stellen: `alarm_notausgang`
     * (`AlarmReceiver`), die beiden Kanaele in `AlarmMaintenanceService`, sowie
     * `CalendarUnavailableNotifier` und `ShiftChangeNotifier`. Heute unschaedlich, weil sie jung
     * sind und nie mit niedrigerer Importance ausgeliefert wurden - aber JEDE kuenftige Aenderung
     * an deren Importance oder Sichtbarkeit verpufft auf Bestandsgeraeten still. Wer dort etwas
     * anfasst, versioniert die ID gleich mit.
     */
    const val ALTE_WECKER_KANAL_ID = "alarm_sound_service"

    /** Entspricht `NotificationManager.IMPORTANCE_NONE` - hier ohne Android-Bezug, s. [beurteile]. */
    const val WICHTIGKEIT_KEINE = 0

    /** Entspricht `NotificationManager.IMPORTANCE_LOW` - lautlos, kein Heads-up, kein Vollbild. */
    const val WICHTIGKEIT_NIEDRIG = 2

    /**
     * Entspricht `NotificationManager.IMPORTANCE_DEFAULT` - macht ein Geraeusch, blendet aber
     * nichts ein und traegt damit KEINEN Full-Screen-Intent. Hier keinen Oberflaechennamen
     * anschreiben: welchen Namen die Stufe traegt, wechselt mit der Android-Version (auf 8/9
     * heisst genau dieser Wert "Hoch"), siehe [mindeststufeBeschreibung].
     */
    const val WICHTIGKEIT_STANDARD = 3

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
     * @param kanalWichtigkeit Importance des Kanals, oder [KANAL_FEHLT], wenn es ihn (noch) nicht
     *   gibt.
     * @param gruppeGesperrt `true`, wenn der Kanal in einer vom Nutzer gesperrten Gruppe liegt.
     * @param mindestwichtigkeit Ab welcher Importance die Meldung ihren Zweck erfuellt. Fuer den
     *   Wecker [WICHTIGKEIT_HOCH] (darunter ignoriert das System den Full-Screen-Intent), sonst
     *   genuegt "ueberhaupt an".
     */
    fun beurteile(
        appErlaubt: Boolean,
        kanalWichtigkeit: Int,
        gruppeGesperrt: Boolean,
        mindestwichtigkeit: Int = WICHTIGKEIT_KEINE + 1
    ): Zustellbarkeit = when {
        !appErlaubt -> Zustellbarkeit.APP_BLOCKIERT
        // Ein fehlender Kanal ist kein blockierter: er entsteht beim ersten Post.
        kanalWichtigkeit == KANAL_FEHLT -> Zustellbarkeit.ERREICHBAR
        kanalWichtigkeit <= WICHTIGKEIT_KEINE -> Zustellbarkeit.KANAL_BLOCKIERT
        gruppeGesperrt -> Zustellbarkeit.GRUPPE_BLOCKIERT
        kanalWichtigkeit < mindestwichtigkeit -> Zustellbarkeit.KANAL_LEISE
        else -> Zustellbarkeit.ERREICHBAR
    }

    /**
     * Die Anweisung fuer den Fall, dass der Kanal Heads-up und Vollbild tragen muss.
     *
     * Bewusst als WIRKUNG formuliert und nur mit hedgenden Namensbeispielen: welchen Namen die
     * oberste Stufe traegt, entscheidet die Android-Version - "oberste Stufe der Liste" gilt
     * ueberall, ein Name nicht.
     */
    const val BESCHREIBUNG_OBERSTE_STUFE: String =
        "auf der obersten Stufe der Wichtigkeit stehen — der, bei der die Meldung auf dem " +
            "Bildschirm eingeblendet wird (je nach Android-Version heißt sie \"Dringend\"; auf " +
            "manchen Geräten ist es stattdessen der Schalter \"Auf dem Bildschirm einblenden\")"

    /** Die Anweisung fuer alle uebrigen Kanaele: dort genuegt "ueberhaupt an". */
    const val BESCHREIBUNG_EINGESCHALTET: String = "überhaupt eingeschaltet sein"

    /**
     * Was der Nutzer einstellen muss, damit [beurteile] mit dieser Mindestwichtigkeit durchlaesst.
     *
     * WARUM EINE WIRKUNG STATT EINES STUFENNAMENS: Bis v1.29.0 stand hier eine handgeschriebene
     * Namenstabelle (2 -> "Lautlos", 3 -> "Standard", 4 -> "Hoch"). Die Zahl kam zwar aus dem
     * Praedikat, der NAME aber aus dem Nichts - und er war falsch: In der deutschen
     * Wichtigkeitsliste von Android 8/9 heisst "Hoch" genau IMPORTANCE_DEFAULT, also der Wert,
     * den dieselbe Karte als zu niedrig verwirft; auf neueren Versionen gibt es den Eintrag gar
     * nicht. Wer der Anweisung folgte, blieb in [Zustellbarkeit.KANAL_LEISE] und sah unveraendert
     * das Warndreieck - der Wecker klingelt dann ohne Weck-Bildschirm. Die Verzweigung faellt
     * weiterhin aus [beurteile], nur der Text beschreibt jetzt das, was der Nutzer in JEDER
     * Android-Version wiederfindet.
     */
    fun mindeststufeBeschreibung(mindestwichtigkeit: Int): String {
        val standardGenuegt = beurteile(
            appErlaubt = true,
            kanalWichtigkeit = WICHTIGKEIT_STANDARD,
            gruppeGesperrt = false,
            mindestwichtigkeit = mindestwichtigkeit
        ).erreicht
        return if (standardGenuegt) BESCHREIBUNG_EINGESCHALTET else BESCHREIBUNG_OBERSTE_STUFE
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
            kanalWichtigkeit = kanal?.importance ?: KANAL_FEHLT,
            gruppeGesperrt = gruppeGesperrt,
            mindestwichtigkeit = mindestwichtigkeit
        )
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
