package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

/**
 * Diagnostik des Schicht-Dimmers: **eine Zeile, ohne PII, ohne Android-Abhängigkeit.**
 *
 * WARUM ES DAS GIBT (Vorfall 24.08.2026): Der Eigentümer meldete, der Bildschirm sei „mal heller
 * und mal dunkler" geworden. Die Ursache liess sich aus dem Datei-Log **nicht rekonstruieren** —
 * [DimAccessibilityService] hatte keine einzige Log-Zeile beim Verbinden, Trennen oder Abräumen
 * des Overlays, und der häufigste Aus-Weg in [DimScheduleUseCase.applyCurrentState] („kein
 * aktives Fenster") kehrte kommentarlos zurück. Bei einer Funktion, deren ganzer Zweck sichtbar
 * auf dem Bildschirm liegt, ist das eine Lücke: man sieht hinterher nur, wann gedimmt WURDE, nie
 * wann und warum es aufhörte.
 *
 * Die Ursache war am Ende gar kein App-Fehler — die UI-Automation (`uiautomator`) verbindet sich
 * als `UiAutomation` und unterdrückt dabei alle anderen Bedienungshilfen-Dienste, also auch
 * diesen. Am Gerät belegt: die SurfaceFlinger-Layer-ID wechselte bei JEDEM Automations-Aufruf, im
 * Leerlauf nie. Genau deshalb braucht es diese Spur: **die nächste solche Beobachtung soll in
 * Minuten beantwortbar sein statt gar nicht.**
 *
 * BAUART wie [com.github.f1rlefanz.cf_alarmfortimeoffice.AlarmFullScreenActivity] sie für den
 * Weckbildschirm vorgibt (`visibilitySnapshot()`): eine Zeile, damit sie im Release-Log neben der
 * WARN-Zeile stehen kann, und **ohne Nutzertexte**. Schicht- und Regelnamen gehören nicht ins Log
 * (siehe [DimWindowResolver] beim Regelkonflikt-WARN) — hier stehen ausschliesslich Flags, Zahlen
 * und Aufzählungswerte.
 *
 * REINE FUNKTIONEN, absichtlich: Der Dienst selbst lässt sich ohne Android-Framework nicht
 * instanziieren, seine Log-Ausgabe also nicht prüfen. Was hier liegt, ist ohne Emulator testbar —
 * dasselbe Vorgehen wie beim Datei-Log (`FileLogTreeInstaller` mit injizierten Lambdas).
 */
internal object DimDiagnostik {

    /** Welcher der beiden Render-Wege gerade trägt — oder keiner. */
    enum class OverlayWeg { DISPLAY, WINDOW_MANAGER, KEINER }

    /**
     * Der Zustand des Dimm-Dienstes in einer Zeile.
     *
     * [alpha] wird auf zwei Nachkommastellen gekürzt: die Alpha-Rampe läuft in 16-ms-Schritten,
     * volle Gleitkomma-Genauigkeit wäre Rauschen ohne Aussage.
     *
     * **`Locale.ROOT`, nicht die Geräte-Sprache.** Sonst stünde auf einem deutschen Gerät
     * `alpha=0,50` und auf einem englischen `alpha=0.50` — eine Logzeile, deren Format vom
     * Nutzergerät abhängt, ist beim Vergleichen zweier Protokolle eine Stolperfalle, und ein
     * Test dagegen würde je nach Build-Maschine anders ausfallen.
     */
    fun overlaySnapshot(
        bound: Boolean,
        weg: OverlayWeg,
        alpha: Float,
        lastOverlayOn: Boolean,
        sdkInt: Int
    ): String = "bound=$bound, weg=$weg, alpha=${"%.2f".format(java.util.Locale.ROOT, alpha)}, " +
        "sollAn=$lastOverlayOn, sdk=$sdkInt"

    /**
     * Warum [DimScheduleUseCase.applyCurrentState] das Overlay abschaltet.
     *
     * Es gibt genau drei Wege, und sie sind sehr verschieden zu bewerten — deshalb tragen sie
     * unterschiedliche Log-Level (siehe [istVerdaechtig]).
     */
    enum class AbschaltGrund {
        /** Die Master-Pause ist aktiv. Der Nutzer hat das ausdrücklich so gewollt. */
        MASTER_PAUSE,

        /** Der Dimmer ist im Ganzen ausgeschaltet. Ebenfalls eine bewusste Einstellung. */
        DIMMER_AUS,

        /** Gerade läuft kein Fenster. Der Normalfall — tagsüber trifft das fast immer zu. */
        KEIN_FENSTER,

        /** Der Nutzer hat das laufende Fenster über die Korrektur-Benachrichtigung pausiert. */
        OVERRIDE_PAUSIERT,

        /**
         * Der Dimmer ist AN, es gibt Regeln — und trotzdem kein Fenster. Das kann völlig richtig
         * sein (Mittagszeit), aber es ist auch die Signatur eines stillen Ausfalls: eine Regel,
         * die nie greift, ein leerer Schichtspannen-Speicher, eine versehentlich leere
         * Fensterliste. Genau dieser Fall soll im Release-Log auffindbar sein.
         */
        KEIN_FENSTER_TROTZ_REGELN
    }

    /**
     * Bestimmt den Abschaltgrund aus den bereits vorliegenden Werten — ohne selbst etwas zu lesen,
     * damit die Entscheidung testbar bleibt und keinen zusätzlichen DataStore-Zugriff kostet.
     *
     * Die Reihenfolge ist bedeutungstragend und folgt der Reihenfolge der Gates in
     * [DimScheduleUseCase.applyCurrentState]: Master-Pause schlägt alles, dann der Hauptschalter,
     * dann die Fensterlage.
     */
    fun abschaltGrund(
        masterPause: Boolean,
        dimEnabled: Boolean,
        regelnVorhanden: Boolean,
        fensterAktiv: Boolean,
        overridePausiert: Boolean
    ): AbschaltGrund = when {
        masterPause -> AbschaltGrund.MASTER_PAUSE
        !dimEnabled -> AbschaltGrund.DIMMER_AUS
        fensterAktiv && overridePausiert -> AbschaltGrund.OVERRIDE_PAUSIERT
        regelnVorhanden -> AbschaltGrund.KEIN_FENSTER_TROTZ_REGELN
        else -> AbschaltGrund.KEIN_FENSTER
    }

    /**
     * Gehört dieser Grund ins RELEASE-Log (WARN) oder reicht DEBUG?
     *
     * Release-Logs führen nur WARN+. Alles, was der Nutzer selbst eingestellt hat — Pause,
     * Hauptschalter aus, Fenster von Hand pausiert — ist kein Vorfall und würde das Log nur
     * zumüllen. Bleibt der eine Fall, bei dem später jemand fragen wird „warum war es hell?":
     * eingeschaltet, Regeln da, trotzdem kein Fenster. Dieselbe Verzweigung nach Verdachtsmoment
     * wie beim Weckbildschirm.
     */
    fun istVerdaechtig(grund: AbschaltGrund): Boolean =
        grund == AbschaltGrund.KEIN_FENSTER_TROTZ_REGELN
}
