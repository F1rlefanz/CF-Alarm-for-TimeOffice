package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import kotlin.math.pow

/**
 * Rechnet den Pegel des sanften Weckton-Anstiegs aus - der EINZIGE Ort, an dem diese Kurve
 * entsteht. Reine Funktionen, deshalb unter Test (`LautstaerkeAnstiegTest`).
 *
 * ## Was hier NICHT passiert
 *
 * Der Anstieg ruehrt die **System-Lautstaerke nicht an**. Er skaliert ausschliesslich den eigenen
 * [android.media.MediaPlayer] per `setVolume()`, also relativ zu dem, was der Nutzer am
 * Alarm-Regler eingestellt hat. Der naheliegende Weg
 * `AudioManager.setStreamVolume(STREAM_ALARM, ...)` waere ein Griff in eine SYSTEMWEITE
 * Einstellung: stirbt der Prozess mitten im Anstieg - und ein Wecker laeuft nun einmal nachts,
 * ohne dass jemand zusieht - bliebe die Alarm-Lautstaerke des Geraets dauerhaft auf dem
 * Anfangswert stehen. Das naechste Wecken waere dann leise, ohne dass irgendetwas darauf
 * hinweist. Ein per `setVolume()` skalierter Player kann diesen Schaden nicht anrichten: er
 * verschwindet mit dem Player.
 *
 * ## Warum die Kurve nicht linear ist
 *
 * `setVolume()` nimmt eine AMPLITUDE, das Ohr hoert aber ungefaehr logarithmisch. Ein linear
 * ansteigender Amplitudenwert klingt deshalb so, als schoesse die Lautstaerke am Anfang hoch und
 * bliebe danach fast stehen - genau verkehrt herum fuer einen Wecker, der sanft anlaufen soll.
 *
 * Deshalb wird zwischen Startpegel und 1,0 GEOMETRISCH interpoliert (gleichbedeutend mit: linear
 * in Dezibel). Das ergibt einen als gleichmaessig empfundenen Anstieg:
 *
 * ```
 * pegel(p) = start^(1 - p)      p = vergangene Zeit / Dauer, geklemmt auf 0..1
 * ```
 *
 * Bei p = 0 steht dort `start`, bei p = 1 exakt `1.0` - die Kurve trifft beide Enden genau, ohne
 * Rundungsrest. Genau das ist der Grund fuer diese Form statt einer Potenzkurve mit Offset: das
 * Ende MUSS die volle Lautstaerke sein, nicht 0,98 davon.
 *
 * ## Richtung der Degradation
 *
 * Jede unklare Eingabe fuehrt zu **1,0**, also zu voller Lautstaerke ab der ersten Sekunde -
 * dem Verhalten ohne Anstieg. Ein zu lauter Wecker weckt; ein zu leiser ist der schwerste
 * denkbare Fehler dieser App. Im Zweifel klingeln.
 */
object LautstaerkeAnstieg {

    /**
     * Abstand zwischen zwei Pegelschritten.
     *
     * 200 ms sind fein genug, dass man keine Stufen hoert (bei 30 s Anstieg sind das 150
     * Schritte), und grob genug, dass die Schrittkette neben einem klingelnden Wecker nicht ins
     * Gewicht faellt. Ein `setVolume()`-Aufruf kostet praktisch nichts.
     */
    const val SCHRITT_MS = 200L

    /** Voller Pegel - der Wert, auf den jede Unklarheit degradiert. */
    const val VOLL = 1.0f

    /**
     * Untergrenze fuer den Startpegel beim RECHNEN (nicht beim Speichern - dafuer sorgt
     * [AlarmPrefs]). Verhindert, dass `0` als Startpegel hereinkommt: `0^(1-p)` ist fuer jedes
     * p < 1 ebenfalls 0, der Wecker bliebe also bis zum letzten Schritt voellig stumm und wuerde
     * dann schlagartig laut. Das ist kein Anstieg, sondern eine verspaetete Ausloesung.
     */
    const val MIN_STARTPEGEL = 0.01f

    /**
     * @param startAnteil Pegel zu Beginn, 0..1 (z. B. 0,15 fuer 15 % der eingestellten
     *   Alarm-Lautstaerke).
     * @param dauerMs Dauer des Anstiegs bis zur vollen Lautstaerke. `<= 0` heisst "kein Anstieg".
     * @param vergangenMs seit dem Start des Tons vergangene Zeit.
     * @return Faktor fuer `MediaPlayer.setVolume()`, immer in `[MIN_STARTPEGEL, 1.0]`.
     */
    fun pegel(startAnteil: Float, dauerMs: Long, vergangenMs: Long): Float {
        // Kein Anstieg gewuenscht, oder die Eingaben sind unbrauchbar (NaN faellt hier ebenfalls
        // heraus, weil jeder Vergleich mit NaN false ergibt und das `!` ihn einfaengt).
        if (dauerMs <= 0L) return VOLL
        if (!(startAnteil > 0f) || startAnteil >= VOLL) return VOLL

        val start = startAnteil.coerceAtLeast(MIN_STARTPEGEL)
        val fortschritt = (vergangenMs.toDouble() / dauerMs.toDouble()).coerceIn(0.0, 1.0)

        // Bereits am Ende: exakt VOLL zurueckgeben statt start^0 zu rechnen - kein Rundungsrest.
        if (fortschritt >= 1.0) return VOLL

        return start.toDouble().pow(1.0 - fortschritt).toFloat().coerceIn(start, VOLL)
    }

    /**
     * Wie lange nach dem Start noch nachgeregelt werden muss.
     *
     * Der Aufrufer plant damit seinen Backstop - den einen Handler-Aufruf, der am Ende
     * bedingungslos auf [VOLL] stellt, falls die Schrittkette unterwegs abgerissen ist. Ein
     * Schritt Zuschlag, damit der Backstop garantiert NACH dem letzten regulaeren Schritt liegt
     * und nicht mit ihm um denselben Millisekundenwert konkurriert.
     */
    fun backstopVerzoegerungMs(dauerMs: Long): Long = dauerMs + SCHRITT_MS
}
