package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

/**
 * Diagnostik des Schicht-Dimmers: **eine Zeile, ohne PII, ohne Android-Abhängigkeit.**
 *
 * Zweck: wann und warum das Dimmen aufhörte, soll aus dem Log in Minuten beantwortbar sein.
 * Hergang: Skill cfalarm-dimmer-und-dnd, reference/dimmer.md.
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

    /**
     * Laeuft gerade ein Fenster, das gar nichts bewirken KANN, weil der Bedienungshilfen-Dienst
     * nicht gebunden ist?
     *
     * Sonst meldet die Korrektur-Benachrichtigung eine Verdunkelung, die nicht stattfinden kann.
     * Hergang: Skill cfalarm-dimmer-und-dnd, reference/dimmer.md.
     *
     * Bewusst NUR bei aktivem, nicht pausiertem Fenster. Ohne Fenster soll ohnehin nicht gedimmt
     * werden — dort waere ein fehlender Dienst kein Vorfall, sondern eine Dauerwarnung bei jedem
     * Nutzer, der den Dimmer nie einschaltet.
     *
     * Gegenstueck zu [abschaltGrund]: dort geht es um ein Overlay, das absichtlich AUS ist; hier
     * um eines, das AN sein soll und trotzdem nicht erscheint.
     */
    fun dimmenWirkungslos(
        fensterAktiv: Boolean,
        overridePausiert: Boolean,
        dienstGebunden: Boolean
    ): Boolean = fensterAktiv && !overridePausiert && !dienstGebunden

    /**
     * In welcher Lage ist der Bedienungshilfen-Dienst — aus Sicht des NUTZERS, nicht nur der App?
     *
     * Android zeigt den SCHALTER (`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`), die App die
     * BINDUNG ([DimAccessibilityService.isRunning]); nur die Bindung dimmt. Eine `UiAutomation`
     * trennt alle Bedienungshilfen, ohne den Schalter anzufassen - die Abhilfe ist dann eine
     * andere (aus/ein, notfalls Neustart), deshalb haelt die Anzeige beide Lagen auseinander.
     * Hergang: Skill cfalarm-dimmer-und-dnd, reference/dimmer.md.
     */
    enum class DienstLage {
        /** Gebunden — das Overlay kann erscheinen. */
        VERBUNDEN,

        /** In den Android-Einstellungen nicht eingeschaltet (oder nicht lesbar). */
        AUSGESCHALTET,

        /** Schalter steht auf „An", Android hat den Dienst aber nicht gebunden. */
        EINGESCHALTET_NICHT_VERBUNDEN
    }

    /**
     * Die Bindung gewinnt: ist der Dienst gebunden, ist der Schalter unerheblich. Ist der Schalter
     * nicht lesbar, liefert der Aufrufer `false` — dann bleibt es bei der bisherigen Aussage
     * „ausgeschaltet", der Nutzer landet also nie schlechter als vor dieser Unterscheidung.
     */
    fun dienstLage(gebunden: Boolean, eingeschaltet: Boolean): DienstLage = when {
        gebunden -> DienstLage.VERBUNDEN
        eingeschaltet -> DienstLage.EINGESCHALTET_NICHT_VERBUNDEN
        else -> DienstLage.AUSGESCHALTET
    }

    /**
     * Steht der Dienst [paket]/[klasse] im Wert von `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`?
     *
     * Der Wert ist eine mit `:` getrennte Liste geplaetteter `ComponentName`s. Android schreibt die
     * volle Form (`paket/paket.dimmer.Klasse`); die Kurzform mit fuehrendem Punkt
     * (`paket/.dimmer.Klasse`) entsteht, wenn jemand den Wert per `adb shell settings put` von Hand
     * setzt, und wird von Android ebenso angenommen — deshalb erkennt dieser Vergleich beide.
     * Bewusst als Zeichenketten-Vergleich statt ueber `ComponentName.unflattenFromString`: so
     * bleibt die Entscheidung ohne Android-Framework testbar.
     */
    fun istInEingeschaltetenDiensten(einstellungsWert: String?, paket: String, klasse: String): Boolean =
        einstellungsWert.orEmpty().split(':').any { eintrag ->
            val teile = eintrag.trim().split('/', limit = 2)
            if (teile.size != 2 || teile[0] != paket) return@any false
            val eingetrageneKlasse = if (teile[1].startsWith('.')) paket + teile[1] else teile[1]
            eingetrageneKlasse == klasse
        }

    /**
     * Zusatz zur „entbunden"-Zeile: wer hat getrennt? Steht der Schalter danach noch auf „An",
     * hat NICHT der Nutzer abgeschaltet — genau die Frage, die am 26.09.2026 aus dem Log nicht zu
     * beantworten war. Der Schalter wird in `onUnbind` gelesen; beim Abschalten ueber die
     * Einstellungen ist er dort schon geschrieben, weil Android erst auf die geaenderte Einstellung
     * hin entbindet.
     */
    fun entbundenZusatz(nochEingeschaltet: Boolean): String = if (nochEingeschaltet) {
        "Schalter steht weiter auf AN - das System hat getrennt (z. B. UiAutomation/adb), " +
            "kommt kein 'verbunden' nach, bleibt das Dimmen wirkungslos"
    } else {
        "Schalter in den Bedienungshilfen ist AUS"
    }

    /**
     * Wie endete der VORIGE Lauf des Dimm-Dienstes?
     *
     * Bei einem `SIGKILL` (z. B. App-Update) laufen weder `onUnbind` noch `onDestroy`;
     * protokollierbar ist nur die RUECKKEHR. Hergang: Skill cfalarm-dimmer-und-dnd,
     * reference/dimmer.md. [DimAccessibilityService] setzt beim Verbinden einen
     * Merker und raeumt ihn beim sauberen Beenden wieder weg; steht er beim naechsten Verbinden
     * noch, wurde der Prozess dazwischen beendet. Damit ist ein „warum war es kurz hell?"
     * beantwortbar — nicht auf die Sekunde, aber mit Zeitpunkt und Ursachenklasse.
     *
     * **Die Falle ist der Geraeteneustart**: der toetet den Dienst genauso, ohne `onDestroy`, und
     * liesse den Merker ebenso stehen. Ohne die Unterscheidung stuende nach JEDEM Neustart ein
     * WARN im Release-Log und entwertete die Zeile, auf die es ankommt. Erkannt wird er an
     * [android.os.SystemClock.elapsedRealtime], das beim Booten auf null zurueckfaellt: ist die
     * gespeicherte Laufzeit GROESSER als die aktuelle, kann dazwischen nur ein Neustart liegen.
     * Die Wanduhr taugt dafuer nicht — sie kann sich auch ohne Neustart verstellen (Zeitzone,
     * NTP-Korrektur), und genau daran scheiterte die erste Ueberlegung.
     */
    enum class RueckkehrArt {
        /** Kein Merker vorhanden — erste Inbetriebnahme nach der Installation. */
        ERSTMALIG,

        /** Der vorige Lauf wurde ordentlich beendet (`onUnbind`/`onDestroy` liefen durch). */
        SAUBER,

        /** Dazwischen lag ein Geraeteneustart. Erwartbar, kein Vorfall. */
        NACH_NEUSTART,

        /**
         * Der vorige Lauf endete ohne Abmeldung: der Prozess wurde beendet (App-Update,
         * Speicherdruck, Absturz). Das ist der Fall, bei dem der Nutzer einen kurz hellen
         * Bildschirm gesehen hat.
         */
        UNERWARTET
    }

    /** Reine Entscheidung aus den bereits gelesenen Merkerwerten — siehe [RueckkehrArt]. */
    fun rueckkehrArt(
        merkerVorhanden: Boolean,
        liefZuletzt: Boolean,
        elapsedGespeichert: Long,
        elapsedJetzt: Long
    ): RueckkehrArt = when {
        !merkerVorhanden -> RueckkehrArt.ERSTMALIG
        !liefZuletzt -> RueckkehrArt.SAUBER
        elapsedJetzt < elapsedGespeichert -> RueckkehrArt.NACH_NEUSTART
        else -> RueckkehrArt.UNERWARTET
    }

    /** Gehoert diese Rueckkehr ins Release-Log (WARN)? Nur der eine Fall — vgl. [istVerdaechtig]. */
    fun istUnerwartet(art: RueckkehrArt): Boolean = art == RueckkehrArt.UNERWARTET
}
