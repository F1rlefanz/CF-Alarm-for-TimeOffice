package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmReceiver
import com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftMatch
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.BatteryOptimizationHelper
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import dagger.hilt.android.EntryPointAccessors
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Ergebnis eines Schlummer-Versuchs — bewusst DREI Zustaende statt eines Boolean.
 *
 * WARUM: Bis zur Pruefrunde 8 gab [AlarmManagerService.armSnooze] ein `Boolean` zurueck, und
 * `scheduleSnooze()` verwarf es ersatzlos. Beide Nutzerpfade (Vollbild-Knopf und
 * Notification-Knopf) beendeten den Wecker danach UNBEDINGT: ein gescheitertes Schlummern sah
 * bitgenau aus wie ein erfolgreiches — Ton aus, Bildschirm zu, kein Wecker, kein Merker, nur eine
 * Zeile im Log. Wer "15 Min spaeter" gedrueckt hat, verschlief ohne jeden Hinweis.
 *
 * Drei Lagen sind zu unterscheiden, weil sie verschiedene Antworten verlangen - und jede traegt
 * ihre EIGENE Ueberschrift (siehe [SchlummerEntscheidung.TITEL_PAUSE]):
 * - [ABGELEHNT_PAUSE]: die Master-Pause laeuft. Das ist KEIN Defekt, sondern der gewollte
 *   Zustand — aber der Nutzer muss erfahren, dass er nicht wieder geweckt wird.
 * - [FEHLGESCHLAGEN]: die Planung selbst ist gescheitert (entzogene Exact-Alarm-Berechtigung auf
 *   API 31/32, Alarm-Obergrenze, DeadSystemException). Hier ist Weiterklingeln die richtige
 *   Antwort: ein Wecker, der laut bleibt, ist besser als einer, der lautlos verschwindet.
 * - [FEHLGESCHLAGEN_UNKLAR]: der Versuch ist gescheitert, NACHDEM der Alarm bereits armiert war,
 *   und der Rueckbau hat ihn nicht abgeraeumt - entweder weil er misslang oder weil er ihn
 *   bewusst stehen laesst (Wiederherstellung nach Neustart). Dann steht moeglicherweise doch ein
 *   Weckruf - und der Nutzertext muss das sagen duerfen, statt "es ist KEIN weiterer Weckruf
 *   geplant" zu behaupten. Ein falsches Versprechen in die andere Richtung waere genauso eine
 *   Luege, nur eine bequemere.
 */
enum class SnoozeErgebnis {
    GEPLANT,
    ABGELEHNT_PAUSE,
    FEHLGESCHLAGEN,
    FEHLGESCHLAGEN_UNKLAR
}

/**
 * Was der Rueckbau eines aufgegebenen Armierungsversuchs erreicht hat.
 *
 * WARUM DREI WERTE UND NICHT `Boolean`: Der Rueckbau darf nur wegraeumen, was DERSELBE Vorgang
 * gerade angelegt hat — und das ist je nach Anlass verschieden. Beim frischen Schlummern hat der
 * Vorgang den Alarm angelegt (also weg damit), bei der Wiederherstellung nach einem Neustart hat
 * er nur einen Alarm zu einem Merker-Eintrag nachgezogen, der schon vorher da war (also stehen
 * lassen). "Nicht abgeraeumt" und "bewusst behalten" sehen als `false` gleich aus, verlangen aber
 * verschiedene Log-Zeilen — und die eine Zeile ist der einzige Hinweis, den ein Vorfall spaeter
 * hinterlaesst.
 */
internal enum class RueckbauErgebnis {
    /** Der Alarm ist wieder weg; es steht nichts Scharfes mehr. */
    ABGERAEUMT,

    /** Der Rueckbau hat nicht funktioniert - der Alarm steht moeglicherweise noch. */
    MISSLUNGEN,

    /**
     * Es wurde bewusst nichts angeruehrt: der Alarm bleibt armiert und bleibt ueber den schon
     * vorher vorhandenen Merker abbrechbar. Fuer eine Wecker-App die richtige Richtung — er
     * klingelt.
     */
    BEWUSST_BEHALTEN
}

/**
 * Reine, Android-freie Entscheidungslogik des Schlummerns — bewusst als eigenes Top-Level-Objekt
 * (Vorbild: `MaintenanceLoadDecision`), damit Unit-Tests den Master-Pause-Backstop und die
 * Fehlerbehandlung ohne AlarmManager/PendingIntent pruefen koennen.
 */
internal object SchlummerEntscheidung {

    /** Loggt [AlarmManagerService.armSnooze], wenn die Master-Pause das Schlummern abweist. */
    const val MELDUNG_PAUSIERT =
        "⏸️ Schlummern abgelehnt - die Hintergrunddienste sind pausiert. Ein hier armierter " +
            "Wecker klingelte mitten in einer Pause und waere durch nichts mehr abzuraeumen " +
            "(die 6h-Kette ist beim Pausieren gekappt)."

    /**
     * Die Ueberschriften - eine je Fehlschlagsart, NICHT eine gemeinsame.
     *
     * WARUM JE ERGEBNIS EINE (Pruefrunde 8, Folgebefund zum unklaren Ausgang): Es gab einen
     * einzigen Titel "Kein Schlummer-Wecker gestellt", und er stand auch ueber [HINWEIS_UNKLAR].
     * Damit war die Luege wieder da, gegen die [SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR] ueberhaupt
     * eingefuehrt wurde - nur eine Zeile hoeher. Und sie stand ausgerechnet an der Stelle, die am
     * Sperrbildschirm im eingeklappten Zustand als EINZIGE gelesen wird, waehrend der Fliesstext
     * gekuerzt ist. Der Titel gehoert deshalb zum Ergebnis, nicht zur Renderstelle.
     *
     * KURZ HALTEN: eingeklappt bleibt nur rund eine Zeile stehen. Jeder Titel nennt die WIRKUNG
     * (weckt nicht / kein Weckruf / ungewiss), nicht die Ursache.
     */
    const val TITEL_PAUSE = "Pausiert - kein Weckruf mehr"

    const val TITEL_FEHLER = "Kein Schlummer-Wecker gestellt"

    const val TITEL_UNKLAR = "Schlummer-Wecker ungewiss"

    /**
     * Nutzertexte beschreiben die WIRKUNG, nicht den Namen einer Systemeinstellung — die
     * verschieben sich zwischen Android-Versionen.
     */
    const val HINWEIS_PAUSE =
        "Die Hintergrunddienste sind pausiert - solange weckt diese App nicht. Setze sie in der " +
            "App fort, wenn du wieder geweckt werden willst."

    const val HINWEIS_FEHLER =
        "Der Schlummer-Wecker liess sich nicht stellen. Es ist KEIN weiterer Weckruf geplant - " +
            "stelle dir bitte selbst einen."

    /**
     * Der ehrliche Text fuer den Fall, dass der Rueckbau des schon armierten Alarms misslungen ist.
     *
     * Er verspricht NICHTS - weder "kein Weckruf" (dann klingelte es unerwartet) noch "es klingelt"
     * (dann verliesse sich jemand darauf). Er sagt die Wirkung und was zu tun ist.
     */
    const val HINWEIS_UNKLAR =
        "Der Schlummer-Wecker liess sich nicht zuverlaessig stellen. Moeglicherweise klingelt es " +
            "spaeter trotzdem noch einmal - verlass dich nicht darauf und stelle dir bitte selbst " +
            "einen Wecker."

    /** Loggt den misslungenen Rueckbau eines bereits armierten Schlummer-Weckers. */
    const val MELDUNG_RUECKBAU_FEHLER =
        "⚠️ Der bereits armierte Schlummer-Wecker liess sich nicht wieder abbrechen - er kann " +
            "spaeter unerwartet feuern und ist ohne Merker durch nichts mehr abzuraeumen."

    /**
     * Loggt den bewusst stehen gelassenen Alarm eines Wiederherstellungslaufs.
     *
     * Klingt harmloser als [MELDUNG_RUECKBAU_FEHLER] und ist es auch: der Alarm steht, der Merker
     * steht, beides passt zusammen. Nur das Nachziehen des Merkers ist misslungen.
     */
    const val MELDUNG_ALARM_BLEIBT =
        "⚠️ Der Snooze-Merker liess sich beim Wiederherstellen nicht neu schreiben. Der Alarm " +
            "bleibt armiert und ueber den bestehenden Eintrag abbrechbar - er klingelt, aber die " +
            "Anzeigedaten koennen veraltet sein."

    /**
     * Der gemeinsame Kern jedes Armierens: erst der Master-Pause-Backstop, dann planen, dann
     * vormerken.
     *
     * ZENTRAL statt ein Gate je Aufrufer: das Schlummern war der EINZIGE Armierungspfad ohne
     * Master-Pause-Pruefung (jeder andere hat sie nachgeruestet), und es hat zwei gleichwertige
     * Ausloeser (Vollbild und Notification). Ein Gate pro Aufrufer haette denselben Fehler nur auf
     * zwei Stellen verteilt — dieselbe Ueberlegung wie beim Skip-Backstop in
     * `AlarmUseCase.scheduleSystemAlarm()`.
     *
     * REIHENFOLGE ist tragend: [merke] laeuft erst NACH [plane]. Der Merker ist die einzige Spur,
     * ueber die ein schwebender Schlummer spaeter abgebrochen oder nach einem Neustart
     * wiederhergestellt werden kann — ein Eintrag ohne Alarm waere eine Luege im Boot-Log.
     *
     * EIN AUFGEGEBENER VERSUCH HINTERLAESST NICHTS SCHARFES ([baueZurueck]): Scheitert erst das
     * Vormerken, ist der Alarm bereits armiert. Bis zur adversarialen Review der Pruefrunde 8
     * meldete diese Funktion dann schlicht [SnoozeErgebnis.FEHLGESCHLAGEN] und liess ihn stehen -
     * beide Aufrufer sagen dem Nutzer daraufhin ausdruecklich "Es ist KEIN weiterer Weckruf
     * geplant", waehrend der Schlummer scharf steht. Er feuert spaeter unerwartet, und weder
     * Master-Pause noch `deleteAllAlarms` noch das Abmelden erreichen ihn, weil genau der Merker
     * fehlt, ueber den sie ihn finden wuerden. Der Kommentar an dieser Stelle benannte die Gefahr
     * sogar - ein Kommentar, der die Gefahr benennt, ist kein Schutz. Deshalb laeuft [baueZurueck],
     * BEVOR der Fehlschlag gemeldet wird: dieselbe Regel wie beim Loeschen von Alarmen ("erst
     * cancelSystemAlarm, dann verwerfen"), nur rueckwaerts angewandt. Was der Rueckbau dabei tut,
     * haengt am Anlass - siehe naechster Absatz.
     *
     * NUR WEGRAEUMEN, WAS DERSELBE VORGANG ANGELEGT HAT: [baueZurueck] kommt als Funktion herein -
     * wie [plane] und [merke] -, damit dieses Objekt Android-frei testbar bleibt UND damit jeder
     * Anlass seinen eigenen, passenden Rueckbau mitbringt. Das ist keine Feinheit, sondern der
     * Unterschied zwischen zwei Datenlagen:
     * - **Frisch schlummern** (`scheduleSnooze`): Der Merker-Eintrag ist gerade NICHT entstanden,
     *   der Alarm schon. Ohne Merker ist er durch nichts mehr abzuraeumen - also abbrechen.
     * - **Nach Neustart wiederherstellen** (`restorePendingSnoozes`): Der Merker-Eintrag steht
     *   BEREITS, er ist ja die Vorlage des Laufs; [merke] zieht ihn nur nach. Ein Rueckbau, der
     *   hier abbricht, wuerde ueber `forgetPendingSnooze()` genau die einzige Spur loeschen, aus
     *   der der Schlummer nach jedem weiteren Neustart wieder auftauchen koennte - aus einem
     *   Schreibfehler wuerde ein endgueltig verlorener Weckruf. Deshalb bleibt dort alles stehen
     *   ([RueckbauErgebnis.BEWUSST_BEHALTEN]): der Alarm klingelt, und das ist fuer eine
     *   Wecker-App die richtige Richtung.
     *
     * Alles ausser [RueckbauErgebnis.ABGERAEUMT] fuehrt zu [SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR] -
     * der Nutzertext hoert dann auf, etwas zu versprechen.
     */
    fun armiere(
        pausiert: Boolean,
        plane: () -> Unit,
        merke: () -> Unit,
        baueZurueck: () -> RueckbauErgebnis,
        melde: (String, Throwable?) -> Unit
    ): SnoozeErgebnis {
        if (pausiert) {
            melde(MELDUNG_PAUSIERT, null)
            return SnoozeErgebnis.ABGELEHNT_PAUSE
        }
        // Merkt sich, ob im AlarmManager wirklich schon etwas steht. Nur dann ist ein Rueckbau
        // noetig - und nur dann waere ein blosses "FEHLGESCHLAGEN" eine Luege.
        var scharf = false
        return try {
            plane()
            scharf = true
            // Scheitert das Vormerken, gilt der Schlummer als NICHT verlaesslich gestellt: der
            // Merker ist frisch nicht geschrieben, der Alarm damit (je nach Anlass) nicht
            // abbrechbar oder nicht reboot-fest. Lieber klingelt der Wecker weiter, als dem
            // Nutzer einen Zustand zuzusagen, den die App nicht mehr in der Hand hat.
            merke()
            SnoozeErgebnis.GEPLANT
        } catch (e: SecurityException) {
            melde("❌ Schlummern konnte nicht geplant werden - Alarm-Berechtigung verweigert", e)
            gibAuf(scharf, baueZurueck, melde)
        } catch (e: Exception) {
            melde("❌ Schlummern konnte nicht geplant werden", e)
            gibAuf(scharf, baueZurueck, melde)
        }
    }

    /**
     * Raeumt einen bereits armierten Alarm ab, bevor der Fehlschlag gemeldet wird.
     *
     * War noch nichts armiert ([scharf] == false), gibt es nichts abzuraeumen - dann ist
     * [SnoozeErgebnis.FEHLGESCHLAGEN] die wahre Aussage. Wirft der Rueckbau selbst, gilt er als
     * misslungen: ein Wurf hier darf den Fehlerpfad nicht ersetzen, sonst kaeme statt einer
     * ehrlichen Meldung gar keine.
     */
    private fun gibAuf(
        scharf: Boolean,
        baueZurueck: () -> RueckbauErgebnis,
        melde: (String, Throwable?) -> Unit
    ): SnoozeErgebnis {
        if (!scharf) return SnoozeErgebnis.FEHLGESCHLAGEN
        val rueckbau = try {
            baueZurueck()
        } catch (e: Exception) {
            // Frueh heraus, damit die Meldung genau EINMAL faellt - eine doppelte Zeile im Log
            // liest sich wie zwei Vorfaelle.
            melde(MELDUNG_RUECKBAU_FEHLER, e)
            return SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR
        }
        return when (rueckbau) {
            RueckbauErgebnis.ABGERAEUMT -> SnoozeErgebnis.FEHLGESCHLAGEN
            RueckbauErgebnis.MISSLUNGEN -> {
                melde(MELDUNG_RUECKBAU_FEHLER, null)
                SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR
            }
            // Eigene Meldung: hier ist NICHTS kaputtgegangen, was der Nutzer suchen muesste - der
            // Alarm steht und bleibt abbrechbar. Die Zeile von MELDUNG_RUECKBAU_FEHLER ("durch
            // nichts mehr abzuraeumen") waere an dieser Stelle schlicht falsch und schickte eine
            // spaetere Fehlersuche in die falsche Richtung.
            RueckbauErgebnis.BEWUSST_BEHALTEN -> {
                melde(MELDUNG_ALARM_BLEIBT, null)
                SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR
            }
        }
    }

    /**
     * Der Text, den der Nutzer sehen MUSS — `null` nur im Erfolgsfall.
     *
     * Ein stilles Nichts ist genau der Fehler, den die Pruefrunde 8 gefunden hat: der Wecker war
     * weg, der Bildschirm zu, und nichts sagte, dass kein neuer Weckruf steht.
     */
    fun hinweisText(ergebnis: SnoozeErgebnis): String? = when (ergebnis) {
        SnoozeErgebnis.GEPLANT -> null
        SnoozeErgebnis.ABGELEHNT_PAUSE -> HINWEIS_PAUSE
        SnoozeErgebnis.FEHLGESCHLAGEN -> HINWEIS_FEHLER
        SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR -> HINWEIS_UNKLAR
    }

    /**
     * Die Ueberschrift ueber [hinweisText] — `null` genau dann, wenn auch der Text `null` ist.
     *
     * Getrennt von [hinweisText] abrufbar, weil die Benachrichtigung beides in verschiedene Felder
     * setzt (`setContentTitle` / `setContentText`).
     */
    fun hinweisTitel(ergebnis: SnoozeErgebnis): String? = when (ergebnis) {
        SnoozeErgebnis.GEPLANT -> null
        SnoozeErgebnis.ABGELEHNT_PAUSE -> TITEL_PAUSE
        SnoozeErgebnis.FEHLGESCHLAGEN -> TITEL_FEHLER
        SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR -> TITEL_UNKLAR
    }

    /**
     * Titel und Text als EIN Wert - damit an einer Renderstelle nicht die Ueberschrift des einen
     * Ergebnisses ueber dem Text eines anderen landen kann. Genau diese Vermischung war der
     * Befund, siehe [TITEL_PAUSE].
     */
    fun hinweis(ergebnis: SnoozeErgebnis): SchlummerMeldung? {
        val text = hinweisText(ergebnis) ?: return null
        val titel = hinweisTitel(ergebnis) ?: return null
        return SchlummerMeldung(titel, text)
    }
}

/**
 * Eine vollstaendige Meldung an den Nutzer: Ueberschrift plus Fliesstext, unzertrennlich.
 *
 * Erzeugt ausschliesslich ueber [SchlummerEntscheidung.hinweis].
 */
internal data class SchlummerMeldung(val titel: String, val text: String)

/**
 * Enhanced AlarmManager service with maximum reliability and doze mode compatibility.
 *
 * Key Features:
 * - Uses setAlarmClock() for maximum doze mode reliability
 * - Integrated battery optimization management
 * - Structured error handling with Result types
 * - Resource management and cleanup
 * - Comprehensive debugging capabilities
 * - 🚀 PHASE 3: Proactive Token-Refresh-Integration
 *
 * Architecture: Clean separation of concerns with dependency injection support
 */
class AlarmManagerService(
    private val application: Application
) {

    private val alarmManager = application.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    data class AlarmStatus(
        val systemAlarmSet: Boolean,
        val canScheduleExactAlarms: Boolean,
        val alarmStatusMessage: String?,
        val batteryOptimizationExempt: Boolean = false
    )

    data class NextAlarmInfo(
        val triggerTime: Long,
        val formattedTime: String
    )



    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true // API < 31 needs no permission
        }
    }

    /**
     * Darf die App eine Vollbild-Benachrichtigung zeigen?
     *
     * Seit Android 14 (API 34) wird USE_FULL_SCREEN_INTENT zwar bei der Installation gewaehrt,
     * der Play Store entzieht sie danach aber allen Apps, die er nicht als Wecker- oder
     * Telefonie-App einstuft. Ohne die Berechtigung degradiert das System den Full-Screen-Intent
     * stillschweigend zu einer Heads-up-Notification: der Wecker klingelt, aber der Weck-Screen
     * kommt nie hoch - und nichts weist darauf hin.
     *
     * Wichtig fuer die Erwartungshaltung: Selbst MIT Berechtigung zeigt Android laut Doku bewusst
     * nur ein Banner, solange das Geraet entsperrt und in Benutzung ist ("While the user is using
     * the device, the system UI might display a heads-up notification instead of launching your
     * full-screen intent"). Das Vollbild ist fuer das gesperrte/dunkle Geraet gedacht - also fuer
     * den echten Weckfall. Ein Test mit entsperrtem Handy in der Hand beweist hier nichts.
     */
    fun canUseFullScreenIntent(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val notificationManager = application.getSystemService(NotificationManager::class.java)
            notificationManager.canUseFullScreenIntent()
        } else {
            true // < API 34: Berechtigung wird mit der Installation gewaehrt
        }
    }



    fun getNextAlarmInfo(): NextAlarmInfo? {
        return try {
            val nextAlarmClockInfo = alarmManager.nextAlarmClock
            if (nextAlarmClockInfo != null) {
                val triggerTime = nextAlarmClockInfo.triggerTime
                val formattedTime = formatAlarmTime(
                    java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(triggerTime),
                        ZoneId.systemDefault()
                    )
                )
                NextAlarmInfo(
                    triggerTime = triggerTime,
                    formattedTime = formattedTime
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_MANAGER, "Error getting next alarm info", e)
            null
        }
    }

    /**
     * Setzt einen Alarm für eine Schicht mit EINDEUTIGEM Request Code
     *
     * @param shiftMatch Die Schicht-Informationen
     * @param autoAlarmEnabled Ob Auto-Alarm aktiviert ist
     * @param alarmId EINDEUTIGE ID für diesen Alarm (z.B. aus der Datenbank)
     */
    fun setAlarmFromShiftMatch(
        shiftMatch: ShiftMatch,
        autoAlarmEnabled: Boolean,
        alarmId: Int // NEU: Eindeutige Alarm-ID!
    ): AlarmStatus {
        Logger.business(
            LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: setAlarmFromShiftMatch called",
            "autoAlarmEnabled=$autoAlarmEnabled, shift=${shiftMatch.shiftDefinition.name}, alarmTime=${shiftMatch.calculatedAlarmTime}, alarmId=$alarmId"
        )

        if (!autoAlarmEnabled) {
            Logger.d(LogTags.ALARM_MANAGER, "Auto-Alarm disabled, not setting alarm")
            return createAlarmStatus(
                systemAlarmSet = false,
                message = "Auto-Alarm deaktiviert"
            )
        }

        val permissionStatus = checkAlarmPermissions()
        Logger.business(
            LogTags.ALARM_MANAGER,
            "🔧 ALARM DEBUG: Permissions check",
            "canScheduleExactAlarms=${permissionStatus.canScheduleExactAlarms}"
        )

        // FEHLENDE EXACT-ALARM-BERECHTIGUNG IST KEIN ABBRUCHGRUND (Fix).
        //
        // Vorher stand hier ein frueher Return ohne jede Planung, plus ein
        // requestExactAlarmPermission(): auf einem Geraet mit entzogener Berechtigung
        // (Android 12/12L, oder ein Hersteller-"Akku-Assistent") landete damit KEIN einziger
        // Schicht-Wecker im AlarmManager - waehrend Repository und UI weiter "5 Alarme aktiv"
        // zeigten. Es klingelte nie. Der Dialog, der das haette erklaeren koennen, kam obendrein
        // nicht: dieser Pfad laeuft aus der 6h-Wartung/dem Worker, und ein
        // Background-Activity-Start wird verworfen (siehe [requestExactAlarmPermission]).
        //
        // Dieselbe Fehlerklasse behandeln Snooze und Direct-Boot-Restore laengst zweistufig -
        // ein inexakt geplanter Wecker (Minuten Verzug) ist unvergleichlich besser als keiner.
        // Alle drei Aufrufstellen gehen deshalb jetzt ueber [setExactOrInexact].
        //
        // "Besser als keiner" traegt allerdings erst, seit AlarmReceiver einen Notausgang hat:
        // das Feuern eines INEXAKTEN Alarms erlaubt keinen Vordergrunddienst-Start (Begruendung
        // an [setExactOrInexact]), der Weckton-Dienst kann also abgelehnt werden. Wer den
        // Notausgang dort entfernt, macht diesen Satz hier wieder zur Falschaussage.
        if (!permissionStatus.canScheduleExactAlarms) {
            Logger.w(
                LogTags.ALARM_MANAGER,
                "⚠️ Keine Exact-Alarm-Berechtigung - Wecker wird inexakt geplant statt uebersprungen"
            )
        }

        return try {
            val alarmTimeMillis = shiftMatch.calculatedAlarmTime
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            val currentTimeMillis = System.currentTimeMillis()
            val timeDifferenceMinutes = (alarmTimeMillis - currentTimeMillis) / (1000 * 60)

            Logger.business(
                LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: Timing",
                "currentTime=$currentTimeMillis, alarmTime=$alarmTimeMillis, differenceMinutes=$timeDifferenceMinutes"
            )

            if (alarmTimeMillis <= currentTimeMillis) {
                Logger.w(
                    LogTags.ALARM_MANAGER,
                    "🔧 ALARM DEBUG: Alarm time is in the past! alarmTime=${shiftMatch.calculatedAlarmTime}, current=${java.time.LocalDateTime.now()}"
                )
                return createAlarmStatus(
                    systemAlarmSet = false,
                    message = "Alarm-Zeit liegt in der Vergangenheit"
                )
            }

            // Erstelle enhanced alarm intent
            val alarmIntent = createEnhancedAlarmIntent(alarmId, shiftMatch)
            val pendingIntent = createAlarmPendingIntent(alarmId, alarmIntent)

            Logger.business(
                LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: Setting alarm",
                "requestCode=$alarmId, pendingIntent=$pendingIntent"
            )

            // KRITISCHE VERBESSERUNG: Verwende setAlarmClock() für maximale Doze-Mode Zuverlässigkeit
            val showIntent = createShowAlarmIntent(alarmId, shiftMatch)
            val exact = setExactOrInexact(
                alarmManager = alarmManager,
                triggerTime = alarmTimeMillis,
                showPendingIntent = showIntent,
                pendingIntent = pendingIntent,
                logContext = "Wecker id=$alarmId"
            )

            val formattedTime = formatAlarmTime(shiftMatch.calculatedAlarmTime)
            Logger.business(
                LogTags.ALARM_MANAGER, "✅ ALARM DEBUG: System alarm set successfully",
                "${shiftMatch.shiftDefinition.name} at $formattedTime (ID: $alarmId, ${if (exact) "exakt" else "INEXAKT"})"
            )

            // Verify alarm was set by checking next alarm
            val nextAlarmInfo = getNextAlarmInfo()
            Logger.business(
                LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: Next alarm verification",
                "nextAlarm=${nextAlarmInfo?.formattedTime ?: "none"}"
            )

            createAlarmStatus(
                systemAlarmSet = true,
                message = if (exact) {
                    "Alarm gesetzt für $formattedTime"
                } else {
                    "Alarm gesetzt für $formattedTime (inexakt - Berechtigung 'Alarme & Erinnerungen' fehlt)"
                }
            )

        } catch (e: SecurityException) {
            Logger.e(
                LogTags.ALARM_MANAGER,
                "🔧 ALARM DEBUG: SecurityException when setting alarm",
                e
            )
            createAlarmStatus(
                systemAlarmSet = false,
                message = "Alarm-Berechtigung verweigert: ${e.message}"
            )
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_MANAGER, "🔧 ALARM DEBUG: Exception when setting alarm", e)
            createAlarmStatus(
                systemAlarmSet = false,
                message = "Fehler beim Alarm setzen: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Creates enhanced alarm intent with comprehensive shift information
     */
    private fun createEnhancedAlarmIntent(alarmId: Int, shiftMatch: ShiftMatch): Intent {
        return Intent(application, AlarmReceiver::class.java).apply {
            putExtra("alarm_id", alarmId)
            putExtra("shift_name", shiftMatch.shiftDefinition.name)
            putExtra("shift_start_time_formatted", formatAlarmTime(shiftMatch.calendarEvent.startTime))
            putExtra("shift_pattern", shiftMatch.shiftDefinition.id)
            putExtra("alarm_type", "setAlarmClock") // Track which API was used
            setPackage(application.packageName)
            action = enhancedAlarmAction(alarmId)
        }
    }

    /**
     * Creates PendingIntent for alarm with proper flags
     */
    private fun createAlarmPendingIntent(alarmId: Int, alarmIntent: Intent): PendingIntent {
        return PendingIntent.getBroadcast(
            application,
            alarmId,
            alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Creates show intent for AlarmClockInfo (when user taps on system alarm)
     */
    private fun createShowAlarmIntent(alarmId: Int, shiftMatch: ShiftMatch): PendingIntent {
        val showIntent = Intent(application, MainActivity::class.java).apply {
            putExtra("alarm_id", alarmId)
            putExtra("shift_name", shiftMatch.shiftDefinition.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(application.packageName)
        }

        return PendingIntent.getActivity(
            application,
            // Der Offset ist KEIN Kollisionsschutz gegen createAlarmPendingIntent()'s requestCode
            // (=alarmId): PendingIntent.getActivity() und .getBroadcast() liegen im System auf
            // getrennten Namensraeumen (Android unterscheidet den "Typ" der Operation als Teil des
            // Schluessels), zwei verschiedene Alarme koennen wegen der Injektivitaet von x -> x+10000
            // auch untereinander nicht kollidieren. Bleibt trotzdem stehen: harmlos, und ein zweiter
            // getActivity()-Aufruf mit requestCode=alarmId anderswo (aktuell: keiner) wuerde sonst
            // real kollidieren.
            alarmId + 10000,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Creates AlarmStatus with comprehensive information
     */
    private fun createAlarmStatus(
        systemAlarmSet: Boolean,
        message: String
    ): AlarmStatus {
        return AlarmStatus(
            systemAlarmSet = systemAlarmSet,
            canScheduleExactAlarms = canScheduleExactAlarms(),
            alarmStatusMessage = message,
            batteryOptimizationExempt = BatteryOptimizationHelper.isExempted(application)
        )
    }

    /**
     * Enhanced alarm cancellation with proper cleanup
     */
    fun cancelSystemAlarm(alarmId: Int): AlarmStatus {
        return try {
            Logger.business(
                LogTags.ALARM_MANAGER,
                "🗑️ ENHANCED ALARM: Cancelling alarm ID: $alarmId"
            )

            val alarmIntent = Intent(application, AlarmReceiver::class.java).apply {
                setPackage(application.packageName)
                action = enhancedAlarmAction(alarmId)
            }

            val pendingIntent = PendingIntent.getBroadcast(
                application,
                alarmId,
                alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Cancel the alarm
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()

            Logger.business(
                LogTags.ALARM_MANAGER,
                "✅ ENHANCED ALARM: System alarm cancelled (ID: $alarmId)"
            )

            createAlarmStatus(
                systemAlarmSet = false,
                message = "Alarm abgebrochen"
            )

        } catch (e: Exception) {
            Logger.e(LogTags.ALARM_MANAGER, "❌ Error cancelling system alarm", e)
            createAlarmStatus(
                systemAlarmSet = false,
                message = "Fehler beim Alarm abbrechen: ${e.localizedMessage}"
            )
        }
    }

    /**
     * Bricht ALLE schwebenden Snooze-Alarme ab (Instanz-Variante fuer Aufrufer ohne Context,
     * z.B. AlarmUseCase).
     *
     * NUR fuer ausdruecklichen Nutzer-Willen ("Hintergrunddienste pausieren", "Automatische
     * Alarme aus", "alle Alarme loeschen") - NICHT fuer datengetriebene Aufraeumzweige und
     * bewusst NICHT an `deleteAlarm(id)` gehaengt: dort raeumt u.a. der BootReceiver abgelaufene
     * Alarme weg, und der Ursprungsalarm eines schwebenden Snooze IST abgelaufen - genau dieser
     * Weg haette den Snooze wieder mitgeloescht. Siehe [Companion.cancelAllSnoozes].
     */
    fun cancelAllSnoozes() = cancelAllSnoozes(application)

    /**
     * Enhanced permission check including battery optimization status
     */
    fun checkAlarmPermissions(): AlarmPermissionStatus {
        val canScheduleExact = canScheduleExactAlarms()
        val batteryExempt = BatteryOptimizationHelper.isExempted(application)
        val canUseFullScreen = canUseFullScreenIntent()

        val overallStatus = when {
            canScheduleExact && batteryExempt -> AlarmPermissionLevel.OPTIMAL
            canScheduleExact && !batteryExempt -> AlarmPermissionLevel.GOOD_BUT_RISKY
            !canScheduleExact && batteryExempt -> AlarmPermissionLevel.MISSING_EXACT_ALARM
            else -> AlarmPermissionLevel.CRITICAL_MISSING
        }

        return AlarmPermissionStatus(
            level = overallStatus,
            canScheduleExactAlarms = canScheduleExact,
            batteryOptimizationExempt = batteryExempt,
            canUseFullScreenIntent = canUseFullScreen
        )
    }



    private fun formatAlarmTime(alarmTime: java.time.LocalDateTime): String {
        val formatter = DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
        return alarmTime.format(formatter)
    }


    companion object {

        /**
         * Der EINE Weg, einen Wecker in den AlarmManager zu legen: exakt per `setAlarmClock()`,
         * und wenn die Exact-Alarm-Berechtigung fehlt, inexakt per `setAndAllowWhileIdle()`.
         *
         * WARUM ZENTRAL: `setAlarmClock()` ist NICHT von der Exact-Alarm-Berechtigung ausgenommen.
         * Auf API 31/32 haengt sie an SCHEDULE_EXACT_ALARM, das der Nutzer (oder ein
         * Hersteller-"Akku-Assistent") entziehen kann. Diese Datei behandelte denselben Fall an
         * ihren drei Aufrufstellen unterschiedlich: Snooze und Direct-Boot-Restore ueberlebten
         * inexakt, der eigentliche Schicht-Wecker fiel komplett aus (frueher Return ohne Planung).
         * Genau diese Inkonsistenz macht das Debuggen unmoeglich - deshalb gibt es die Entscheidung
         * nur noch hier, einmal.
         *
         * Fangt bewusst NICHTS: die SecurityException gehoert zum jeweiligen Aufrufer, der sie
         * schon heute in seinem eigenen try/catch behandelt (Snooze/Direct-Boot: weiterlaufen,
         * setAlarmFromShiftMatch: AlarmStatus mit Fehlermeldung).
         *
         * @return true, wenn exakt geplant wurde
         */
        private fun setExactOrInexact(
            alarmManager: AlarmManager,
            triggerTime: Long,
            showPendingIntent: PendingIntent,
            pendingIntent: PendingIntent,
            logContext: String
        ): Boolean {
            val canScheduleExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()

            if (canScheduleExact) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerTime, showPendingIntent),
                    pendingIntent
                )
                return true
            }

            // WARN, damit es im Release-Log steht (dort landet nur WARN+).
            //
            // Der Verzug ist dabei NICHT die einzige Folge, und die zweite ist die schwerere:
            // ein per setAndAllowWhileIdle gestellter Alarm traegt beim Feuern KEINE
            // Vordergrunddienst-Startfreigabe. Die AlarmManager-Doku nennt den Satz "Alarms
            // scheduled via this API will be allowed to start a foreground service even if the app
            // is in the background" ausdruecklich nur bei setAlarmClock und
            // setExactAndAllowWhileIdle - AlarmMaintenanceService.start() haelt dieselbe Grenze
            // bereits als geltend fest. Der AlarmSoundService kann in diesem Zustand also
            // abgelehnt werden; deshalb hat AlarmReceiver seit dieser Runde den Notausgang
            // (Benachrichtigung mit Full-Screen-Intent, siehe AlarmReceiver.posteWeckNotausgang).
            // Ohne den waere "inexakt" nicht verzoegert, sondern stumm gewesen.
            Logger.w(
                LogTags.ALARM_MANAGER,
                "⚠️ Keine Exact-Alarm-Berechtigung - $logContext wird inexakt geplant (Verzug um " +
                    "Minuten; zusaetzlich kann der Vordergrund-Start des Wecktons abgelehnt " +
                    "werden, dann weckt nur der Notausgang)"
            )
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            return false
        }

        /**
         * Action-String des Alarm-PendingIntents. MUSS an allen Stellen identisch sein
         * (Setzen, Abbrechen, Direct-Boot-Restore), sonst adressieren sie verschiedene
         * Alarm-Slots und es entstehen Doppel-Alarme.
         */
        fun enhancedAlarmAction(alarmId: Int): String =
            "com.github.f1rlefanz.cf_alarmfortimeoffice.ENHANCED_ALARM_$alarmId"

        /**
         * Action-String des SNOOZE-PendingIntents - bewusst verschieden von
         * [enhancedAlarmAction].
         *
         * Ein Snooze darf NICHT denselben PendingIntent-Slot belegen wie sein Ursprungsalarm:
         * PendingIntents matchen ueber requestCode + Action. Waeren beide gleich, wuerde
         * [cancelSystemAlarm] den schwebenden Snooze mit abraeumen - und genau das passiert
         * regelmaessig, weil der Maintenance-Sync gefeuerte (= in der Vergangenheit liegende)
         * Alarme loescht und dabei cancelSystemAlarm aufruft. Der Nutzer haette gedrueckt
         * "5 Minuten schlummern" und waere nie wieder geweckt worden.
         *
         * Die Action bleibt pro alarmId stabil, damit ein zweiter Snooze denselben Slot
         * ersetzt statt einen weiteren Wecker zu stapeln.
         */
        fun snoozeAlarmAction(alarmId: Int): String =
            "com.github.f1rlefanz.cf_alarmfortimeoffice.SNOOZE_ALARM_$alarmId"

        /** Snooze-Dauer in Minuten - EINE Quelle fuer Vollbild-Button UND Notification-Button. */
        const val SNOOZE_MINUTES = 5L

        /**
         * Plant einen Snooze-Alarm in [minutes] Minuten. Der EINE Weg fuer beide Snooze-Ausloeser:
         * "5 Min spaeter" im Vollbild UND der Snooze-Button der Sperrbildschirm-Benachrichtigung
         * (letzterer ist der Notausgang, wenn das Vollbild gar nicht erst sichtbar wird).
         *
         * Nutzt bewusst [snoozeAlarmAction], NICHT [enhancedAlarmAction]: teilte sich der Snooze
         * den PendingIntent-Slot mit seinem Ursprungsalarm, raeumte ihn der Maintenance-Sync beim
         * Loeschen des gefeuerten Alarms mit ab - der Nutzer haette "schlummern" gedrueckt und waere
         * nie wieder geweckt worden. requestCode = alarmId, damit ein zweiter Snooze denselben Slot
         * ersetzt statt zu stapeln.
         *
         * BERECHTIGUNG: setAlarmClock() ist NICHT von der Exact-Alarm-Berechtigung ausgenommen (das
         * behauptete dieser Kommentar frueher). Auf API 31/32 haengt sie an SCHEDULE_EXACT_ALARM,
         * das der Nutzer in den Systemeinstellungen entziehen kann - dann wirft AlarmManager eine
         * SecurityException. Ungefangen riss die aus dem Snooze-Button der Notification den
         * kompletten AlarmSoundService-Prozess mit: kein Snooze, kein Ton, keine Meldung. Deshalb
         * hier zweistufig: bei fehlender Berechtigung inexakt per setAndAllowWhileIdle() planen
         * (ein um Minuten verzoegerter Snooze ist besser als kein Snooze), und alles in try/catch.
         *
         * SHOW-INTENT: Der erste Parameter von [AlarmManager.AlarmClockInfo] ist der Intent, den
         * Uhr-Widgets/Sperrbildschirm ANZEIGEN bzw. per send() ausloesen - dort gehoert eine
         * Activity hin, nicht der Broadcast-PendingIntent, der den Wecker AUSLOEST (sonst klingelt
         * der Wecker sofort, sobald jemand die Wecker-Affordance antippt). Gleiche
         * requestCode-Konvention (alarmId + 10000) wie [createShowAlarmIntent] und
         * [rescheduleFromDirectBoot].
         *
         * RUECKGABE AUSWERTEN, IMMER: bis zur Pruefrunde 8 war diese Funktion `Unit` und verwarf
         * das Ergebnis von [armSnooze] — ein gescheiterter Schlummer war von einem erfolgreichen
         * nicht zu unterscheiden, beide Aufrufer beendeten den Wecker danach unbedingt. Wer den
         * Rueckgabewert wieder ignoriert, baut genau diesen stillen Ausfall zurueck.
         *
         * @return [SnoozeErgebnis.GEPLANT] nur, wenn der Wecker WIRKLICH steht und vorgemerkt ist.
         */
        fun scheduleSnooze(
            context: Context,
            alarmId: Int,
            shiftName: String,
            shiftStartTimeFormatted: String,
            minutes: Long = SNOOZE_MINUTES
        ): SnoozeErgebnis {
            val triggerTime = System.currentTimeMillis() + minutes * 60 * 1000L
            return armSnooze(
                context = context,
                alarmId = alarmId,
                triggerTime = triggerTime,
                shiftName = shiftName,
                shiftStartTimeFormatted = shiftStartTimeFormatted,
                logContext = "Snooze-Alarm gesetzt: $shiftName in $minutes Min",
                // FRISCH SCHLUMMERN: Der Merker-Eintrag zu dieser ID ist gerade NICHT entstanden
                // (sonst waeren wir nicht im Rueckbau). Ein Alarm ohne Merker ist durch nichts
                // mehr abzuraeumen - weder durch die Master-Pause noch durch `deleteAllAlarms`
                // noch durch das Abmelden -, also muss er weg. [cancelSnooze] baut denselben
                // PendingIntent-Slot zeichengleich nach und ist damit die exakte Gegenoperation.
                // Das darin enthaltene `forgetPendingSnooze()` laeuft ins Leere bzw. raeumt einen
                // Alt-Eintrag derselben ID mit weg - hier beides richtig.
                baueZurueck = {
                    if (cancelSnooze(context, alarmId)) {
                        RueckbauErgebnis.ABGERAEUMT
                    } else {
                        RueckbauErgebnis.MISSLUNGEN
                    }
                }
            )
        }

        /**
         * Armiert einen Snooze-Alarm zu einem BEREITS BERECHNETEN Zeitpunkt und merkt ihn vor.
         *
         * EIN Weg fuer beide Anlaesse - [scheduleSnooze] (der Nutzer drueckt schlummern) und
         * [restorePendingSnoozes] (nach einem Reboot). Bewusst gemeinsam: der PendingIntent muss in
         * BEIDEN Faellen bis aufs Zeichen derselbe sein (requestCode = alarmId, action =
         * [snoozeAlarmAction]), sonst trifft ein spaeterer Abbruch den wiederhergestellten Snooze
         * nicht mehr - und ein Snooze, der sich nicht abbrechen laesst, klingelt mitten in einer
         * Pause, die der Nutzer eingeschaltet hat. Zwei getrennte Kopien dieses Intents waeren genau
         * die Doppelung, die im Projekt schon einmal zu zwei Wartungsketten gefuehrt hat.
         *
         * MASTER-PAUSE-BACKSTOP: Genau HIER, nicht bei den Aufrufern. Das Schlummern war der
         * einzige Armierungspfad ohne Pausen-Pruefung; der Ablauf "Wecker klingelt -> Nutzer
         * schaltet die Pause ein -> Nutzer drueckt schlummern" armierte einen Wecker, den danach
         * nichts mehr abraeumte (die 6h-Kette ist gekappt, `syncAlarms()` laeuft nur bei
         * App-Interaktion) - waehrend die Oberflaeche "Hintergrunddienste pausiert" zeigte. Der
         * Backstop deckt beide Ausloeser (Vollbild, Notification) UND jeden kuenftigen Aufrufer.
         *
         * [baueZurueck] IST DER EINZIGE UNTERSCHIED zwischen den beiden Anlaessen und kommt
         * deshalb vom Aufrufer, nicht aus einem Flag hier drin: Scheitert das Vormerken, ist der
         * Alarm schon armiert, und was dann zu tun ist, haengt daran, ob der Merker-Eintrag
         * gerade erst entstehen sollte (frisch schlummern -> Alarm abbrechen) oder ob er schon
         * vorher da war (Wiederherstellung -> nichts anfassen, sonst loescht der Rueckbau die
         * einzige Spur des Schlummers und der Weckruf ist endgueltig verloren). Details in
         * [SchlummerEntscheidung.armiere].
         */
        private fun armSnooze(
            context: Context,
            alarmId: Int,
            triggerTime: Long,
            shiftName: String,
            shiftStartTimeFormatted: String,
            logContext: String,
            baueZurueck: () -> RueckbauErgebnis
        ): SnoozeErgebnis {
            val ergebnis = SchlummerEntscheidung.armiere(
                // ZUERST der Backstop: waehrend einer Master-Pause entsteht hier gar nichts, auch
                // kein PendingIntent (FLAG_UPDATE_CURRENT schriebe sonst einen Slot fort, den
                // niemand mehr braucht).
                pausiert = masterPauseLaeuft(context),
                plane = {
                    val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                        putExtra(AlarmReceiver.EXTRA_SHIFT_NAME, shiftName)
                        putExtra(AlarmReceiver.EXTRA_ALARM_ID, alarmId)
                        // Der Schichtbeginn aendert sich durchs Schlummern nicht - unveraendert
                        // aus dem urspruenglichen Alarm durchreichen statt hier neu ("jetzt +
                        // Minuten") zu berechnen.
                        putExtra(AlarmReceiver.EXTRA_SHIFT_START_TIME, shiftStartTimeFormatted)
                        setPackage(context.packageName)
                        action = snoozeAlarmAction(alarmId)
                    }

                    val pendingIntent = PendingIntent.getBroadcast(
                        context, alarmId, alarmIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val showIntent = Intent(context, MainActivity::class.java).apply {
                        putExtra("alarm_id", alarmId)
                        putExtra("shift_name", shiftName)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        setPackage(context.packageName)
                    }
                    val showPendingIntent = PendingIntent.getActivity(
                        context, alarmId + 10000, showIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )

                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                    setExactOrInexact(
                        alarmManager = alarmManager,
                        triggerTime = triggerTime,
                        showPendingIntent = showPendingIntent,
                        pendingIntent = pendingIntent,
                        logContext = "Snooze id=$alarmId"
                    )
                },
                // Erst NACH erfolgreicher Planung vormerken: der Eintrag ist die einzige Spur, ueber
                // die ein schwebender Snooze spaeter noch abgebrochen werden kann.
                merke = {
                    rememberPendingSnooze(context, alarmId, triggerTime, shiftName, shiftStartTimeFormatted)
                },
                // Durchgereicht vom Aufrufer - siehe KDoc: frisch schlummern raeumt ab,
                // Wiederherstellen laesst stehen.
                baueZurueck = baueZurueck,
                melde = { text, fehler ->
                    if (fehler == null) {
                        Logger.w(LogTags.ALARM_MANAGER, "$text (id=$alarmId)")
                    } else {
                        Logger.e(LogTags.ALARM_MANAGER, "$text (id=$alarmId)", fehler)
                    }
                }
            )
            if (ergebnis == SnoozeErgebnis.GEPLANT) {
                Logger.business(LogTags.ALARM_MANAGER, "😴 $logContext (id=$alarmId)")
            }
            return ergebnis
        }

        /**
         * Laeuft gerade eine Master-Pause? Synchron lesbar, weil beide Schlummer-Ausloeser
         * synchron sind (Vollbild-Knopf, Notification-Notausgang) und der Snooze auch im
         * Boot-Fenster wiederhergestellt wird.
         *
         * BEWUSST der Device-Protected-Spiegel statt `MasterPausePrefs`: Letzteres liegt im
         * CE-Storage und ist nur suspendierend UND erst nach der ersten Entsperrung lesbar -
         * derselbe Grund und derselbe Weg wie im `BootReceiver`, im
         * `AlarmMaintenanceBroadcastReceiver` und in `StatusPermissionCards.masterPauseAktiv()`.
         *
         * RICHTUNG DER DEGRADATION: Bei einem Lesefehler "nicht pausiert" - im Zweifel wecken.
         * Ein verschluckter Schlummer waere der schwerere Schaden.
         */
        private fun masterPauseLaeuft(context: Context): Boolean = try {
            EntryPointAccessors
                .fromApplication(context.applicationContext, AlarmMaintenanceEntryPoint::class.java)
                .directBootAlarmStore()
                .isPausedNow()
        } catch (e: Exception) {
            Logger.w(
                LogTags.ALARM_MANAGER,
                "Pausen-Spiegel nicht lesbar - der Schlummer wird trotzdem gestellt (im Zweifel wecken)",
                e
            )
            false
        }

        // ---------------------------------------------------------------------------------------
        // Schwebende Snooze-Alarme: eigener Cancel-Weg
        //
        // Der Snooze liegt bewusst in einem EIGENEN PendingIntent-Slot (siehe [snoozeAlarmAction]),
        // damit ihn der Maintenance-Sync nicht mit abraeumt. Genau daraus folgte aber ein Loch:
        // [cancelSystemAlarm] baut ausschliesslich [enhancedAlarmAction] und trifft den Snooze-Slot
        // damit NIE. Ein bereits gestellter Snooze lief deshalb durch JEDE App-seitige Abschaltung
        // hindurch (Master-Pause, "Automatische Alarme aus", deleteAllAlarms) und klingelte mitten
        // in einer Pause, die der Nutzer gerade eingeschaltet hatte.
        //
        // Die Snooze-IDs sind sonst nirgends persistiert (der gefeuerte Ursprungsalarm ist zu dem
        // Zeitpunkt schon aus dem Repository geraeumt), deshalb dieser winzige eigene Merker im
        // DEVICE-PROTECTED Storage - dieselbe Wahl und derselbe Grund wie beim DirectBootAlarmStore
        // (synchron, ohne Coroutine, auch vor der ersten Entsperrung lesbar).
        // ---------------------------------------------------------------------------------------

        private const val SNOOZE_PREFS_NAME = "pending_snoozes"
        private const val KEY_SNOOZE_ENTRIES = "entries"
        private const val SNOOZE_ENTRY_SEPARATOR = "|"

        /**
         * Serialisiert JEDEN Read-Modify-Write auf dem Snooze-Merker.
         *
         * Drei unabhaengige Schreibpfade ([rememberPendingSnooze], [forgetPendingSnooze] und der
         * Writeback in [restorePendingSnoozes]) lesen die Menge, rechnen und schreiben zurueck. Ohne
         * Serialisierung gewinnt der letzte Schreiber, und ein verlorener Eintrag heisst: ein Snooze
         * ist im AlarmManager scharf, aber die App kennt ihn nicht mehr - er laesst sich weder
         * abbrechen noch nach einem Reboot wiederherstellen. Nebenlaeufig erreichbar ist das bei
         * jedem Boot (Restore-Writeback) und bei jedem Snooze-Tippen.
         *
         * `synchronized` statt Mutex: die Aufrufer sind teils synchron (Vollbild-Button,
         * Notification-Notausgang), ein `suspend`-Lock waere dort nicht verfuegbar. Die
         * eingeschlossene Arbeit ist ein Prefs-Read und ein Write - Millisekunden.
         */
        private val snoozeRegistryLock = Any()

        private fun snoozePrefs(context: Context): SharedPreferences =
            context.createDeviceProtectedStorageContext()
                .getSharedPreferences(SNOOZE_PREFS_NAME, Context.MODE_PRIVATE)

        /**
         * Ein vorgemerkter Snooze. [shiftName]/[shiftStartTimeFormatted] koennen leer sein - dann
         * stammt der Eintrag aus einem Merker vor v1.23.0, der nur ID und Zeit kannte.
         */
        internal data class SnoozeEntry(
            val id: Int,
            val triggerTime: Long,
            val shiftName: String,
            val shiftStartTimeFormatted: String
        )

        /**
         * "id|triggerTime|shiftName|shiftStart" - reine Funktion, damit das Format testbar bleibt.
         *
         * Die beiden Textfelder kamen mit v1.23.0 dazu, weil der Merker seit dem
         * Reboot-Wiederherstellen (siehe [restorePendingSnoozes]) nicht nur zum ABBRECHEN dient:
         * zum Neusetzen braucht der Alarm dieselben Anzeigedaten wie beim ersten Mal, sonst zeigt
         * das Vollbild nach einem Reboot "Deine Schicht beginnt um" ohne Zeit.
         *
         * Das Trennzeichen wird aus den Textfeldern entfernt, statt sie zu escapen: es sind reine
         * Anzeigetexte, ein ersetztes Zeichen ist harmlos - ein zerbrochener Eintrag nicht.
         */
        internal fun encodeSnoozeEntry(
            alarmId: Int,
            triggerTime: Long,
            shiftName: String = "",
            shiftStartTimeFormatted: String = ""
        ): String = listOf(
            alarmId.toString(),
            triggerTime.toString(),
            shiftName.replace(SNOOZE_ENTRY_SEPARATOR, "/"),
            shiftStartTimeFormatted.replace(SNOOZE_ENTRY_SEPARATOR, "/")
        ).joinToString(SNOOZE_ENTRY_SEPARATOR)

        /**
         * null bei kaputtem/fremdem Eintrag - ein Lesefehler darf nie den Cancel-Weg sprengen.
         *
         * Der ZWEITEILIGE Altbestand bleibt lesbar. Wuerde er hier als kaputt gelten, verloere ein
         * Nutzer, der genau ueber die Aktualisierung hinweg schlummert, den einzigen Weg, diesen
         * Snooze noch abzubrechen - der Eintrag ist seine einzige Spur.
         */
        internal fun parseSnoozeEntry(entry: String): SnoozeEntry? {
            val parts = entry.split(SNOOZE_ENTRY_SEPARATOR)
            if (parts.size != 2 && parts.size != 4) return null
            val id = parts[0].toIntOrNull() ?: return null
            val triggerTime = parts[1].toLongOrNull() ?: return null
            return SnoozeEntry(
                id = id,
                triggerTime = triggerTime,
                shiftName = parts.getOrNull(2) ?: "",
                shiftStartTimeFormatted = parts.getOrNull(3) ?: ""
            )
        }

        /**
         * Selbstreinigung: Eintraege, deren Snooze-Zeit verstrichen ist, sind erledigt (der Alarm
         * ist gefeuert oder wurde gestoppt) und wuerden den Merker sonst unbegrenzt wachsen lassen.
         */
        internal fun pruneSnoozeEntries(entries: Set<String>, now: Long): Set<String> =
            entries.filter { raw ->
                val parsed = parseSnoozeEntry(raw)
                parsed != null && parsed.triggerTime > now
            }.toSet()

        internal fun snoozeIdsOf(entries: Set<String>): List<Int> =
            entries.mapNotNull { parseSnoozeEntry(it)?.id }.distinct()

        // Lint-Befund ApplySharedPref ("nimm apply()") wird hier bewusst NICHT befolgt: der
        // synchrone commit() ist eine Entscheidung, kein Versehen - siehe Kommentar am Write unten
        // und Skill cfalarm-wecker-und-boot ("Der Snooze-Merker ist serialisiert
        // (snoozeRegistryLock) und schreibt mit commit()"). apply() schreibt asynchron und verloere
        // denselben Eintrag bei einem Prozess-Tod unmittelbar danach; der Snooze waere dann im
        // AlarmManager scharf, aber der App unbekannt - weder abbrechbar noch nach einem Reboot
        // wiederherstellbar.
        @Suppress("ApplySharedPref")
        private fun rememberPendingSnooze(
            context: Context,
            alarmId: Int,
            triggerTime: Long,
            shiftName: String,
            shiftStartTimeFormatted: String
        ) = synchronized(snoozeRegistryLock) {
            try {
                val prefs = snoozePrefs(context)
                val existing = prefs.getStringSet(KEY_SNOOZE_ENTRIES, emptySet()) ?: emptySet()
                val kept = pruneSnoozeEntries(existing, System.currentTimeMillis())
                    .filterNot { parseSnoozeEntry(it)?.id == alarmId }
                    .toSet()
                // commit(), NICHT apply(): dieser Eintrag ist die EINZIGE Spur eines
                // schwebenden Snooze. `apply()` schreibt asynchron; stirbt der Prozess unmittelbar
                // danach (Doze, Low-Memory-Kill, Reboot), ist der Snooze im AlarmManager scharf,
                // aber nirgends vermerkt - nicht abbrechbar und nach einem Reboot nicht
                // wiederherstellbar. Der Aufrufer ist ein Notausgang von Millisekunden-Dauer, ein
                // synchroner Write ist hier billiger als der Verlust.
                prefs.edit(commit = true) {
                    putStringSet(
                        KEY_SNOOZE_ENTRIES,
                        kept + encodeSnoozeEntry(alarmId, triggerTime, shiftName, shiftStartTimeFormatted)
                    )
                }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze-Merker konnte nicht geschrieben werden", e)
                // WEITERWERFEN, nicht schlucken: Dieser Merker ist die einzige Spur des
                // schwebenden Snooze. Wer den Fehler hier begraebt, meldet dem Aufrufer
                // "GEPLANT" fuer einen Wecker, den die App danach weder abbrechen noch nach
                // einem Neustart wiederherstellen kann - ein Fehler, der als Erfolg durchgeht,
                // ist genau die Luege, gegen die [SnoozeErgebnis] ueberhaupt eingefuehrt wurde.
                // Der einzige Aufrufer ist die `merke`-Funktion in [armSnooze]; dort raeumt
                // [SchlummerEntscheidung.armiere] den bereits armierten Alarm daraufhin wieder ab
                // und sagt dem Nutzer, woran er ist. Der CE-Storage ist vor der ersten
                // Entsperrung nicht beschreibbar - dieser Zweig ist real erreichbar.
                throw e
            }
        }

        // Lint-Befund ApplySharedPref ("nimm apply()") wird hier bewusst NICHT befolgt - dieselbe
        // Begruendung wie bei [rememberPendingSnooze] und im Skill cfalarm-wecker-und-boot:
        // der Merker ist die einzige Spur eines schwebenden Snooze, und ein asynchroner Write kann
        // bei einem Prozess-Tod unmittelbar danach verloren gehen. Beim Vergessen ist die Richtung
        // gespiegelt, aber genauso wenig hinnehmbar: ein bereits abgebrochener Snooze bliebe im
        // Merker stehen und wuerde beim naechsten Boot-Restore wieder scharf gesetzt.
        @Suppress("ApplySharedPref")
        private fun forgetPendingSnooze(context: Context, alarmId: Int) = synchronized(snoozeRegistryLock) {
            try {
                val prefs = snoozePrefs(context)
                val existing = prefs.getStringSet(KEY_SNOOZE_ENTRIES, emptySet()) ?: emptySet()
                val kept = pruneSnoozeEntries(existing, System.currentTimeMillis())
                    .filterNot { parseSnoozeEntry(it)?.id == alarmId }
                    .toSet()
                prefs.edit(commit = true) { putStringSet(KEY_SNOOZE_ENTRIES, kept) }
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze-Merker konnte nicht bereinigt werden", e)
            }
        }

        /**
         * Bricht den schwebenden Snooze-Alarm zu [alarmId] ab. Baut denselben PendingIntent wie
         * [scheduleSnooze] (requestCode = alarmId, action = [snoozeAlarmAction]) - nur so trifft
         * der Cancel den richtigen Slot.
         *
         * RUECKGABE: `true` nur, wenn der Abbruch wirklich durchlief. Die Funktion faengt ihren
         * Fehler weiterhin selbst (kein Aufrufer darf daran sterben), verschweigt ihn aber nicht
         * mehr - [SchlummerEntscheidung.armiere] braucht die Antwort, um beim Aufgeben eines
         * Armierungsversuchs zwischen "nichts steht mehr scharf" und "moeglicherweise doch"
         * unterscheiden zu koennen. Aufrufer, die den Wert nicht auswerten, bleiben unveraendert
         * richtig.
         */
        fun cancelSnooze(context: Context, alarmId: Int): Boolean {
            return try {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                    setPackage(context.packageName)
                    action = snoozeAlarmAction(alarmId)
                }
                val pendingIntent = PendingIntent.getBroadcast(
                    context, alarmId, alarmIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                forgetPendingSnooze(context, alarmId)
                Logger.business(LogTags.ALARM_MANAGER, "🚫 Schwebender Snooze abgebrochen (id=$alarmId)")
                true
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze konnte nicht abgebrochen werden (id=$alarmId)", e)
                false
            }
        }

        /**
         * Bricht ALLE vorgemerkten Snooze-Alarme ab.
         *
         * NUR bei ausdruecklichem Nutzer-Willen aufrufen (Master-Pause, "Automatische Alarme aus",
         * "alle Alarme loeschen"). NICHT in datengetriebenen Aufraeumzweigen wie "Kalender liefert
         * gerade keine Events" - genau davor schuetzt der eigene Snooze-Slot, und ein leerer
         * Kalender-Abruf ist direkt nach dem Boot/ohne Netz der Normalfall.
         */
        fun cancelAllSnoozes(context: Context) {
            val ids = try {
                snoozeIdsOf(snoozePrefs(context).getStringSet(KEY_SNOOZE_ENTRIES, emptySet()) ?: emptySet())
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze-Merker konnte nicht gelesen werden", e)
                emptyList()
            }
            if (ids.isEmpty()) return
            Logger.business(LogTags.ALARM_MANAGER, "🚫 ${ids.size} schwebende Snooze-Alarme werden abgebrochen")
            ids.forEach { cancelSnooze(context, it) }
        }

        /**
         * Setzt schwebende Snooze-Alarme nach einem Reboot neu - der Weg, der sonst zum Verschlafen
         * fuehrt.
         *
         * AlarmManager verliert bei einem Reboot ALLE Alarme. Der [DirectBootAlarmStore] deckt die
         * regulaeren Alarme ab; ein Snooze stand bis v1.23.0 in keinem der beiden
         * Wiederherstellungs-Pfade. Der Ablauf war damit: Wecker klingelt, der Nutzer drueckt
         * schlummern, das Geraet startet in den naechsten Minuten neu (Systemupdate, Akku leer und
         * am Kabel wieder an, Absturz) - und es klingelt NIE wieder. Der Ursprungsalarm ist zu dem
         * Zeitpunkt schon gefeuert und aus dem Repository geraeumt, es gibt also auch keinen
         * zweiten Anker. Genau die Datenlage, fuer die dieser Merker existiert: er liegt im
         * DEVICE-PROTECTED Storage und ist deshalb schon bei LOCKED_BOOT_COMPLETED lesbar, also vor
         * der ersten Entsperrung.
         *
         * Verstrichene Eintraege werden dabei weggeraeumt (nicht neu gesetzt): ihr Snooze ist
         * gefeuert oder wurde gestoppt. Ein Eintrag aus einer Version vor v1.23.0 hat keine
         * Anzeigedaten - er wird trotzdem gesetzt, nur ohne Schichtnamen. Lieber ein Wecker ohne
         * Beschriftung als kein Wecker.
         *
         * @return Anzahl der wiederhergestellten Snooze-Alarme.
         */
        fun restorePendingSnoozes(context: Context): Int {
            val now = System.currentTimeMillis()
            val entries = try {
                snoozePrefs(context).getStringSet(KEY_SNOOZE_ENTRIES, emptySet()) ?: emptySet()
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze-Merker konnte nicht gelesen werden", e)
                return 0
            }
            if (entries.isEmpty()) return 0

            val alive = pruneSnoozeEntries(entries, now)
            if (alive.size != entries.size) {
                try {
                    // DRITTER Read-Modify-Write-Pfad auf dem Merker - und deshalb unter DERSELBEN
                    // Sperre und mit DEMSELBEN commit() wie `rememberPendingSnooze()`/
                    // `forgetPendingSnooze()`. Vorher stand hier ein ungesichertes `apply()` auf der
                    // oben gelesenen Menge: schlummert der Nutzer, waehrend der Boot-Restore laeuft
                    // (der Ursprungsalarm kann waehrend der Wiederherstellung feuern), dann legt
                    // `rememberPendingSnooze()` seinen Eintrag an - und dieser Writeback schrieb die
                    // ALTE, vor dem Eintrag gelesene Menge darueber. Der Snooze waere im AlarmManager
                    // scharf, dem Merker aber unbekannt: weder abbrechbar (auch nicht durch die
                    // Master-Pause) noch nach dem naechsten Reboot wiederherstellbar - genau der
                    // Ausfall, gegen den dieser Merker ueberhaupt existiert.
                    // Deshalb INNERHALB der Sperre neu lesen und erst dann kuerzen; ein frisch
                    // vorgemerkter Snooze liegt in der Zukunft und ueberlebt das Kuerzen.
                    synchronized(snoozeRegistryLock) {
                        val current = snoozePrefs(context)
                            .getStringSet(KEY_SNOOZE_ENTRIES, emptySet()) ?: emptySet()
                        val pruned = pruneSnoozeEntries(current, now)
                        snoozePrefs(context).edit(commit = true) {
                            putStringSet(KEY_SNOOZE_ENTRIES, pruned)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e(LogTags.ALARM_MANAGER, "❌ Snooze-Merker konnte nicht bereinigt werden", e)
                }
            }

            // ERFOLGE zaehlen, nicht Versuche. `armSnooze()` faengt SecurityException und Exception
            // selbst ab und wirft nie nach oben - ein `try/catch` um den Aufruf waere toter Code, und
            // ein blindes `restored++` behauptete im Boot-Log eine Wiederherstellung, die nicht
            // stattgefunden hat. Fuer einen Vorfall ist das die schlimmste Art Log: es sieht aus, als
            // waere alles in Ordnung.
            var restored = 0
            var failed = 0
            var pausiert = 0
            alive.mapNotNull { parseSnoozeEntry(it) }.forEach { entry ->
                val ergebnis = armSnooze(
                    context = context,
                    alarmId = entry.id,
                    triggerTime = entry.triggerTime,
                    shiftName = entry.shiftName,
                    shiftStartTimeFormatted = entry.shiftStartTimeFormatted,
                    logContext = "Schwebender Snooze nach Neustart wiederhergestellt",
                    // WIEDERHERSTELLEN RAEUMT NICHT AB: Der Merker-Eintrag zu dieser ID steht
                    // schon - er ist die Vorlage dieses Laufs, `rememberPendingSnooze()` zieht ihn
                    // nur nach. Ein Cancel wuerde ueber `forgetPendingSnooze()` genau diesen
                    // Eintrag loeschen: Alarm gecancelt, einzige Spur weg, auch bei jedem weiteren
                    // Neustart nicht mehr wiederherstellbar. Aus einem Schreibfehler wuerde ein
                    // endgueltig verlorener Weckruf. Also stehen lassen - der Alarm klingelt und
                    // bleibt ueber den bestehenden Eintrag abbrechbar.
                    baueZurueck = { RueckbauErgebnis.BEWUSST_BEHALTEN }
                )
                when (ergebnis) {
                    SnoozeErgebnis.GEPLANT -> restored++
                    // Die Ablehnung wegen Master-Pause ist KEIN Ausfall, sondern der gewollte
                    // Zustand - beide Aufrufer dieser Funktion gaten ohnehin schon davor. Sie
                    // duerfte nur als ZWEITE Schicht ueberhaupt greifen; als Fehlschlag gezaehlt
                    // wuerde sie im Boot-Log einen Ausfall behaupten, den es nicht gibt.
                    SnoozeErgebnis.ABGELEHNT_PAUSE -> pausiert++
                    // Auch der unklare Ausgang zaehlt nicht als Erfolg: der Snooze steht nicht
                    // verlaesslich (beim Wiederherstellen: Alarm ja, frisch geschriebener Merker
                    // nein), und ein `restored++` behauptete im Boot-Log eine saubere
                    // Wiederherstellung, die es nicht gab.
                    SnoozeErgebnis.FEHLGESCHLAGEN,
                    SnoozeErgebnis.FEHLGESCHLAGEN_UNKLAR -> failed++
                }
            }
            if (pausiert > 0) {
                Logger.w(
                    LogTags.ALARM_MANAGER,
                    "⏸️ $pausiert schwebende(r) Snooze NICHT wiederhergestellt - die " +
                        "Hintergrunddienste sind pausiert (gewollt, kein Ausfall)"
                )
            }
            if (failed > 0) {
                Logger.w(
                    LogTags.ALARM_MANAGER,
                    // "koennen ausfallen", nicht "fallen aus": der unklare Ausgang laesst den
                    // Alarm bewusst armiert. Eine Zeile, die den Ausfall behauptet, waere in
                    // diesem Fall genauso falsch wie eine, die den Erfolg behauptet.
                    "⚠️ $failed schwebende(r) Snooze konnte NICHT verlaesslich wiederhergestellt " +
                        "werden - diese Weckvorgaenge koennen ausfallen"
                )
            }
            return restored
        }

        /**
         * DE-SICHERES Neusetzen eines Alarms aus dem Direct-Boot-Spiegel.
         *
         * Baut exakt denselben PendingIntent wie der regulaere Pfad (gleiche requestCode=id und
         * action), sodass ein spaeteres regulaeres Rescheduling nach Entsperrung den Alarm via
         * FLAG_UPDATE_CURRENT aktualisiert statt zu duplizieren. Braucht KEIN Hilt/CE/Kalender und
         * darf im Direct-Boot laufen. Nur fuer Alarme in der Zukunft.
         *
         * setAlarmClock() ist NICHT von der Exact-Alarm-Berechtigung ausgenommen (das behauptete
         * dieser Kommentar frueher): auf API 31/32 kann der Nutzer sie entziehen, dann fliegt eine
         * SecurityException. Sie darf hier NIEMALS nach oben durchschlagen - der Aufrufer
         * (BootReceiver) restauriert in derselben Schleife weitere Alarme, und ein Absturz im
         * Boot-Fenster liesse alle folgenden ungesetzt. Fallback wie beim Snooze: inexakt planen.
         */
        fun rescheduleFromDirectBoot(
            context: Context,
            id: Int,
            triggerTime: Long,
            shiftName: String,
            shiftStartTimeFormatted: String
        ) {
            if (triggerTime <= System.currentTimeMillis()) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val alarmIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra("alarm_id", id)
                putExtra("shift_name", shiftName)
                putExtra("shift_start_time_formatted", shiftStartTimeFormatted)
                putExtra("alarm_type", "directBootRestore")
                setPackage(context.packageName)
                action = enhancedAlarmAction(id)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, id, alarmIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val showIntent = Intent(context, MainActivity::class.java).apply {
                putExtra("alarm_id", id)
                putExtra("shift_name", shiftName)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                setPackage(context.packageName)
            }
            // Gleicher +10000-Offset wie createShowAlarmIntent() - siehe Kommentar dort, warum das
            // kein aktiver Kollisionsschutz ist (getrennter PendingIntent-Namensraum getActivity()
            // vs. getBroadcast()), sondern nur die requestCode-Konvention konsistent haelt.
            val showPendingIntent = PendingIntent.getActivity(
                context, id + 10000, showIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                setExactOrInexact(
                    alarmManager = alarmManager,
                    triggerTime = triggerTime,
                    showPendingIntent = showPendingIntent,
                    pendingIntent = pendingIntent,
                    logContext = "DIRECT-BOOT-Alarm id=$id"
                )
                Logger.business(
                    LogTags.ALARM_MANAGER,
                    "🔐 DIRECT-BOOT: Alarm neu gesetzt - id=$id, $shiftName @ $shiftStartTimeFormatted"
                )
            } catch (e: SecurityException) {
                Logger.e(
                    LogTags.ALARM_MANAGER,
                    "❌ DIRECT-BOOT: Alarm id=$id konnte nicht gesetzt werden - Berechtigung verweigert",
                    e
                )
            } catch (e: Exception) {
                Logger.e(LogTags.ALARM_MANAGER, "❌ DIRECT-BOOT: Alarm id=$id konnte nicht gesetzt werden", e)
            }
        }
    }
}

/**
 * Alarm permission status
 */
data class AlarmPermissionStatus(
    val level: AlarmPermissionLevel,
    val canScheduleExactAlarms: Boolean,
    val batteryOptimizationExempt: Boolean,
    /**
     * false = der Weck-Screen kommt nicht von selbst hoch, der Wecker erscheint nur als Banner.
     * Ab Android 14 entzieht der Play Store diese Berechtigung nach der Installation, wenn er
     * die App nicht als Wecker-App einstuft.
     */
    val canUseFullScreenIntent: Boolean = true
)

/**
 * Alarm permission levels for user guidance
 */
enum class AlarmPermissionLevel {
    OPTIMAL,           // All permissions granted, battery optimization exempt
    GOOD_BUT_RISKY,    // Exact alarms allowed but no battery optimization exemption
    MISSING_EXACT_ALARM, // Battery exempt but no exact alarm permission
    CRITICAL_MISSING   // Missing both critical permissions
}
