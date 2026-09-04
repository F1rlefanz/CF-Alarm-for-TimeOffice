package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

/**
 * Entscheidet, ob der Bildschirm VOR der Wecker-Notification selbst geweckt wird - und wie lange
 * vorher.
 *
 * ## Warum es das gibt
 *
 * Auf dem Fairphone 6 (Android 16) startet SystemUI die herstellereigene Gesichtsentsperrung
 * `com.android.settings/.anc.unlock.UnlockActivity` **bei jedem Aufwecken des Bildschirms**. Sie
 * traegt kein `showWhenLocked` - im Settings-Manifest nachgesehen, das Attribut kommt dort
 * ueberhaupt nicht vor - und hat eine eigene `taskAffinity`. Sie landet also in einer eigenen Task
 * oberhalb des Weckbildschirms, und weil `KeyguardController.updateVisibility()` nur die OBERSTE
 * Task wertet, hebt sie die Ueberlagerung des Sperrbildschirms fuer den ganzen Display-Bereich auf.
 * Wer schon dasteht, wird verdraengt; der Wecker klingelt dann hinter dem Sperrbildschirm weiter.
 *
 * Der Auslöser ist bei uns bislang der Full-Screen-Intent SELBST: er ist der Weckgrund
 * (`WAKE_REASON_APPLICATION, details=com.android.systemui:full_screen_intent`). Unser
 * Weckbildschirm ist dadurch zwangslaeufig VOR der Gesichtsentsperrung da - und verliert.
 *
 * ## Warum ein Vorlauf hilft
 *
 * Die Google Uhr weckt den Bildschirm mit einem EIGENEN Wake-Lock und postet ihre Notification
 * erst danach; ihre Activity kommt dadurch NACH der Gesichtsentsperrung und legt sich obendrauf.
 * Am 04.09.2026 lagen beide Faelle 60 Sekunden auseinander im selben Systemlog:
 *
 * | | Abstand Wake -> eigene Activity | Ergebnis |
 * |---|---|---|
 * | CFAlarm 07:00 | 62 ms | verdraengt, 32 s ohne Weckbildschirm |
 * | Google Uhr 07:01 | 448 ms | nicht verdraengt, stabil bis zum Abstellen |
 *
 * Am Emulator ist die Umkehrung nachgestellt (04.09.2026, Android 16, ohne Fairphone-Anteil):
 * Eindringling zuerst per `am start`, Weckbildschirm 3 s spaeter - die Ueberlagerung haelt
 * (16 s spaeter noch `mKeyguardOccluded=true` und top-resumed). Mit vertauschter Reihenfolge
 * dagegen `wm_set_keyguard_occluded [0]` und `wm_stop_activity` fuer den Weckbildschirm.
 *
 * ## Warum es nur auf betroffenen Geraeten laeuft
 *
 * Gate ist der bereits vorhandene [WeckbildschirmVerdraengungPrefs]-Zaehler: erst wenn mindestens
 * EIN Weckvorgang tatsaechlich verdraengt wurde, wird vorgeweckt. Auf jedem gesunden Geraet bleibt
 * der Ablauf damit unveraendert. Und weil [WeckbildschirmVerdraengungPrefs.meldeSauberenLauf] den
 * Zaehler bei jedem sauberen Wecker zurueckstellt, verschwindet der Vorlauf von selbst, sobald es
 * aufhoert - etwa weil Fairphone es repariert oder der Nutzer die Gesichtsentsperrung entfernt.
 *
 * ## Warum ausserdem nur bei dunklem, gesperrtem Bildschirm
 *
 * Ist der Bildschirm schon an, gibt es kein Aufwecken - also auch keine Gesichtsentsperrung, die
 * verdraengen koennte. Ist kein Sperrbildschirm aktiv, gibt es nichts zu ueberlagern. In beiden
 * Faellen waere der Vorlauf reine Verzoegerung ohne Gegenwert.
 *
 * ## Richtung der Degradation
 *
 * Alles, was unklar ist, fuehrt zu 0 - also zum unveraenderten Verhalten. Ein nicht lesbarer
 * Zaehler darf einen Wecker nicht verzoegern. Der Ton haengt ohnehin nicht daran: er startet auf
 * beiden Pfaden sofort.
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
     * @param geraetIstBetroffen [WeckbildschirmVerdraengungPrefs.jeVerdraengt] - BLEIBEND, nicht
     *   der zuruecksetzbare Hinweis-Zaehler. Mit dem Zaehler als Gate schaltete sich das Vorwecken
     *   durch seinen eigenen Erfolg ab; am 04.09.2026 am Geraet gemessen.
     * @param bildschirmAn `PowerManager.isInteractive`.
     * @param gesperrt `KeyguardManager.isKeyguardLocked`.
     * @return [VORLAUF_MS], wenn vorgeweckt werden soll, sonst 0.
     */
    fun vorlaufMillis(
        geraetIstBetroffen: Boolean,
        bildschirmAn: Boolean,
        gesperrt: Boolean
    ): Long = when {
        !geraetIstBetroffen -> 0L
        bildschirmAn -> 0L
        !gesperrt -> 0L
        else -> VORLAUF_MS
    }
}
