package com.github.f1rlefanz.cf_alarmfortimeoffice.dnd

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Die Zustandszeile der Ruhezeit - eine Zeile pro Wechsel, im Release-Log.
 *
 * Ohne sie liess sich die Frage "war Nicht stoeren heute frueh aus?" am Tag danach nicht
 * beantworten: die App protokollierte nur den naechsten Wechsel, und Androids Zen-Log ist nach
 * einer halben Stunde ueberschrieben (Logcat-Fallstrick, Skill cfalarm-dimmer-und-dnd).
 * Vorbild ist [com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimDiagnostik].
 *
 * Reine Funktion ohne Android-Abhaengigkeit, damit der Text ohne Geraet pruefbar ist.
 */
object DndDiagnostik {

    private val UHRZEIT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /** Warum die Ruhezeit AUS ist. Reihenfolge = Reihenfolge der Pruefung in `applyCurrentState`. */
    enum class AusGrund {
        /** Master-Pause: die App laesst nichts schalten, bis der Nutzer sie aufhebt. */
        MASTER_PAUSE,

        /**
         * Eine Grundlage war nicht lesbar (Schicht-Konfiguration, Schichtspannen, Dimm-Fenster) und es
         * gibt kein Fenster: der 15-min-Retry ist geplant. Vor [KEINE_QUELLE] geprueft, denn ist die
         * Konfiguration unlesbar, weiss niemand, ob ein Schalter an ist (#218).
         */
        LESEFEHLER,

        /** Kein Typ und nicht die feste Zeile hat „Solange gedimmt wird“ oder „Waehrend des Kalendereintrags“ an (#218). */
        KEINE_QUELLE,

        /** Ein Schalter ist an, hat aber kein Fenster geliefert (freier Tag, Urlaubswoche, Typ ohne Dimm-Fenster). */
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
            AusGrund.LESEFEHLER -> "Grundlage nicht lesbar, neuer Versuch in 15 min"
            AusGrund.KEINE_QUELLE -> "kein Typ hat einen Schalter fuer Nicht stoeren an"
            AusGrund.KEIN_FENSTER_TROTZ_QUELLE -> "Quelle an, aber kein Fenster berechnet"
            AusGrund.AUSSERHALB -> "ausserhalb aller $fensterGesamt Fenster"
        }
        "🔔 Ruhezeit AUS - $warum"
    }
}

/**
 * Woher ein DND-Fenster stammt. Rufbereitschaft ist KEINE Quelle - sie klippt nur.
 *
 * Seit #218 sind es die beiden Schalter am Typ: [FOLGT_DIMMER] = „Solange gedimmt wird“,
 * [DIENSTZEIT] = „Waehrend des Kalendereintrags“. Die Log-Texte bleiben, damit alte und neue
 * Release-Logs vergleichbar sind; der Typ steht bewusst nicht im Log (Nutzertext).
 */
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
