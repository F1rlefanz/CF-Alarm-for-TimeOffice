package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Die Zustandszeile der Ruhezeit - eine Zeile pro Wechsel, im Release-Log.
 *
 * WARUM ES DAS GIBT (05.09.2026): Die App protokollierte vom DND ausschliesslich den NAECHSTEN
 * Wechsel (`Naechster DND-Wechsel geplant: …`), nie den gesetzten ZUSTAND. Die Frage "war
 * 'Nicht stoeren' heute frueh um 05:30 aus, zusammen mit dem Dimmer?" liess sich am Tag danach
 * deshalb nicht beantworten - nur erschliessen: aus der Lage der geplanten Grenzen und aus
 * `lastActivation` der Zen-Regel.
 *
 * **Androids eigenes Zen-Protokoll taugt als Ersatz NICHT.** Am Fairphone 6 gemessen: Googles
 * Digital Wellbeing ("Schlafenszeit", `com.google.android.apps.wellbeing`) ruft im MINUTENTAKT
 * `setAutomaticZenRuleState` auf seiner eigenen, sogar abgeschalteten Regel auf
 * (`config: setAzrState … (ORIGIN_APP) no changes`). Der Abschnitt `State Changes` im Zen Log von
 * `dumpsys notification` fasst 100 Eintraege und ist bei drei Eintraegen je Minute nach gut einer
 * halben Stunde ueberschrieben - fuer eine Frage vom Vortag ist er leer. Wer sich darauf
 * verlaesst, steht ohne Beleg da. (Das Unterlog `Interception Events` daneben wird nicht geflutet
 * und reicht weiter zurueck, beantwortet aber nur, WELCHE Benachrichtigung unterdrueckt wurde -
 * nicht, ob die Regel an war.)
 *
 * Das Vorbild ist [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimDiagnostik]: der Dimmer
 * protokolliert seit laengerem jeden Aus-Weg mit GRUND, und genau deshalb war die Dimmer-Frage
 * desselben Morgens in einer Zeile beantwortet (`Dimmen aus - Grund=KEIN_FENSTER_TROTZ_REGELN`).
 *
 * Reine Funktion ohne Android-Abhaengigkeit, damit der Text ohne Geraet pruefbar ist.
 */
object DndDiagnostik {

    private val UHRZEIT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Warum die Ruhezeit AUS ist. Reihenfolge = Reihenfolge der Pruefung in `applyCurrentState`. */
    enum class AusGrund {
        /** Master-Pause: die App laesst nichts schalten, bis der Nutzer sie aufhebt. */
        MASTER_PAUSE,

        /** Weder "Folgt dem Dimmer" noch "Waehrend der Dienstzeit" ist eingeschaltet. */
        KEINE_QUELLE,

        /** Eine Quelle ist an, hat aber kein Fenster geliefert (freier Tag, Urlaubswoche, Lesefehler). */
        KEIN_FENSTER_TROTZ_QUELLE,

        /** Fenster gibt es, gerade laeuft nur keines - der Normalfall tagsueber. */
        AUSSERHALB
    }

    /**
     * @param aktiv das gerade laufende Fenster, oder `null`.
     * @param grund nur ausgewertet, wenn [aktiv] `null` ist.
     * @param fensterGesamt Anzahl berechneter Fenster - macht "ausserhalb" nachvollziehbar.
     */
    fun zustandszeile(
        aktiv: DndFenster?,
        grund: AusGrund,
        fensterGesamt: Int,
        zone: ZoneId
    ): String = if (aktiv != null) {
        val von = UHRZEIT.format(Instant.ofEpochMilli(aktiv.range.first).atZone(zone))
        val bis = UHRZEIT.format(Instant.ofEpochMilli(aktiv.range.last).atZone(zone))
        val zusatz = if (aktiv.geklippt) ", auf den Rufbereitschaft-Cutoff geklippt" else ""
        "🔕 Ruhezeit AN - Fenster $von-$bis (${aktiv.quelle.anzeige}$zusatz)"
    } else {
        val warum = when (grund) {
            AusGrund.MASTER_PAUSE -> "Master-Pause aktiv"
            AusGrund.KEINE_QUELLE -> "keine Fenster-Quelle eingeschaltet"
            AusGrund.KEIN_FENSTER_TROTZ_QUELLE -> "Quelle an, aber kein Fenster berechnet"
            AusGrund.AUSSERHALB -> "ausserhalb aller $fensterGesamt Fenster"
        }
        "🔔 Ruhezeit AUS - $warum"
    }
}

/** Woher ein DND-Fenster stammt. Rufbereitschaft ist KEINE Quelle - sie klippt nur. */
enum class DndQuelle(val anzeige: String) {
    FOLGT_DIMMER("folgt dem Dimmer"),
    DIENSTZEIT("Dienstzeit")
}

/**
 * Ein DND-Fenster samt Herkunft.
 *
 * Die Herkunft dient AUSSCHLIESSLICH der Diagnose - fuer die Entscheidung "an oder aus" bleibt DND
 * binaer, jede aktive Quelle genuegt (siehe Klassenkommentar von [DndScheduleUseCase]). Wer daraus
 * eine Vorrang-Regel baut, aendert das Verhalten; hier steht nur, was ins Log soll.
 */
data class DndFenster(
    val range: LongRange,
    val quelle: DndQuelle,
    val geklippt: Boolean = false
)
