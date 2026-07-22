package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Traegt die Dimm-Schicht fuer den Schicht-Dimmer. Portiert aus der eigenstaendigen
 * NachtDimmer-App (flickerfrei), hier aber DataStore-reaktiv statt SharedPreferences:
 * der Dienst beobachtet [DimOverlayPrefs.renderState] und rendert das Overlay entsprechend.
 * Ein-/Aus geschaltet wird ausschliesslich ueber [DimOverlayPrefs.setOverlayOn] (durch
 * [DimScheduleUseCase]) – dieser Dienst enthaelt KEINE Zeitplan-Logik.
 *
 * Zwei Wege:
 * 1) Ab Android 14 (API 34): fensterlose [SurfaceControl] via
 *    [attachAccessibilityOverlayToDisplay] – Compositor-Ebene ueber allem (inkl. Shade),
 *    nahtlos und flickerfrei.
 * 2) Darunter/Fallback: [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] – deckt
 *    Status-/Navigationsleiste, nicht die Shade. Wird NICHT bei System-Events neu
 *    aufgebaut (das hatte frueher geflackert).
 *
 * Datenschutz: liest KEINE Bildschirminhalte (canRetrieveWindowContent=false), filtert
 * keine Eingaben.
 */
@AndroidEntryPoint
class DimAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var prefs: DimOverlayPrefs

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Weg 1: Display-Overlay (fensterlose SurfaceControl)
    private var displaySc: SurfaceControl? = null
    private var displaySurface: Surface? = null

    // Weg 2: WindowManager-Overlay (Fallback)
    private var windowManager: WindowManager? = null
    private var wmView: View? = null

    // Sanfter Uebergang
    private var currentAlpha = 0f
    private var fadeJob: Job? = null
    private var lastColor = 0
    private var lastOverlayOn = false

    companion object {
        private const val FADE_MS = 320L
        private const val FADE_FRAME_MS = 16L

        @Volatile
        private var instance: DimAccessibilityService? = null

        /** Ist der Bedienungshilfen-Dienst gerade verbunden/aktiv? */
        fun isRunning(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        scope.launch {
            prefs.renderState
                .distinctUntilChanged { a, b -> a.overlayOn == b.overlayOn && a.color == b.color }
                .collect { render(it.overlayOn, it.color) }
        }
    }

    private fun render(overlayOn: Boolean, color: Int) {
        lastColor = color
        lastOverlayOn = overlayOn
        if (overlayOn) {
            ensureOverlay(color)
            animateTo(1f)
        } else {
            animateTo(0f)
        }
    }

    private fun ensureOverlay(color: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && ensureDisplayOverlay(color)) {
            removeWindowManagerOverlay() // nie beide gleichzeitig
        } else {
            removeDisplayOverlay()
            ensureWindowManagerOverlay(color)
        }
    }

    // --- Weg 1: Display-Overlay (ab Android 14 / API 34) ---

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun ensureDisplayOverlay(color: Int): Boolean {
        return try {
            val existing = displaySurface
            if (displaySc != null && existing != null) {
                paintSurface(existing, color)
                return true
            }
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val bounds = wm.currentWindowMetrics.bounds
            val sc = SurfaceControl.Builder()
                .setName("CFAlarmDimLayer")
                .setBufferSize(bounds.width(), bounds.height())
                .build()
            val surface = Surface(sc)
            displaySc = sc
            displaySurface = surface
            paintSurface(surface, color)

            SurfaceControl.Transaction()
                .setLayer(sc, Int.MAX_VALUE)
                .setAlpha(sc, currentAlpha)
                .setVisibility(sc, true)
                .apply()
            attachAccessibilityOverlayToDisplay(Display.DEFAULT_DISPLAY, sc)
            true
        } catch (t: Throwable) {
            removeDisplayOverlay()
            Logger.w(LogTags.DIMMER, "Display-Overlay fehlgeschlagen: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun paintSurface(surface: Surface, color: Int) {
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawColor(color, PorterDuff.Mode.SRC)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    private fun removeDisplayOverlay() {
        displaySurface?.let { runCatching { it.release() } }
        displaySurface = null
        val sc = displaySc
        if (sc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { SurfaceControl.Transaction().reparent(sc, null).apply() }
            runCatching { sc.release() }
        }
        displaySc = null
    }

    // --- Weg 2: WindowManager-Overlay (Fallback) ---

    private fun ensureWindowManagerOverlay(color: Int) {
        val wm = windowManager ?: (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .also { windowManager = it }
        val existing = wmView
        if (existing == null) {
            val v = View(this)
            v.setBackgroundColor(color)
            v.alpha = currentAlpha // VOR addView, damit der erste Frame nicht aufblitzt
            runCatching { wm.addView(v, buildWmLayoutParams()) }
                .onSuccess { wmView = v }
        } else {
            existing.setBackgroundColor(color)
        }
    }

    private fun buildWmLayoutParams(): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun removeWindowManagerOverlay() {
        val v = wmView ?: return
        runCatching { windowManager?.removeView(v) }
        wmView = null
    }

    private fun removeAllOverlays() {
        removeDisplayOverlay()
        removeWindowManagerOverlay()
    }

    // --- Sanfte Alpha-Rampe (Coroutine, Main) ---

    private fun animateTo(target: Float) {
        if (currentAlpha == target && fadeJob == null) {
            if (target == 0f) removeAllOverlays()
            return
        }
        fadeJob?.cancel()
        fadeJob = scope.launch {
            val from = currentAlpha
            val start = SystemClock.uptimeMillis()
            while (isActive) {
                val t = ((SystemClock.uptimeMillis() - start) / FADE_MS.toFloat()).coerceAtMost(1f)
                val eased = t * t * (3f - 2f * t) // smoothstep
                currentAlpha = from + (target - from) * eased
                applyAlpha(currentAlpha)
                if (t >= 1f) break
                delay(FADE_FRAME_MS)
            }
            currentAlpha = target
            applyAlpha(currentAlpha)
            if (target == 0f) removeAllOverlays()
            fadeJob = null
        }
    }

    private fun applyAlpha(a: Float) {
        val sc = displaySc
        if (sc != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching { SurfaceControl.Transaction().setAlpha(sc, a).apply() }
        }
        wmView?.alpha = a
    }

    // --- Lifecycle ---

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Bewusst leer: das Overlay wird NICHT bei System-Events neu aufgebaut (Flacker-Ursache).
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation aendert die Display-Groesse -> feste SurfaceControl neu aufbauen.
        // Das WM-Overlay (MATCH_PARENT) passt sich selbst an.
        if (lastOverlayOn && displaySc != null) {
            removeDisplayOverlay()
            ensureOverlay(lastColor)
            applyAlpha(currentAlpha)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        scope.cancel()
        removeAllOverlays()
        instance = null
        super.onDestroy()
    }
}
