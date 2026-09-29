package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

/**
 * Entscheidet, ob der Bildschirm VOR der Wecker-Notification selbst geweckt wird - und wie lange
 * vorher. Zweck: die Gesichtsentsperrung des Fairphone 6 startet bei jedem Aufwecken und
 * verdraengt einen Weckbildschirm, der schon dasteht; kommt er nach ihr, legt er sich obendrauf.
 *
 * - Liest NUR Systemzustand, keinen gespeicherten Merker - der waere im Direct Boot nicht lesbar
 *   und liesse den ersten Wecker nach einem naechtlichen Neustart ungeschuetzt.
 * - Nur bei dunklem UND gesperrtem Bildschirm; sonst gibt es nichts zu verdraengen.
 * - Jede Unklarheit fuehrt zu 0, also zum unveraenderten Verhalten.
 * - Der Ton startet unabhaengig davon sofort; verzoegert wird nur die Bedienoberflaeche.
 *
 * Hergang und Messwerte: .claude/skills/cfalarm-wecker-und-boot/reference/vorwecken.md.
 */
object VorweckEntscheidung {

    /**
     * Vorlauf in Millisekunden.
     *
     * 600 ms, weil die Gesichtsentsperrung des FP6 am 04.09.2026 137 ms nach dem Wake startete und
     * die Google Uhr mit 448 ms Abstand unbehelligt blieb - der Wert liegt bewusst ueber beidem.
     * Fuer einen Wecker ist er nicht spuerbar, zumal der Ton in dieser Zeit bereits laeuft.
     */
    const val VORLAUF_MS = 600L

    /**
     * @param bildschirmAn `PowerManager.isInteractive`.
     * @param gesperrt `KeyguardManager.isKeyguardLocked`.
     * @return [VORLAUF_MS], wenn vorgeweckt werden soll, sonst 0.
     */
    fun vorlaufMillis(
        bildschirmAn: Boolean,
        gesperrt: Boolean
    ): Long = when {
        bildschirmAn -> 0L
        !gesperrt -> 0L
        else -> VORLAUF_MS
    }
}
