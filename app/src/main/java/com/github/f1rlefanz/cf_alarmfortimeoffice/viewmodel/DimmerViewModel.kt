package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Die Geraete-Einstellungen des Dimmens unter "Einstellungen" (#225): Farbwaerme und Vorschau. Die
 * Bedienungshilfe braucht Context und lebt in der UI-Schicht.
 */
@HiltViewModel
class GeraeteDimmenViewModel @Inject constructor(
    private val prefs: DimOverlayPrefs,
    private val dimSchedule: DimScheduleUseCase
) : ViewModel() {

    private val vorschau = DimmVorschau(prefs, dimSchedule)

    /** Die Farbwaerme fuer Typen ohne eigene; `null`, solange noch nicht gelesen. */
    val farbwaerme: StateFlow<Int?> =
        prefs.warmth
            .map<Int, Int?> { it }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Nur [DimScheduleUseCase.enable], nicht der `ZeitkettenArmierer`: die Waerme verschiebt keine
     * Fenstergrenze. Unentprellt (Pflichtregel) - der Regler ruft erst beim Loslassen.
     */
    fun setzeFarbwaerme(wert: Int) = viewModelScope.launch {
        prefs.setWarmth(wert)
        dimSchedule.enable()
    }

    /** Zeigt das Overlay [VORSCHAU_SEKUNDEN] Sekunden mit den Vorgabewerten des Geraets. */
    fun zeigeVorschau(): Job =
        vorschau.zeige(VORSCHAU_SEKUNDEN) { prefs.strengthNow() to prefs.warmthNow() }

    companion object {
        const val VORSCHAU_SEKUNDEN = 10
    }
}

/**
 * Die Dimm-Vorschau: Overlay kurz an, danach den regulaeren Zustand wiederherstellen.
 *
 * **Das Aufraeumen darf NICHT am `viewModelScope` haengen.** [DimOverlayPrefs.setPreviewOverlay]
 * schreibt einen PERSISTENTEN Zustand; `DimAccessibilityService` beobachtet nur
 * `DimOverlayPrefs.renderState` und hat eine vom ViewModel voellig unabhaengige Lebensdauer. Wird
 * der `viewModelScope` waehrend des `delay()` gecancelt (Nutzer verlaesst die App - zweimal Zurueck
 * beendet die Activity), liefe `applyCurrentState()` nie, der Schreibvorgang aber schon: der
 * Bildschirm bliebe systemweit verdunkelt. Geheilt wuerde das erst beim naechsten Dimm-Tick - und
 * wer die Vorschau zum Ausprobieren nutzt, hat typischerweise noch kein Fenster aktiv.
 *
 * Deshalb drei Massnahmen: eigener [previewScope] (bewusst ohne `cancel()` in `onCleared()` - genau das
 * waere der Bug), Zuruecksetzen im `finally` (greift auch bei Exception und Cancellation) und dort
 * `NonCancellable`. Vorbild ist `HueLightUseCase.followUpScope`.
 *
 * Der [CoroutineExceptionHandler] ist Pflicht und nicht durch den [SupervisorJob] gedeckt: ein
 * SupervisorJob isoliert nur Geschwister; die Exception liefe trotzdem zum Thread-Default-Handler
 * und beendete den PROZESS - also den, der die Wecker haelt.
 *
 * Die Massnahmen decken nur Coroutine-Cancellation ab. Ein PROZESSTOD im Vorschau-Fenster fuehrt
 * kein `finally` aus; deshalb schreibt [DimOverlayPrefs.setPreviewOverlay] den Ablaufzeitpunkt mit
 * auf die Platte, und jeder spaetere Leser setzt das Ende der Vorschau von allein durch.
 *
 * Eine Instanz je ViewModel; der einzige Nutzer ist [GeraeteDimmenViewModel].
 */
internal class DimmVorschau(
    private val prefs: DimOverlayPrefs,
    private val dimSchedule: DimScheduleUseCase
) {
    private val previewScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Logger.e(LogTags.DIMMER, "Dimm-Vorschau: Aufraeumen fehlgeschlagen", e)
        }
    )

    private var job: Job? = null

    /** [werte] = Staerke und Waerme; gelesen erst, wenn eine laufende Vorschau aufgeraeumt hat. */
    fun zeige(sekunden: Int, werte: suspend () -> Pair<Int, Int>): Job {
        val laufende = job
        val neue = previewScope.launch {
            // Eine noch laufende Vorschau ZUERST zu Ende aufraeumen lassen: sonst schaltet deren
            // finally die gerade neu eingeschaltete Vorschau sofort wieder aus.
            laufende?.cancelAndJoin()
            val dauerMs = sekunden * 1000L
            try {
                val (staerke, waerme) = werte()
                prefs.setPreviewOverlay(
                    staerke,
                    waerme,
                    System.currentTimeMillis() + dauerMs + DimOverlayPrefs.PREVIEW_EXPIRY_GRACE_MS
                )
                delay(dauerMs)
            } finally {
                withContext(NonCancellable) { dimSchedule.applyCurrentState() }
            }
        }
        job = neue
        return neue
    }
}
