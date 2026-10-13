package com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer

import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarPreAlarmRefreshScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.RufbereitschaftAbfrage
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Armiert die beiden Zeitketten neu — **Dimmer zuerst, dann DND**.
 *
 * Die EINZIGE Stelle dafuer - verstreute Kopien liessen die Zusicherung driften.
 *
 * DIE REIHENFOLGE IST DIE ZUSICHERUNG. „Nicht stören" hat im Modus „folgt dem Dimmer" keine eigene
 * Fensterquelle — es liest die Dimm-Zeitleiste LIVE über
 * [DimScheduleUseCase.previewTimelineWithStatus]. `dataStore.edit {}` kehrt erst nach persistiertem
 * Write zurück, das anschliessende DND-`enable()` sieht den neuen Stand also nur, wenn der Dimmer
 * vorher dran war. Am Fairphone gemessen (23.08.2026), als der Nachzug ganz fehlte: knapp drei
 * Stunden „Nicht stören" ohne Grund, weil DND auf dem alten Plan stehen blieb. In einer
 * Rufbereitschaftsnacht sind das verlorene Anrufe.
 *
 * [NonCancellable], weil hier ein Zustand HERGESTELLT wird. Mehrere Aufrufer hängen an einem
 * `viewModelScope`, und genau der stirbt, wenn der Nutzer die App direkt nach einer Änderung
 * verlässt — der Prefs-Wert wäre geschrieben, die Ketten hingen hinterher. Genau diese Falle hat
 * schon einmal eine Slider-Entprellung gerissen (Hergang im Skill `cfalarm-dimmer-und-dnd`).
 *
 * JEDER AUFRUF EINZELN GEFANGEN: Ein Fehlschlag der einen Kette darf die andere nicht mitreissen,
 * und beide tragen ihren eigenen Master-Pause-Backstop — sie sind also auch bei aktiver Pause
 * gefahrlos. Ein Fehlschlag hier darf ausserdem nie den auslösenden Vorgang (Import, Freigabe,
 * Umbenennung) als gescheitert melden: der ist zu diesem Zeitpunkt längst geschrieben.
 *
 * `dagger.Lazy`, weil `DndScheduleUseCase` seinerseits am Dimmer hängt — zyklusfrei, aber der Graph
 * soll die Kette erst bauen, wenn wirklich nacharmiert wird.
 *
 * DIE DRITTE KETTE, die stündliche Rufbereitschafts-Abfrage ([RufbereitschaftAbfrage]), hängt an
 * [dnd]: sie liest dieselben Eingänge wie der DND-Cutoff (Schichtspannen, `ShiftDefinition.isOnCall`,
 * freigegebene Tage). Jeder Anlass, der DND neu armiert (Umbenennung, Freigabe, Import, geändertes
 * Flag), verschiebt auch ihre nächste Abfrage — ein eigener Schalter wäre eine vierte Stelle, die
 * jemand vergisst.
 */
@Singleton
class ZeitkettenArmierer @Inject constructor(
    private val dimSchedule: dagger.Lazy<DimScheduleUseCase>,
    private val dndSchedule: dagger.Lazy<DndScheduleUseCase>,
    private val rufbereitschaftAbfrage: dagger.Lazy<RufbereitschaftAbfrage>,
    private val preAlarmRefresh: dagger.Lazy<CalendarPreAlarmRefreshScheduler>,
    /** Nur fuer [schichtStand] - der Vergleich vor/nach einem Vordergrund-Sync. */
    private val shiftSpanStore: dagger.Lazy<ShiftSpanStore>
) {
    /**
     * [anlass] steht als Präfix in der WARN-Zeile und ist die einzige Spur, aus der sich später
     * ableiten lässt, WELCHE Änderung ihre Kette nicht nachgezogen bekam. Kurz halten und in
     * Grossbuchstaben, wie die bisherigen Präfixe (`IMPORT`, `FREIGABE`, `UMBENENNUNG`).
     *
     * [dimmer] und [dnd] einzeln schaltbar: Ändert sich ausschliesslich etwas, das nur DND liest
     * (Dienstzeit-Ausnahmen, Rufbereitschafts-Flag am Schichttyp), wäre ein Dimmer-`enable()` eine vollständige
     * Fensterberechnung ohne jede Wirkung. Umgekehrt gilt das NICHT — ein geändertes Dimm-Fenster
     * zieht DND immer mit.
     */
    suspend fun armiere(anlass: String, dimmer: Boolean = true, dnd: Boolean = true) {
        if (!dimmer && !dnd) return
        withContext(NonCancellable) {
            if (dimmer) {
                runCatching { dimSchedule.get().enable() }
                    .onFailure { Logger.w(LogTags.DIMMER, "⚠️ $anlass: Dimmer-Kette nicht neu armiert", it) }
            }
            if (dnd) {
                runCatching { dndSchedule.get().enable() }
                    .onFailure { Logger.w(LogTags.DND, "⚠️ $anlass: DND-Kette nicht neu armiert", it) }
                runCatching { rufbereitschaftAbfrage.get().reschedule() }
                    .onFailure { Logger.w(LogTags.MAINTENANCE, "⚠️ $anlass: Rufbereitschafts-Abfrage nicht neu geplant", it) }
            }
        }
    }

    /**
     * ALLE vier Nebenketten nach einem Neustart oder einem Wartungslauf: Dimmer, DND,
     * Pre-Alarm-Refresh und Rufbereitschafts-Abfrage - bei [pausiert] stattdessen abgeraeumt.
     *
     * Bis v1.45 stand derselbe Block wortgleich in `BootReceiver` und `AlarmMaintenanceService`
     * (#131, G11-14). Hier, weil dieselbe Reihenfolge-Zusicherung gilt wie in [armiere]: Dimmer
     * vor DND. Ein Neustart loescht ALLE AlarmManager-Eintraege, und der Wartungslauf IST an einem
     * Rufbereitschaftstag der stuendliche Tick - ohne Neuplanung risse jede dieser Ketten ab.
     *
     * Jeder Schritt einzeln gefangen und best-effort: er darf die Wecker-Wiederherstellung bzw.
     * den Wartungslauf NIE stoeren. [anlass] ("Boot"/"Wartung") steht im WARN als Spur.
     */
    suspend fun armiereNebenketten(anlass: String, pausiert: Boolean) {
        withContext(NonCancellable) {
            runCatching { if (pausiert) dimSchedule.get().disable() else dimSchedule.get().enable() }
                .onFailure { Logger.w(LogTags.DIMMER, "$anlass: Dimm-Reschedule fehlgeschlagen", it) }
            runCatching { if (pausiert) dndSchedule.get().disable() else dndSchedule.get().enable() }
                .onFailure { Logger.w(LogTags.DND, "$anlass: DND-Reschedule fehlgeschlagen", it) }
            runCatching {
                if (pausiert) preAlarmRefresh.get().cancelAll() else preAlarmRefresh.get().reschedule()
            }.onFailure {
                Logger.w(LogTags.BACKGROUND_WORKER, "$anlass: Pre-Alarm-Refresh-Reschedule fehlgeschlagen", it)
            }
            runCatching {
                if (pausiert) rufbereitschaftAbfrage.get().cancel() else rufbereitschaftAbfrage.get().reschedule()
            }.onFailure {
                Logger.w(LogTags.MAINTENANCE, "$anlass: Rufbereitschafts-Abfrage nicht neu geplant", it)
            }
        }
    }

    /**
     * Nach einem gelungenen Wecker-Sync im VORDERGRUND (`CalendarViewModel`: App-Start, Rueckkehr,
     * "Mit Google Kalender abgleichen"): die beiden Ketten neu planen, die aus dem frisch
     * geschriebenen Bestand rechnen - Pre-Alarm-Refresh (3 h vor jedem Wecker) und
     * Rufbereitschafts-Abfrage (aus den Schichtspannen).
     *
     * WARUM (Review 1.48.0): Seit die Rueckkehr in die App neu laedt, entstehen Wecker oft im
     * Vordergrund. Bis dahin plante NUR der Wartungslauf diese Ketten - ein um 23:30 beim
     * Zurueckkehren angelegter Wecker fuer 04:30 bekam keinen Vorab-Abgleich, wenn die naechste
     * Wartung erst um 05:00 kam, obwohl Status-Karte und Website "3 Stunden vor jedem Wecker"
     * zusagen. Genauso startete eine kurzfristig eingetragene Rufbereitschaft ihre stuendliche
     * Abfrage erst mit der naechsten Wartung.
     *
     * DIMMER UND DND NUR, WENN SICH DER SCHICHTSTAND GEAENDERT HAT ([standVorher], seit 1.49.1):
     * deren `enable()` wendet den Zustand sofort am Geraet an (`applyCurrentState`) - ein blosser
     * Blick in die App soll nichts umschalten. Hat der Sync aber einen Dienst verschoben,
     * hingen Fenster, deren BEGINN an Weckzeit oder Dienst haengt („60 min vor der Weckzeit",
     * „Nicht stoeren waehrend der Dienstzeit"), bis zur naechsten Wartung - bis zu 6 h - an der
     * alten Zeit. Der neu berechnete Zustand ist dann genau der, den es ohnehin geben muesste.
     * Verglichen werden die Schichtspannen (die einzige Fenster-Grundlage, die ein Sync
     * schreibt; manuelle Wecker fasst er nicht an). Ist [standVorher] `null` (vorher nicht
     * lesbar) oder der Stand danach nicht lesbar, bleibt es beim alten Verhalten: die Wartung
     * zieht nach. Ein nur durch das Aufraeumen alter Spannen veraenderter Stand armiert
     * unnoetig, aber harmlos - die Neuberechnung ergibt denselben Zustand.
     *
     * Beide Ketten sind gefahrlos haeufig aufrufbar: `reschedule()` rechnet jeweils vom Ist-Zustand
     * und ersetzt die bisherige Planung, die Rufbereitschafts-Abfrage fragt die Master-Pause selbst,
     * und waehrend der Pause gibt es keinen Wecker, den der Pre-Alarm-Refresh einplanen koennte.
     * Jeder Schritt einzeln gefangen - der Sync ist zu diesem Zeitpunkt laengst durch.
     */
    suspend fun planeNachVordergrundSync(standVorher: List<ShiftSpan>? = null) {
        withContext(NonCancellable) {
            runCatching { preAlarmRefresh.get().reschedule() }
                .onFailure {
                    Logger.w(LogTags.BACKGROUND_WORKER, "VORDERGRUND: Pre-Alarm-Refresh nicht neu geplant", it)
                }
            runCatching { rufbereitschaftAbfrage.get().reschedule() }
                .onFailure {
                    Logger.w(LogTags.MAINTENANCE, "VORDERGRUND: Rufbereitschafts-Abfrage nicht neu geplant", it)
                }
            if (standVorher != null) {
                val standDanach = schichtStand()
                if (standDanach != null && standDanach != standVorher) {
                    // Dimmer vor DND - dieselbe Reihenfolge-Zusicherung wie in [armiere].
                    runCatching { dimSchedule.get().enable() }
                        .onFailure { Logger.w(LogTags.DIMMER, "VORDERGRUND: Dimmer-Kette nicht neu armiert", it) }
                    runCatching { dndSchedule.get().enable() }
                        .onFailure { Logger.w(LogTags.DND, "VORDERGRUND: DND-Kette nicht neu armiert", it) }
                }
            }
        }
    }

    /**
     * Der Schichtstand VOR einem Vordergrund-Sync, fuer [planeNachVordergrundSync]. `null`, wenn
     * er nicht lesbar ist - dann zieht nach dem Sync nur die naechste Wartung Dimmer und DND nach.
     */
    suspend fun schichtStand(): List<ShiftSpan>? =
        runCatching { shiftSpanStore.get().spansNow().getOrNull() }.getOrNull()
}
