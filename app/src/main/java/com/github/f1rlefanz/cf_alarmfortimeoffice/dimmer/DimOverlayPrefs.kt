package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.graphics.Color
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Einstellungen und Render-Zustand des Schicht-Dimmers (im bestehenden [MainDataStore]).
 *
 * EINE Fenster-Quelle, EIN Schalter (siehe [DimScheduleUseCase], [Toggles]): das
 * schicht-gekoppelte Regelsystem ([DimRule]). Die frueheren Sonderquellen "Wellness/Wind-down"
 * und "Nacht-Standard" sind entfallen - beide lassen sich seit dem Ende-Anker
 * [DimAnchor.ALARM_SONST_CLOCK] als gewoehnliche Regel ausdruecken (Wellness als Fenster
 * `ALARM -X` -> `ALARM +0`, der Nacht-Standard als `CLOCK 22:00` -> `ALARM_SONST_CLOCK 07:00`,
 * das fuer JEDE Kalendernacht gilt und keinen Folgetag-Sonderfall braucht).
 *
 * [strength]/[warmth] bleiben: sie sind der Fallback des Renderzustands und der Vorgabewert
 * fuer neue Regeln.
 *
 * [overlayOn] wird vom Scheduler gesetzt; der [DimAccessibilityService] beobachtet nur
 * [renderState] (an/aus + Farbe).
 */
@Singleton
class DimOverlayPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        /**
         * DER Dimmer-Schalter. Loest die drei frueheren Quellen-Schalter
         * (`dim_wellness_enabled` / `dim_rules_enabled` / `dim_night_default_enabled`) ab.
         *
         * BEWUSST EIN NEUER SCHLUESSEL, und die alten werden NICHT geloescht: sie bleiben eine
         * Version lang im Store liegen, damit ein Downgrade den alten Zustand noch vorfindet und
         * die Migration sie noch lesen kann. Hier werden sie nur nicht mehr gelesen.
         */
        private val KEY_DIM_ON = booleanPreferencesKey("dim_enabled")
        private val KEY_OVERLAY_ON = booleanPreferencesKey("dim_overlay_on")

        // Ablaufzeitpunkt einer VORSCHAU (Wanduhr-Millis). Nur von setPreviewOverlay gesetzt, von
        // jedem setActiveOverlay wieder entfernt - siehe setPreviewOverlay fuer den Ablauf, der
        // ohne diesen Schluessel kaputt ging.
        private val KEY_OVERLAY_PREVIEW_UNTIL = longPreferencesKey("dim_overlay_preview_until")
        private val KEY_STRENGTH = intPreferencesKey("dim_strength")
        private val KEY_WARMTH = intPreferencesKey("dim_warmth")

        // Dimmer-Korrektur-Notification (Feature C) - Override-Zustand + Settings-Toggle. Angebunden
        // ueber NotificationSettingsViewModel/SettingsTabContent (Default AUS).
        private val KEY_OVERRIDE_STRENGTH_DELTA = intPreferencesKey("dim_override_strength_delta")
        private val KEY_OVERRIDE_PAUSED = booleanPreferencesKey("dim_override_paused")
        private val KEY_OVERRIDE_WINDOW_END = longPreferencesKey("dim_override_window_end")
        private val KEY_OVERRIDE_WINDOW_STRENGTH = intPreferencesKey("dim_override_window_strength")
        private val KEY_CORRECTION_NOTIFICATION_ENABLED = booleanPreferencesKey("dim_correction_notification_enabled")

        /** Schrittweite von Heller/Dunkler in der Korrektur-Notification. Wirkt NUR auf strength. */
        const val OVERRIDE_STEP = 10

        // Farbe, die der Service gerade rendert (Intensität des AKTIVEN Fensters). Getrennt von
        // KEY_STRENGTH/KEY_WARMTH (= globale Nutzer-Slider), damit der Scheduler-Schreibzugriff und
        // die „Darstellung"-Slider sich nicht überschreiben. Fällt zurück auf die globalen Werte.
        private val KEY_RENDER_STRENGTH = intPreferencesKey("dim_render_strength")
        private val KEY_RENDER_WARMTH = intPreferencesKey("dim_render_warmth")

        /**
         * Zuschlag auf die Vorschau-Dauer, mit dem der Ablaufzeitpunkt auf Platte geschrieben wird.
         *
         * Im Normalfall soll das prozessinterne Aufraeumen (`finally` -> `applyCurrentState()`)
         * gewinnen, weil nur DAS den regulaeren Zustand kennt (ein gerade aktives Dimm-Fenster
         * bleibt dann an). Der Ablaufzeitpunkt ist der Auffangpfad fuer den Fall, dass es diesen
         * Aufruf nie gibt - er darf deshalb nicht schon waehrend der laufenden Vorschau greifen.
         */
        const val PREVIEW_EXPIRY_GRACE_MS = 2_000L

        const val STRENGTH_MAX = 85
        const val WARMTH_MAX = 100
        const val DEFAULT_STRENGTH = 55
        const val DEFAULT_WARMTH = 40

        fun overlayColor(strength: Int, warmth: Int): Int {
            val alpha = Math.round(strength / 100.0 * 255.0).toInt()
            val r = Math.round(warmth / 100.0 * 90.0).toInt()
            val g = Math.round(warmth / 100.0 * 28.0).toInt()
            return Color.argb(alpha, r, g, 0)
        }
    }

    /** Was der Service rendert. */
    data class RenderState(val overlayOn: Boolean, val strength: Int, val warmth: Int) {
        val color: Int get() = overlayColor(strength, warmth)
    }

    /**
     * DER Dimmer-Schalter. Bewusst weiterhin ein eigener Typ statt eines nackten `Boolean`:
     * ueber ihn beantwortet [DimScheduleUseCase] die Frage "ist ueberhaupt eine Quelle an", und
     * diese Frage soll benannt bleiben (sie steuert Keep-alive- gegen Retry-Tick).
     */
    data class Toggles(val dimEnabled: Boolean)

    // Serialisiert JEDEN Read-Modify-Write auf den Override-Zustand ueber ALLE Aufrufer hinweg -
    // DimNotificationService's Aktions-Handler (Heller/Dunkler/Pause) UND DimScheduleUseCase.
    // applyCurrentState()'s eigenes Stale-Clearing lesen beide overrideNow() und schreiben ggf.
    // zurueck, aber aus unabhaengigen, potenziell gleichzeitig laufenden Kontexten (Tick-Alarm,
    // 6h-Wartung, Boot, jeder DimmerViewModel-Setter, Notification-Button-Tap). Ein rein lokaler
    // Mutex in einem der Aufrufer schuetzt nur gegen sich selbst, nicht gegen die anderen - dieser
    // Singleton ist der einzige Ort, an dem ALLE Aufrufer tatsaechlich dieselbe Instanz teilen.
    private val overrideMutex = Mutex()

    /** Serialisiert [block] gegen jeden anderen Aufrufer dieser Funktion - siehe [overrideMutex]. */
    suspend fun <T> withOverrideLock(block: suspend () -> T): T = overrideMutex.withLock { block() }

    /**
     * Temporärer Nutzer-Override für die Dimmer-Korrektur-Notification (Feature C). Persistiert im
     * DataStore, nicht in-memory - übersteht damit einen Prozess-Neustart von
     * [DimAccessibilityService]/[DimScheduleReceiver], die beide keine garantierte Lebensdauer
     * haben. [windowEnd] + [windowStrength] (= `range.last`/`strength` der aktiven Spanne) sind der
     * Gültigkeits-Schlüssel: gilt nur für dieselbe aktive Fenster-Spanne wie beim Setzen - `windowEnd`
     * allein reicht nicht, weil mehrere Regel-Fenster sich denselben Anker (oft die Weckzeit)
     * teilen können, siehe [DimWindowResolver.isOverrideStale].
     */
    data class Override(val strengthDelta: Int, val paused: Boolean, val windowEnd: Long, val windowStrength: Int)

    /**
     * ALLE Lese-Flows dieser Klasse gehen hierueber, keiner mehr direkt auf `dataStore.data`.
     *
     * Vorher war jeder Flow ein blankes `dataStore.data.map{}` ohne ein einziges `.catch` - waehrend
     * AuthDataStoreRepository, ShiftConfigRepository und DataStoreTokenRepository fuer denselben
     * Store-Typ alle eines haben. Der `ReplaceFileCorruptionHandler` des Stores faengt nur
     * Korruption; eine IOException (voller Speicher, EACCES, transienter Lesefehler) reicht DataStore
     * durch. Der gefaehrlichste Konsument ist [DimAccessibilityService]: er sammelt [renderState] in
     * einem eigenen Scope, dessen SupervisorJob nur Geschwister isoliert - die Exception lief zum
     * Thread-Default-Handler und beendete den PROZESS, der die Alarme haelt. Fuer eine WECKER-App ist
     * das die falsche Reihenfolge der Wichtigkeit (dieselbe Ueberlegung wie bei
     * HueBridgeConnectionManager.healthCheckScope).
     *
     * Degradiert wird auf LEERE Preferences, also auf die Defaults jedes einzelnen Flows. Fuer den
     * Dimmer ist das die fail-safe Richtung: [renderState] faellt damit auf `overlayOn = false`, im
     * Zweifel wird also NICHT verdunkelt. Ein unerwartet dunkler Bildschirm ist deutlich schlimmer
     * als ein unerwartet heller - bei voller Verdunkelung kann der Nutzer sein Geraet nicht mehr
     * bedienen und den Dimmer nicht mehr abschalten.
     *
     * Und die Notlage-Leere wird nicht zur Schreibwahrheit (die Invariante aus CLAUDE.md,
     * "Persistenz"): jeder Setter geht ueber `dataStore.edit{}` mit eigenem Read, keiner speist
     * einen dieser Flows zurueck. Die einzige Stelle, die einen gelesenen Wert weiterschreibt, ist
     * `DimScheduleUseCase`s `setActiveOverlay(false, strengthNow(), warmthNow())` - dort landen die
     * Defaults nur in den abgeleiteten RENDER-Schluesseln, die ohnehin bei jedem
     * `applyCurrentState()` mit den Werten der aktiven Spanne neu geschrieben werden, und zwar
     * zusammen mit `overlayOn = false`. Die vom Nutzer eingestellten Werte (KEY_STRENGTH/
     * KEY_WARMTH und die Toggles) schreibt nur die UI mit ihren eigenen Eingaben - eine
     * Nutzer-Einstellung kann durch diese Degradierung also nicht verloren gehen.
     */
    private val safeData: Flow<Preferences> = dataStore.data
        .catch { e ->
            Logger.e(LogTags.DIMMER, "Dimmer-Einstellungen nicht lesbar - degradiert auf Defaults (kein Dimmen)", e)
            emit(emptyPreferences())
        }

    /**
     * Was der Dienst rendern soll - inklusive der SELBSTDURCHSETZUNG eines abgelaufenen
     * Vorschau-Zustands.
     *
     * Der Ablauf, der ohne diese Auswertung kaputt ging: die Vorschau schaltet mit
     * [setPreviewOverlay] persistent EIN, zurueckgenommen wurde sie aber ausschliesslich von einem
     * Timer IM PROZESS (`delay()` + `finally`). Stirbt der Prozess in diesem Fenster (Absturz,
     * "Beenden erzwingen", App-Update, OEM-Task-Killer), laeuft kein `finally` - `NonCancellable`
     * deckt nur Coroutine-Cancellation ab. Android bindet den [DimAccessibilityService] danach neu,
     * der liest hier `overlayOn = true` und baut das Overlay wieder auf: der Bildschirm ist
     * SYSTEMWEIT bis zu 85 % verdunkelt, in jeder App, und in Screenshots nicht einmal sichtbar.
     * Geheilt haette das erst der naechste `applyCurrentState()`-Aufruf - und wer die Vorschau zum
     * Ausprobieren nutzt, hat typischerweise noch keine Fenster-Quelle an, also auch keinen
     * rollenden Tick; bis zum 6h-Wartungslauf konnten so Stunden vergehen.
     *
     * Deshalb traegt die Vorschau ihren Ablaufzeitpunkt MIT AUF DIE PLATTE, und jeder spaetere
     * Leser setzt ihn von allein durch: abgelaufen heisst hier `overlayOn = false`, ganz ohne
     * Schreibzugriff und ohne dass irgendjemand den Prozesstod bemerken muesste. Ist der Zeitpunkt
     * noch in der Zukunft, wird er hier abgewartet und danach das Aus nachgereicht - sonst bliebe
     * ein Dienst, der eine Sekunde nach dem Prozesstod neu bindet, mit einem noch gueltigen
     * Vorschau-Zustand haengen, ohne dass je wieder ein Wert nachkaeme.
     *
     * Der persistierte `true` bleibt dabei bewusst stehen: geraeumt wird er beim naechsten
     * [setActiveOverlay] durch den Scheduler, der als Einziger den regulaeren Zustand kennt.
     */
    val renderState: Flow<RenderState> = channelFlow {
        // collectLatest, nicht collect: das Warten auf den Ablauf darf den Upstream nicht
        // blockieren - kommt waehrenddessen ein neuer Zustand (z. B. das regulaere Aufraeumen),
        // muss der ihn sofort abloesen.
        safeData.collectLatest { p ->
            val strength = (p[KEY_RENDER_STRENGTH] ?: p[KEY_STRENGTH] ?: DEFAULT_STRENGTH).coerceIn(0, STRENGTH_MAX)
            val warmth = (p[KEY_RENDER_WARMTH] ?: p[KEY_WARMTH] ?: DEFAULT_WARMTH).coerceIn(0, WARMTH_MAX)
            val on = p[KEY_OVERLAY_ON] ?: false
            val previewUntil = p[KEY_OVERLAY_PREVIEW_UNTIL] ?: 0L

            if (!on || previewUntil <= 0L) {
                send(RenderState(on, strength, warmth))
                return@collectLatest
            }

            val remaining = previewUntil - System.currentTimeMillis()
            if (remaining > 0L) {
                send(RenderState(true, strength, warmth))
                delay(remaining)
            } else {
                Logger.w(
                    LogTags.DIMMER,
                    "Vorschau-Overlay war abgelaufen (seit ${-remaining} ms) und wird nicht gerendert - " +
                        "vermutlich starb der Prozess waehrend der Vorschau"
                )
            }
            send(RenderState(false, strength, warmth))
        }
    }

    val toggles: Flow<Toggles> = safeData.map { p -> Toggles(dimEnabled = p[KEY_DIM_ON] ?: false) }

    val strength: Flow<Int> = safeData.map { (it[KEY_STRENGTH] ?: DEFAULT_STRENGTH).coerceIn(0, STRENGTH_MAX) }
    val warmth: Flow<Int> = safeData.map { (it[KEY_WARMTH] ?: DEFAULT_WARMTH).coerceIn(0, WARMTH_MAX) }

    val override: Flow<Override> = safeData.map { p ->
        Override(
            strengthDelta = p[KEY_OVERRIDE_STRENGTH_DELTA] ?: 0,
            paused = p[KEY_OVERRIDE_PAUSED] ?: false,
            windowEnd = p[KEY_OVERRIDE_WINDOW_END] ?: 0L,
            windowStrength = p[KEY_OVERRIDE_WINDOW_STRENGTH] ?: 0
        )
    }

    /** Settings-Toggle fuer die Dimmer-Korrektur-Notification. Default AUS - angebunden ueber
     * NotificationSettingsViewModel/SettingsTabContent. */
    val correctionNotificationEnabled: Flow<Boolean> = safeData.map {
        it[KEY_CORRECTION_NOTIFICATION_ENABLED] ?: false
    }

    suspend fun togglesNow(): Toggles = toggles.first()
    suspend fun strengthNow(): Int = strength.first()
    suspend fun warmthNow(): Int = warmth.first()
    suspend fun overrideNow(): Override = override.first()
    suspend fun correctionNotificationEnabledNow(): Boolean = correctionNotificationEnabled.first()

    suspend fun setDimEnabled(v: Boolean) = dataStore.edit { it[KEY_DIM_ON] = v }

    /**
     * Setzt An/Aus UND die Render-Farbe (Intensität/Wärme des gerade aktiven Fensters). Der Scheduler
     * ruft das mit den Werten der aktiven Spanne; die globalen [strength]/[warmth] bleiben unberührt
     * (sie sind Renderzustand-Fallback + Editor-Default). Für Vorschau: mit den globalen Werten aufrufen.
     */
    suspend fun setActiveOverlay(on: Boolean, strength: Int, warmth: Int) = dataStore.edit {
        it[KEY_OVERLAY_ON] = on
        it[KEY_RENDER_STRENGTH] = strength.coerceIn(0, STRENGTH_MAX)
        it[KEY_RENDER_WARMTH] = warmth.coerceIn(0, WARMTH_MAX)
        // Der Scheduler ist die Wahrheit ueber den regulaeren Zustand: sein Schreibvorgang beendet
        // jede Vorschau. Bliebe der Ablaufzeitpunkt stehen, wuerde [renderState] ein voellig
        // regulaeres, gerade eingeschaltetes Dimm-Fenster faelschlich als abgelaufene Vorschau
        // abschalten.
        it.remove(KEY_OVERLAY_PREVIEW_UNTIL)
    }

    /**
     * Schaltet das Overlay fuer eine VORSCHAU ein - mit einem Ablaufzeitpunkt, der MIT auf die
     * Platte geht.
     *
     * Getrennt von [setActiveOverlay], weil die beiden gegensaetzliche Zusicherungen tragen: der
     * Scheduler stellt einen Zustand her, der bis zu seinem naechsten Lauf gilt, die Vorschau einen,
     * der von allein enden MUSS. Ohne den persistierten Ablauf endete sie nur, solange der Prozess
     * lebt (siehe [renderState]) - ein Prozesstod im Vorschau-Fenster liess den Bildschirm
     * systemweit verdunkelt zurueck.
     *
     * @param expiresAtMillis Wanduhr-Zeitpunkt, ab dem das Overlay als erloschen GILT, auch wenn
     *        niemand mehr schreibt. Der Aufrufer schlaegt [PREVIEW_EXPIRY_GRACE_MS] auf die
     *        Vorschaudauer auf, damit im Normalfall das prozessinterne Aufraeumen gewinnt.
     */
    suspend fun setPreviewOverlay(strength: Int, warmth: Int, expiresAtMillis: Long) = dataStore.edit {
        it[KEY_OVERLAY_ON] = true
        it[KEY_RENDER_STRENGTH] = strength.coerceIn(0, STRENGTH_MAX)
        it[KEY_RENDER_WARMTH] = warmth.coerceIn(0, WARMTH_MAX)
        it[KEY_OVERLAY_PREVIEW_UNTIL] = expiresAtMillis
    }
    suspend fun setStrength(v: Int) = dataStore.edit { it[KEY_STRENGTH] = v.coerceIn(0, STRENGTH_MAX) }
    suspend fun setWarmth(v: Int) = dataStore.edit { it[KEY_WARMTH] = v.coerceIn(0, WARMTH_MAX) }

    /**
     * Schreibt Delta/Pause/Fenster-Schlüssel ATOMAR zusammen. Ein Teil-Update (z. B. nur `paused`
     * ändern) würde sonst einen alten, eigentlich schon veralteten `strengthDelta`-Wert unter einem
     * neuen [windowEnd] unbeabsichtigt "wiederbeleben" - der Aufrufer ([DimNotificationService])
     * liest daher vorher IMMER den effektiven (nicht-stale) Zustand und schreibt ihn hier komplett
     * zurück.
     */
    suspend fun setOverride(strengthDelta: Int, paused: Boolean, windowEnd: Long, windowStrength: Int) = dataStore.edit {
        it[KEY_OVERRIDE_STRENGTH_DELTA] = strengthDelta
        it[KEY_OVERRIDE_PAUSED] = paused
        it[KEY_OVERRIDE_WINDOW_END] = windowEnd
        it[KEY_OVERRIDE_WINDOW_STRENGTH] = windowStrength
    }

    suspend fun clearOverride() = dataStore.edit {
        it.remove(KEY_OVERRIDE_STRENGTH_DELTA)
        it.remove(KEY_OVERRIDE_PAUSED)
        it.remove(KEY_OVERRIDE_WINDOW_END)
        it.remove(KEY_OVERRIDE_WINDOW_STRENGTH)
    }

    suspend fun setCorrectionNotificationEnabled(v: Boolean) =
        dataStore.edit { it[KEY_CORRECTION_NOTIFICATION_ENABLED] = v }
}
