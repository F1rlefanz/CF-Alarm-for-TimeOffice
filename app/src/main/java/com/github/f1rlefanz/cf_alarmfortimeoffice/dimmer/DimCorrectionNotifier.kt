package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.github.f1rlefanz.cf_alarmfortimeoffice.MainActivity
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NotificationDeliverability
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Baut/aktualisiert/verwirft die Dimmer-Korrektur-Notification (Feature C): Heller/Dunkler/
 * Pause↔Fortsetzen fuer das gerade aktive Dimm-Fenster. [DimScheduleUseCase.applyCurrentState]
 * ist der EINZIGE Aufrufer, der [show]/[cancel] entscheidet - dieser Notifier selbst kennt keine
 * Fenster-Logik, er rendert nur den ihm übergebenen Zustand.
 *
 * Channel [CHANNEL_ID] bewusst [NotificationManager.IMPORTANCE_LOW] - anders als der
 * Wecker-Channel (siehe `AlarmSoundService`) keine Dringlichkeit, nur ein Korrektur-Werkzeug fuer
 * den Fall, dass die automatische Verdunkelung gerade zu stark/schwach ist.
 *
 * `setOngoing(true)`: die Notification soll nicht versehentlich wegwischbar sein, solange sie
 * noch wirkt - sonst verliert der Nutzer den Korrektur-Zugriff, obwohl der Dimmer weiterhin läuft.
 * [cancel] raeumt sie exakt dann weg, wenn kein Dimm-Fenster mehr aktiv ist ODER der Nutzer-Toggle
 * ausgeschaltet ist (siehe [DimOverlayPrefs.correctionNotificationEnabled]).
 */
@Singleton
class DimCorrectionNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val CHANNEL_ID = "dim_correction"
        private const val NOTIFICATION_ID = 2101
    }

    /**
     * Welche Sperre zuletzt geloggt wurde - gegen WARN-Spam bei jedem Dimmer-Tick. `null` heisst
     * "zuletzt zustellbar", die naechste Sperre loggt also wieder.
     */
    @Volatile
    private var zuletztGemeldeteSperre: NotificationDeliverability.Zustellbarkeit? = null

    /**
     * [dienstGebunden] = laeuft [DimAccessibilityService] gerade? Ist er es NICHT, kann das
     * Overlay technisch nicht erscheinen — dann meldet diese Benachrichtigung genau das, statt
     * eine Verdunkelung zu behaupten (Vorfall 29.08.2026, Hergang in
     * [DimDiagnostik.dimmenWirkungslos]). Die drei Korrektur-Knoepfe entfallen in dem Fall
     * bewusst: sie wuerden ein Overlay heller/dunkler stellen, das es nicht gibt. An ihre Stelle
     * tritt ein Tipp-Ziel auf die Status-Karte, die den Dienst aktivieren laesst (siehe
     * [appIntent]) — eine Meldung ohne Ausweg waere nur halb so viel wert.
     */
    fun show(strength: Int, warmth: Int, paused: Boolean, dienstGebunden: Boolean) {
        createNotificationChannelIfNeeded()

        if (!dienstGebunden) {
            zeigeDienstFehltHinweis()
            return
        }

        val brighterIntent = actionIntent(DimNotificationService.ACTION_BRIGHTER, 0)
        val darkerIntent = actionIntent(DimNotificationService.ACTION_DARKER, 1)
        val pauseResumeIntent = if (paused) {
            actionIntent(DimNotificationService.ACTION_RESUME, 2)
        } else {
            actionIntent(DimNotificationService.ACTION_PAUSE, 2)
        }

        val statusText = if (paused) {
            "Dimmer pausiert"
        } else {
            "Verdunkelung: $strength %, Wärme: $warmth %"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Schicht-Dimmer")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.arrow_up_float, "Heller", brighterIntent)
            .addAction(android.R.drawable.arrow_down_float, "Dunkler", darkerIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (paused) "Fortsetzen" else "Pause",
                pauseResumeIntent
            )
            .build()

        poste(notification)
    }

    /**
     * Gemeinsamer Zustellweg beider Varianten — inklusive Zustellbarkeits-Pruefung und
     * Anti-Spam-Merker. Waere er dupliziert, wuerde die eine Variante die Sperre melden und die
     * andere nicht.
     */
    private fun poste(notification: android.app.Notification) {
        // notify() wuerde sonst lautlos verschlucken - kein Log, keine Exception. Ohne diesen
        // Check sieht eine fehlende POST_NOTIFICATIONS-Berechtigung im Logcat exakt gleich aus wie
        // der (gefixte) Bug "Toggle loest keine Neubewertung aus".
        //
        // Geprueft wird die App-Ebene UND dieser Kanal: die drei Aktionsknoepfe sind der einzige
        // Korrektur-Zugriff auf den laufenden Dimmer, und Android laesst den Nutzer einen
        // einzelnen Kanal mit zwei Tipps abschalten - areNotificationsEnabled() bleibt dabei true.
        val zustellbarkeit = NotificationDeliverability.bestimme(context, CHANNEL_ID)
        if (!zustellbarkeit.erreicht) {
            // Einmal pro Anlass: show() laeuft bei jedem Dimmer-Tick erneut, ein WARN je Durchlauf
            // waere reines Rauschen. Erst ein Zustandswechsel loggt wieder.
            if (zuletztGemeldeteSperre != zustellbarkeit) {
                zuletztGemeldeteSperre = zustellbarkeit
                Logger.w(
                    LogTags.DIMMER,
                    "Dimmer-Notification unterdrueckt ($zustellbarkeit) - Zustand und Korrektur des laufenden Dimmers sind fuer den Nutzer nicht erreichbar"
                )
            }
            return
        }
        zuletztGemeldeteSperre = null

        try {
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Der Dimmer selbst laeuft weiter; nur die Korrektur-Oberflaeche fehlt.
            Logger.w(LogTags.DIMMER, "Dimmer-Korrektur-Notification konnte nicht gepostet werden: ${e.message}")
        }
    }

    /**
     * Das Dimm-Fenster laeuft, der Dienst nicht. Bewusst derselbe Kanal und dieselbe
     * NOTIFICATION_ID: es ist derselbe Platz fuer denselben Sachverhalt ("was macht der Dimmer
     * gerade"), und zwei nebeneinander stehende Dimmer-Meldungen waeren genau die Doppelaussage,
     * die hier korrigiert werden soll. Der naechste Tick mit gebundenem Dienst ersetzt sie
     * lautlos durch die normale Korrektur-Ansicht.
     *
     * ZWEI TEXTE, weil es zwei Lagen mit verschiedener Abhilfe sind (Vorfall 26.09.2026, Hergang
     * bei [DimDiagnostik.DienstLage]): Steht der Schalter in den Android-Bedienungshilfen schon auf
     * „An", waere „Dienst ist aus … zum Aktivieren tippen" eine falsche Auskunft — der Nutzer fand
     * dort einen eingeschalteten Schalter und keinen Weg weiter.
     */
    private fun zeigeDienstFehltHinweis() {
        val eingeschaltet = DimAccessibilityService.istEingeschaltet(context)
        val kurz: String
        val lang: String
        if (eingeschaltet) {
            kurz = "Dimmt nicht — Bedienungshilfen-Dienst nicht verbunden"
            lang = "Das Dimm-Fenster läuft, und der Bedienungshilfen-Dienst ist in den " +
                "Android-Einstellungen eingeschaltet — Android hat ihn aber nicht gestartet, " +
                "deshalb wird nicht verdunkelt. Zum Beheben tippen."
        } else {
            kurz = "Dimmt nicht — Bedienungshilfen-Dienst ist aus"
            lang = "Das Dimm-Fenster läuft gerade, aber der Bedienungshilfen-Dienst ist nicht " +
                "aktiv — ohne ihn kann der Bildschirm nicht verdunkelt werden. " +
                "Zum Aktivieren tippen."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Schicht-Dimmer")
            .setContentText(kurz)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lang))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(appIntent())
            .build()

        poste(notification)
    }

    fun cancel() {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)
        // Naechste Sperre gilt als neuer Anlass und darf wieder einmal loggen.
        zuletztGemeldeteSperre = null
    }

    /**
     * Tipp-Ziel des Dienst-fehlt-Hinweises: die App, nicht direkt die Bedienungshilfen-Einstellungen -
     * sonst ginge es an der Play-Pflicht-Offenlegung der Status-Karte vorbei (`DimmerAccessibilityCard`).
     * Das Extra EINSTIEG sagt MainActivity, weshalb geoeffnet wurde: sie fuehrt auf den Status-Tab und
     * rollt die Bedienungshilfen-Karte ins Bild, statt auf dem zuletzt benutzten Tab zu enden.
     *
     * Flags und `ACTION_MAIN`/`CATEGORY_LAUNCHER` kommen aus [MainActivity.einstiegIntent] - ohne
     * Letztere legte nach einem Tipp hierauf jeder Launcher-Start eine weitere MainActivity an.
     */
    private fun appIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            3,
            MainActivity.einstiegIntent(context, MainActivity.EINSTIEG_DIMMER_BEDIENUNGSHILFEN),
            // FLAG_UPDATE_CURRENT: PendingIntents vergleichen sich OHNE Extras - ein aelterer,
            // noch registrierter PendingIntent unter demselben Request-Code (aus einer Version
            // vor diesem Extra) wuerde sonst weiterverwendet und traege das Ziel nicht.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, DimNotificationService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Idempotent, sicher bei jedem [show]-Aufruf erneut aufzurufen. */
    private fun createNotificationChannelIfNeeded() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Dimmer-Korrektur",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Heller/Dunkler/Pause fuer den aktiven Schicht-Dimmer"
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
