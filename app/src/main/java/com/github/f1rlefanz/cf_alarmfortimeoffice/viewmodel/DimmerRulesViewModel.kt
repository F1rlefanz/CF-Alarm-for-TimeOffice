package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.Blockposition
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimScheduleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.HellZeitpunkt
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TageslichtEnde
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.ITageslichtRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/**
 * ViewModel für Regel-Liste und Regel-Editor. Liefert die Regeln + die Namen der erkannten
 * Schicht-Definitionen (fürs Dropdown) und stößt nach jedem Speichern/Löschen BEIDE Zeitketten
 * neu an ([com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer]) — eine geänderte Regel verschiebt Dimm-Fenster, und
 * „Nicht stören" im Modus „folgt dem Dimmer" hat keine andere Fensterquelle.
 */
@HiltViewModel
class DimmerRulesViewModel @Inject constructor(
    private val dimRuleUseCase: DimRuleUseCase,
    private val shiftUseCase: IShiftUseCase,
    private val dimSchedule: DimScheduleUseCase,
    private val armierer: ZeitkettenArmierer,
    private val prefs: DimOverlayPrefs,
    private val shiftSpanStore: ShiftSpanStore,
    /** Naechste Weckzeiten fuer die Beispielzeile unter "Nachlauf nur, solange es dunkel ist" (#140). */
    private val alarmUseCase: IAlarmUseCase,
    /**
     * Ort-Einstellungen als SIGNAL ([ITageslichtRepository.einstellungen]), aufgeloest wird
     * weiter ausschliesslich ueber [TageslichtQuelle.stand] - derselbe Weg wie zur Laufzeit.
     */
    private val tageslicht: ITageslichtRepository
) : ViewModel() {

    /**
     * Eigener Scope fuer das Aufraeumen der Regel-Vorschau — bewusst NICHT der `viewModelScope`
     * und bewusst ohne `cancel()` in `onCleared()`: genau das waere der Bug (siehe [previewRule]).
     *
     * Der [CoroutineExceptionHandler] ist Pflicht und nicht durch den [SupervisorJob] gedeckt.
     * Ein SupervisorJob isoliert nur Geschwister; die Exception liefe trotzdem zum
     * Thread-Default-Handler und beendete den PROZESS — also den, der die Wecker haelt. Fuer eine
     * Wecker-App ist das die falsche Reihenfolge der Wichtigkeit (dieselbe Lehre wie bei
     * `HueBridgeConnectionManager.healthCheckScope`).
     */
    private val previewScope = CoroutineScope(
        Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Logger.e(LogTags.DIMMER, "Regel-Vorschau: Aufraeumen fehlgeschlagen", e)
        }
    )

    private var previewJob: Job? = null

    val rules: StateFlow<List<DimRule>> = dimRuleUseCase.rules
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Namen der erkannten Schicht-Definitionen, fuer das Schicht-Muster-Dropdown im Regel-Editor.
     * Reaktiv statt Einmal-Snapshot - eine Schicht-Umbenennung/-Neuanlage ohne App-Neustart muss
     * sofort sichtbar werden. */
    val shiftNames: StateFlow<List<String>> =
        shiftUseCase.shiftConfig
            // #142: ein Krankmeldungs-Typ hat keine Dienstzeit - als Regelmuster/Ausnahme
            // waehlbar, wuerde er nie greifen.
            .map { config -> config.definitions.filterNot { it.isSickDay }.map { it.name } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Ist der Dimmer-Hauptschalter ueberhaupt an?
     *
     * WARUM DIE REGELLISTE DAS WISSEN MUSS: Ohne diesen Zustand kann der Nutzer hier Regeln bauen -
     * von Hand oder per Schnellstart -, die garantiert nichts tun, und nichts sagt es ihm. Das ist
     * dieselbe Fehlerklasse wie "angezeigt, wirkt nicht", nur aus der anderen Richtung: angelegt,
     * wirkt nicht. Der Schalter selbst sitzt im Dimmer-Reiter; hier wird sein Zustand nur
     * angezeigt und im Notfall repariert (siehe [schalteDimmerEin]).
     */
    val dimmerAn: StateFlow<Boolean> = prefs.toggles
        .map { it.dimEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * Schaltet den Dimmer EIN - bewusst eine Einbahnstrasse.
     *
     * Kein allgemeiner Setter: Ausschalten gehoert an die eine Stelle, an der der Schalter sichtbar
     * sitzt (Dimmer-Reiter). Hier gibt es nur die Reparatur des einen Zustands, in dem die
     * Regelliste sonst in die Irre fuehrt - und sie laeuft ueber denselben Nachzug wie jede andere
     * fensterrelevante Aenderung.
     */
    fun schalteDimmerEin() = viewModelScope.launch {
        prefs.setDimEnabled(true)
        armierer.armiere("REGEL")
    }

    /**
     * Regel zu einer Kennung - fuer den Editor.
     *
     * Der Rueckfall auf [zuletztAngelegteRegel] ist kein Zierrat: [rules] ist ein `stateIn` ueber
     * dem DataStore-Fluss und traegt die neue Regel erst, nachdem der Store sie emittiert hat.
     * Der Schnellstart oeffnet den Editor unmittelbar nach dem Schreiben; ohne den Rueckfall
     * traefe er ein leeres Formular an - und der Nutzer saehe genau NICHT, was entstanden ist.
     */
    fun ruleById(id: String?): DimRule? = id?.let { rid ->
        rules.value.firstOrNull { it.id == rid }
            ?: zuletztAngelegteRegel?.takeIf { it.id == rid }
    }

    fun saveRule(rule: DimRule) = viewModelScope.launch {
        dimRuleUseCase.saveRule(rule)
        armierer.armiere("REGEL")
    }

    fun deleteRule(id: String) = viewModelScope.launch {
        dimRuleUseCase.deleteRule(id)
        armierer.armiere("REGEL")
    }

    /**
     * Zeigt das Overlay kurz mit den Werten AUS DEM FORMULAR (auch ungespeichert) – analog zu
     * der frueheren Vorschau des Dimmer-Reiters, aber mit den Regel-eigenen Werten.
     * Der Bedienungshilfen-Dienst muss aktiv sein. Danach den regulären Zustand wiederherstellen.
     *
     * **Das Aufräumen darf NICHT am `viewModelScope` hängen** — exakt dieselbe Falle wie in
     * der frueheren Vorschau des Dimmer-Reiters, hier ein zweites Mal. `setPreviewOverlay(…)` schreibt
     * einen PERSISTENTEN Zustand; `DimAccessibilityService` beobachtet nur
     * `DimOverlayPrefs.renderState` und hat eine vom ViewModel völlig unabhängige Lebensdauer.
     * Wird der `viewModelScope` während des `delay()` gecancelt (Nutzer verlässt die App
     * innerhalb der 5 s — zweimal Zurück beendet die Activity), lief `applyCurrentState()` nie,
     * der Schreibvorgang aber schon: der Bildschirm bleibt systemweit verdunkelt. Geheilt würde
     * das erst beim nächsten Dimm-Tick — und wer die Vorschau zum Ausprobieren nutzt, hat
     * typischerweise noch gar keine Fenster-Quelle aktiv, es kommt also unter Umständen keiner.
     *
     * Deshalb drei Maßnahmen: eigener [previewScope], Zurücksetzen im
     * `finally` (greift auch bei Exception und Cancellation) und dort `NonCancellable`.
     * Vorbild ist `HueLightUseCase.followUpScope` — auch dort muss das Aufräumen feuern, wenn
     * der auslösende Bildschirm längst verlassen wurde.
     *
     * Die drei decken nur Coroutine-Cancellation ab. Ein PROZESSTOD im
     * Vorschau-Fenster führt kein `finally` aus, der neu gebundene `DimAccessibilityService` liest
     * den persistierten `overlayOn = true` und verdunkelt weiter. Deshalb schreibt
     * [DimOverlayPrefs.setPreviewOverlay] den Ablaufzeitpunkt mit auf die Platte — jeder spätere
     * Leser setzt das Ende der Vorschau dann von allein durch.
     */
    fun previewRule(strength: Int, warmth: Int, seconds: Int = 5): Job {
        val running = previewJob
        val job = previewScope.launch {
            // Eine noch laufende Vorschau ZUERST zu Ende aufräumen lassen: sonst schaltet deren
            // finally die gerade neu eingeschaltete Vorschau sofort wieder aus.
            running?.cancelAndJoin()
            val durationMs = seconds * 1000L
            try {
                prefs.setPreviewOverlay(
                    strength,
                    warmth,
                    System.currentTimeMillis() + durationMs + DimOverlayPrefs.PREVIEW_EXPIRY_GRACE_MS
                )
                delay(durationMs)
            } finally {
                withContext(NonCancellable) { dimSchedule.applyCurrentState() }
            }
        }
        previewJob = job
        return job
    }

    // --- Tageslicht (#140) ---------------------------------------------------------------------

    /**
     * Was die Beispielzeile braucht: Ort/Zone und die aktiven Weckzeiten. Ein Schnappschuss, den
     * [verfolgeTageslicht] beim Oeffnen des Editors holt und bei JEDER Aenderung der
     * Ort-Einstellungen erneuert - auch dann, wenn eine Standortabfrage den Ort-Bildschirm
     * ueberlebt und erst schreibt, nachdem der Nutzer schon zurueck im Editor ist (bis zu 30 s).
     * Ein Einmal-Schnappschuss behauptete dort bis zum naechsten Oeffnen "Kein Ort eingestellt",
     * waehrend die Laufzeit laengst mit dem neuen Ort rechnete.
     */
    private var tageslichtLage = TageslichtLage(TageslichtStand.ohneOrt(), emptyList())

    private data class TageslichtLage(val stand: TageslichtStand, val weckzeiten: List<Long>)

    private val _ortBekannt = MutableStateFlow<Boolean?>(null)

    /**
     * Ist ein Ort bekannt (Standort, PLZ oder Zeitzone)? `null`, solange [verfolgeTageslicht] noch
     * nicht geladen hat - der Editor zeigt dann weder Hinweis noch Beispiel. Mit `false` stuende
     * bis zum ersten Laden der rote "Kein Ort eingestellt"-Hinweis da, auch wenn ein Ort gesetzt
     * ist (Zwilling: `ortBeschreibung` im Hue-Editor). `false` heisst "kein Ort - der Nachlauf
     * gilt immer", und genau so wirkt es auch (fail-safe: voller Nachlauf).
     */
    val ortBekannt: StateFlow<Boolean?> = _ortBekannt.asStateFlow()

    private val _tageslichtGeladen = MutableStateFlow(0)

    /** Zaehlt jedes abgeschlossene Laden des Tageslicht-Stands - der Editor rechnet daran die Beispielzeile neu. */
    val tageslichtGeladen: StateFlow<Int> = _tageslichtGeladen.asStateFlow()

    /**
     * Haelt Ort und Weckzeiten fuer die Beispielzeile aktuell, solange der Aufrufer (der Editor,
     * per `LaunchedEffect`) laeuft: die erste Emission laedt, jede Aenderung der Ort-Einstellungen
     * laedt neu.
     *
     * `distinctUntilChanged` ist tragend: [ITageslichtRepository.einstellungen] haengt am GANZEN
     * `settings`-Store und gaebe sonst bei jedem fremden Schreibvorgang neu aus. Ein Lesefehler
     * des Signals wird zu einem letzten Laden (das selbst fail-safe ist), nie zu einem Abbruch
     * ohne Stand.
     */
    suspend fun verfolgeTageslicht() {
        tageslicht.einstellungen
            .distinctUntilChanged()
            .catch { e ->
                Logger.w(LogTags.DIMMER, "Ort-Einstellungen nicht beobachtbar - Editor laedt einmalig", e)
                emit(TageslichtEinstellungen.LEER)
            }
            .collect { ladeTageslicht() }
    }

    /**
     * Holt Ort und Weckzeiten. Beides ist fail-safe: [TageslichtQuelle.stand] wirft nicht
     * (gesperrt/Lesefehler = kein Ort), und ein unlesbarer Alarm-Bestand heisst nur
     * "kein Beispiel" - nie eine erfundene Weckzeit.
     */
    private suspend fun ladeTageslicht() {
        val stand = tageslicht.stand()
        val weckzeiten = alarmUseCase.getAllAlarms().getOrNull()
            ?.filter { it.isActive }
            ?.map { it.triggerTime }
            .orEmpty()
        tageslichtLage = TageslichtLage(stand, weckzeiten)
        _ortBekannt.value = stand.ort != null
        _tageslichtGeladen.value += 1
    }

    /**
     * Beispiel fuer [w] am naechsten Wecker, den dieses Fenster faengt - oder `null` (kein Ort,
     * kein passender Wecker, kein Nachlauf, [HellAb.IMMER]). Siehe [nachlaufBeispiel] im Companion.
     */
    fun nachlaufBeispiel(w: DimWindow): NachlaufBeispiel? {
        val lage = tageslichtLage
        if (lage.stand.ort == null) return null
        return nachlaufBeispiel(w, lage.stand::hellZeitpunkt, lage.weckzeiten, System.currentTimeMillis(), lage.stand.zone)
    }

    /** Welche der drei Begruendungen (`tageslicht_dimmer_grund_*`) die Beispielzeile traegt. */
    enum class BeispielGrund {
        /** Der Nachlauf laeuft voll, es wird erst danach hell. */
        NACHLAUF_VOLL,

        /** Es wird im Nachlauf hell, dort endet das Fenster. */
        HELL,

        /** Es war schon vor dem Wecken hell - Ende an der Weckzeit. */
        WECKZEIT
    }

    /**
     * Die Rechnung hinter "Nächster Wecker [weckzeit] → gedimmt bis [ende]". [grund] ist derselbe
     * Wert, den die Vorschau zeigt; [hellZeitpunkt] fehlt nur, wenn es an dem Tag kein solches
     * Ereignis gibt.
     */
    data class NachlaufBeispiel(
        val weckzeit: Long,
        val ende: Long,
        val grund: TageslichtEnde?,
        val nachlaufMin: Int,
        val hellZeitpunkt: Long?
    ) {
        /**
         * `null` = keine Begruendung zeigen. Insbesondere dann, wenn die Uhrzeit-Schranke den
         * Nachlauf gekappt hat: "volle 60 Minuten" waere dort gelogen.
         */
        val grundArt: BeispielGrund?
            get() = when {
                grund?.vorWeckzeit == true -> BeispielGrund.WECKZEIT
                grund != null -> BeispielGrund.HELL
                hellZeitpunkt != null && ende == weckzeit + nachlaufMin * 60_000L -> BeispielGrund.NACHLAUF_VOLL
                else -> null
            }
    }

    // --- Schnellstart -------------------------------------------------------------------------

    /**
     * Die drei Zeiten des Nachtdienst-Rhythmus - in SCHLAFSPRACHE, so wie der Dialog sie abfragt.
     *
     * WARUM ES DAS GIBT (18.09.2026): Der Eigentuemer konnte den Nachtdienst-Dimmer nicht
     * einrichten - "mehrdeutig, missverstaendlich, unuebersichtlich". Der Regel-Editor spricht in
     * Ankern ("Schichtende", "Zur Weckzeit") und Minuten-Offsets, und dass der Vormittagsschlaf
     * nach der Nacht Mo->Di zur Regel von MONTAG gehoert, steht nirgends. Was er sagen wollte,
     * war: "nach dem Dienst schlafe ich bis 14, nach der letzten Nacht bis 12, vor dem Dienst ab
     * 16". Genau diese drei Saetze sind die Felder hier; die Uebersetzung in Anker und
     * Blockpositionen macht [baueVorlagenRegel]. Das Ergebnis ist eine gewoehnliche, sichtbare
     * Regel - die Bequemlichkeit sitzt AUF der Faehigkeit, nicht daneben (Dimmer-Skill).
     *
     * Alle Werte in Minuten seit Mitternacht.
     */
    data class NachtdienstRhythmus(
        /** Schlaf nach dem Dienst bis - an ersten und mittleren Tagen einer Folge. */
        val schlafNachDienstBis: Int = 14 * 60,
        /** Nach der LETZTEN Nacht (und nach einem einzelnen Nachtdienst) bis - die Umstellung. */
        val nachLetzterNachtBis: Int = 12 * 60,
        /** Schlaf vor dem Dienst ab - bis zur Weckzeit. */
        val schlafVorDienstAb: Int = 16 * 60
    )

    /**
     * Eine Schnellstart-Vorlage. Sie ist ausdruecklich KEINE zweite Fenster-Quelle, sondern nur
     * eine Vorbelegung: der Knopf legt eine ganz gewoehnliche [DimRule] an, die anschliessend in
     * der Regelliste steht und sich aendern und loeschen laesst.
     *
     * GENAU DAS IST DER UNTERSCHIED ZUM ALTEN NACHT-STANDARD. Der war ein eingebautes Verhalten
     * mit eigenen Schaltern, eigener Verdunkelung und eigener Ausnahmenliste - wirksam, aber in
     * der Regelliste unsichtbar. Wer wissen wollte, warum um 07:00 noch gedimmt wird, fand dort
     * keine Regel dazu. Seit dem Ein-Modell gibt es nur noch Regeln; die Vorlagen ersetzen den
     * Komfort, den der Nacht-Standard bot, ohne seine Unsichtbarkeit zurueckzuholen.
     */
    enum class SchnellstartVorlage {
        /** Die komplette bisherige Nacht-Standard-Semantik als EIN Fenster fuer jede Kalendernacht. */
        NACHT_DIMMEN,

        /** Drei Fenster an EINEM Kalendertag, gekoppelt an eine Nachtdienst-Schicht. */
        NACHTDIENST_RHYTHMUS,

        /** Regel mit leerer Fensterliste - Unterdrueckung an den Tagen dieser Schicht. */
        SCHICHT_AUSNEHMEN;

        /** Ob die Vorlage sich auf EINE benannte Schicht bezieht und deren Namen braucht. */
        val brauchtSchicht: Boolean get() = this != NACHT_DIMMEN
    }

    /**
     * Zuletzt per Schnellstart angelegte Regel - siehe den Rueckfall in [ruleById]. Bewusst ein
     * einfaches Feld ohne Fluss: es ueberbrueckt Millisekunden, es haelt keinen Zustand.
     */
    private var zuletztAngelegteRegel: DimRule? = null

    private val _neueRegelId = MutableStateFlow<String?>(null)

    /**
     * Kennung einer soeben per Schnellstart angelegten Regel, sonst `null`.
     *
     * WARUM DAS SIGNAL EXISTIERT: Eine Regel, die der Nutzer nach dem Anlegen nicht zu Gesicht
     * bekommt, waere wieder ein unsichtbares Verhalten - genau der Fehler, den der Umbau
     * beseitigt. Der Bildschirm oeffnet auf dieses Signal hin den Editor der neuen Regel und
     * meldet sich ueber [neueRegelGeoeffnet] wieder ab, damit das nicht bei jeder
     * Neuzusammensetzung erneut geschieht.
     */
    val neueRegelId: StateFlow<String?> = _neueRegelId.asStateFlow()

    /** Vom Bildschirm zu rufen, sobald er auf [neueRegelId] reagiert hat. */
    fun neueRegelGeoeffnet() {
        _neueRegelId.value = null
    }

    /**
     * Die bereits vorhandene, aktive Regel, wegen der eine Schnellstart-Vorlage NICHT angelegt
     * wurde - Kennung und Roh-Name (die Beschriftung dazu ist ein Nutzertext und gehoert in den
     * Bildschirm).
     */
    data class SchnellstartBlockiert(val regelId: String, val regelName: String)

    private val _schnellstartBlockiert = MutableStateFlow<SchnellstartBlockiert?>(null)

    /**
     * Gesetzt, wenn ein Schnellstart-Knopf nichts angelegt hat, weil auf demselben Muster schon
     * eine aktive Regel liegt. `null` = kein Hinweis offen.
     *
     * WARUM ES DAS SIGNAL GEBEN MUSS: Ohne es waere der Knopf schlicht wirkungslos - der Nutzer
     * tippt, und nichts geschieht. Ein stiller Abbruch ist an dieser Stelle dieselbe Fehlerklasse
     * wie die tote zweite Regel, gegen die er gebaut ist.
     */
    val schnellstartBlockiert: StateFlow<SchnellstartBlockiert?> = _schnellstartBlockiert.asStateFlow()

    /** Vom Bildschirm zu rufen, sobald er den Hinweis gezeigt hat. */
    fun schnellstartHinweisGesehen() {
        _schnellstartBlockiert.value = null
    }

    /**
     * Legt die Regel zur Vorlage an und armiert danach BEIDE Zeitketten - ueber denselben Weg wie
     * [saveRule] und bewusst nicht daran vorbei: eine neue Regel verschiebt Dimm-Fenster, und
     * "Nicht stoeren" im Modus "folgt dem Dimmer" hat keine andere Fensterquelle.
     *
     * [regelName] kommt aus dem Bildschirm, nicht von hier: er ist ein Nutzertext und gehoert in
     * `strings.xml` - dieselbe Trennung wie bei [RegelVerdraengt].
     *
     * Fehlt einer schichtbezogenen Vorlage der Schichtname, wird NICHTS geschrieben. Eine Regel
     * auf leerem Muster traefe keine Schicht und wuerde auch vom Umbenennungs-Nachzug nicht
     * erfasst - eine tote Regel anzulegen waere schlechter als gar keine.
     */
    fun legeVorlageAn(
        vorlage: SchnellstartVorlage,
        regelName: String,
        schichtName: String? = null,
        rhythmus: NachtdienstRhythmus = NachtdienstRhythmus()
    ) = viewModelScope.launch {
        val regel = baueVorlagenRegel(vorlage, regelName, schichtName, rhythmus)
        if (regel == null) {
            Logger.w(
                LogTags.DIMMER,
                "⚠️ Schnellstart '$vorlage' ohne Schichtnamen - es wurde KEINE Regel angelegt"
            )
            return@launch
        }

        // KEINE ZWEITE AKTIVE REGEL AUF DEMSELBEN MUSTER. Siehe [vorhandeneRegelFuerVorlage]:
        // sie waere tot, stuende aber als aktiv in der Liste, und ihr Editor ginge sofort auf.
        val vorhanden = vorhandeneRegelFuerVorlage(vorlage, schichtName, dimRuleUseCase.getAllRules())
        if (vorhanden != null) {
            Logger.w(
                LogTags.DIMMER,
                "⚠️ Schnellstart '$vorlage' abgebrochen - auf diesem Muster liegt bereits die " +
                    "aktive Regel ${vorhanden.id}; eine zweite waere wirkungslos"
            )
            _schnellstartBlockiert.value = SchnellstartBlockiert(vorhanden.id, vorhanden.name)
            return@launch
        }

        dimRuleUseCase.saveRule(regel)
        zuletztAngelegteRegel = regel
        armierer.armiere("REGEL")
        Logger.business(
            LogTags.DIMMER,
            "✨ Schnellstart-Regel '$vorlage' angelegt (${regel.windows.size} Fenster)"
        )
        _neueRegelId.value = regel.id
    }

    private val _timeline = MutableStateFlow<List<DimWindowResolver.ResolvedInterval>>(emptyList())
    val timeline: StateFlow<List<DimWindowResolver.ResolvedInterval>> = _timeline.asStateFlow()

    /** Berechnet die Dimm-Vorschau aus den Regeln neu - siehe
     * [DimScheduleUseCase.previewTimeline]. Ohne Seiteneffekt auf den echten Scheduler. */
    fun refreshTimeline() = viewModelScope.launch {
        _timeline.value = dimSchedule.previewTimeline()
    }

    /**
     * Eine Regel wird an [tage] Kalendertagen des Planungshorizonts von einer anderen verdraengt;
     * dort gilt stattdessen eine der Regeln aus [gewinnerNamen] (Roh-Namen aus [DimRule.name], ein
     * leerer Name bleibt leer - die Ersatzbeschriftung dafuer ist ein Nutzertext und gehoert in den
     * Bildschirm, nicht ins ViewModel).
     */
    data class RegelVerdraengt(val tage: Int, val gewinnerNamen: List<String>)

    private val _verdraengteRegeln = MutableStateFlow<Map<String, RegelVerdraengt>>(emptyMap())

    /**
     * Je Regel-ID: an wie vielen Tagen sie hinter einer anderen Regel zurueckstehen muss, und
     * hinter welcher. Leer = kein Konflikt (oder die Regel-Quelle ist ganz aus).
     *
     * **Warum dieses Feld existiert:** [DimWindowResolver.buildRuleSpans] entscheidet den Konflikt
     * zweier Regeln an einem Tag zugunsten der frueheren Schicht - bis dahin sichtbar
     * ausschliesslich als Logzeile. Die Regelliste zeigte beide Regeln unveraendert als aktiv:
     * "angezeigt, wirkt nicht". Genau diese Karte rendert den Wert (siehe `DimmerSettingsScreen`);
     * ein Zustand, den niemand rendert, waere hier so falsch wie eine Renderstelle ohne Zustand.
     */
    val verdraengteRegeln: StateFlow<Map<String, RegelVerdraengt>> = _verdraengteRegeln.asStateFlow()

    /**
     * Rechnet [verdraengteRegeln] neu - reine Auskunft, ohne Seiteneffekt auf den Scheduler.
     *
     * Gerechnet wird auf derselben Grundlage wie der echte Scheduler: die Schichtspannen aus dem
     * [ShiftSpanStore] (NICHT der Alarm-Bestand - ein Alarm ueberlebt die Weckzeit nicht) und
     * dieselbe Regelauswahl ueber [DimRuleUseCase.findRuleForShift].
     *
     * Zwei bewusste Leer-Ausgaenge, beide in Richtung "nichts behaupten":
     * - **Dimmer aus** (`toggles.dimEnabled == false`): dann wirkt KEINE Regel, und ein
     *   Hinweis "diese hier wirkt an 3 Tagen nicht" waere eine Halbwahrheit, die die eigentliche
     *   Ursache verdeckt.
     * - **Schichtspannen nicht lesbar**: ohne Dienstplan ist unbekannt, an welchen Tagen mehrere
     *   Dienste zusammentreffen. Lieber kein Hinweis als ein falscher - dieselbe Richtung wie der
     *   fail-open des Schedulers.
     */
    fun refreshVerdraengteRegeln() = viewModelScope.launch {
        val alleRegeln = dimRuleUseCase.getAllRules()
        val spans = shiftSpanStore.spansNow().getOrNull()
        if (spans == null || !prefs.togglesNow().dimEnabled) {
            _verdraengteRegeln.value = emptyMap()
            return@launch
        }
        val zone = ZoneId.systemDefault()
        val konflikte = DimWindowResolver.findRuleConflicts(
            alarms = spans.map { DimWindowResolver.AlarmSlot(it.alarmTriggerTime, it.shiftName, it.endTime) },
            horizonDays = DimWindowResolver.KONFLIKT_HORIZONT_TAGE,
            today = LocalDate.now(zone),
            zone = zone,
            ruleForShift = { name -> dimRuleUseCase.findRuleForShift(name, alleRegeln) },
        )
        val namen = alleRegeln.associate { it.id to it.name }
        _verdraengteRegeln.value = konflikte
            .flatMap { k -> k.shadowedRuleIds.map { id -> id to k } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, tage) ->
                RegelVerdraengt(
                    // Distinct: ein Tag zaehlt einmal, auch wenn dort mehrere Schichten dieselbe
                    // Regel verdraengen.
                    tage = tage.map { it.date }.distinct().size,
                    gewinnerNamen = tage.mapNotNull { namen[it.winningRuleId] }.distinct().sorted()
                )
            }
    }

    companion object {
        /**
         * Reine Rechnung zu [nachlaufBeispiel] - ueber [DimWindowResolver.nachlaufEnde], also
         * DIESELBE Formel wie die Wirkung.
         *
         * Gewaehlt wird die frueheste aktive Weckzeit nach [jetzt], die dieses Fenster faengt. Das
         * laesst sich nur fuer einen CLOCK-Start allein aus dem Fenster beantworten (die Nacht
         * beginnt am letzten Start vor dem Wecker und endet an der naechsten Schranke danach); fuer
         * ALARM-/SHIFT_END-Starts haengt es an der Schicht - dort lieber kein Beispiel als ein
         * falsches.
         */
        internal fun nachlaufBeispiel(
            w: DimWindow,
            hell: HellZeitpunkt,
            weckzeiten: List<Long>,
            jetzt: Long,
            zone: ZoneId
        ): NachlaufBeispiel? {
            if (w.endAnchor != DimAnchor.ALARM_SONST_CLOCK) return null
            if (w.weckzeitNachlaufMinuten <= 0 || w.nachlaufBisHell == HellAb.IMMER) return null
            if (w.startAnchor != DimAnchor.CLOCK) return null
            val weckzeit = weckzeiten.sorted().firstOrNull { it > jetzt && clockFensterFaengt(w, it, zone) }
                ?: return null
            val ende = DimWindowResolver.nachlaufEnde(
                weckzeit, w.endClockMinutes, w.weckzeitNachlaufMinuten, w.nachlaufBisHell, zone, hell
            )
            val tag = Instant.ofEpochMilli(weckzeit).atZone(zone).toLocalDate()
            return NachlaufBeispiel(
                weckzeit = weckzeit,
                ende = ende.zeit,
                grund = ende.tageslicht,
                nachlaufMin = w.weckzeitNachlaufMinuten.coerceIn(0, DimWindowResolver.NACHLAUF_MAX_MINUTEN),
                hellZeitpunkt = hell(tag, w.nachlaufBisHell)
            )
        }

        /**
         * Faengt ein Fenster mit CLOCK-Start die [weckzeit]? Start = die letzte Start-Uhrzeit ECHT
         * vor dem Wecker, Schranke = die erste Ende-Uhrzeit ECHT nach dem Start - dieselben
         * Vergleiche wie in der Fensteraufloesung (Wanduhrzeit auf Kalendertagen, keine 24-h-Millis).
         */
        private fun clockFensterFaengt(w: DimWindow, weckzeit: Long, zone: ZoneId): Boolean {
            fun wand(d: LocalDate, minuten: Int) =
                d.atStartOfDay().plusMinutes(minuten.toLong()).atZone(zone).toInstant().toEpochMilli()
            val tag = Instant.ofEpochMilli(weckzeit).atZone(zone).toLocalDate()
            val start = wand(tag, w.startClockMinutes).let { if (it >= weckzeit) wand(tag.minusDays(1), w.startClockMinutes) else it }
            val startTag = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            var schranke = wand(startTag, w.endClockMinutes)
            var d = startTag
            while (schranke <= start) {
                d = d.plusDays(1)
                schranke = wand(d, w.endClockMinutes)
            }
            return weckzeit < schranke
        }

        /**
         * Die bereits vorhandene, AKTIVE Regel auf demselben Muster - oder `null`, wenn die
         * Vorlage angelegt werden darf.
         *
         * WARUM DIESE PRUEFUNG NOETIG IST: `DimRuleUseCase.findRuleForShift` und
         * `findRuleForFreeDay` nehmen den ERSTEN Treffer. Eine zweite aktivierte Regel auf
         * demselben `shiftPattern` wird also NIE gefragt - sie ist tot, steht aber unveraendert
         * als aktiv in der Regelliste, und der Schnellstart oeffnet direkt danach ihren Editor.
         * Der Nutzer stellt dort Zeiten und Verdunkelung ein, und nichts davon wirkt je. Der
         * Konflikt-Hinweis der Regelliste (`verdraengteRegeln`) faengt das NICHT ab: der entsteht
         * aus VERSCHIEDENEN Regeln, die an EINEM Tag verschiedene Schichten treffen - zwei Regeln
         * auf demselben Muster liefern fuer jede Schicht dieselbe (erste) Regel.
         *
         * Betroffen waeren zuerst die migrierten Nutzer: nach `DimmerModellMigration` liegt bei
         * ihnen bereits eine aktive UNIVERSAL-Regel bzw. je ausgenommener Schicht eine
         * Ausnahme-Regel.
         *
         * Der Vergleich ist genau der der Auswahl: nur AKTIVIERTE Regeln zaehlen (eine
         * ausgeschaltete wird nicht gefragt, eine neue daneben ist also nicht tot), Schichtnamen
         * gross-/kleinschreibungsblind, die Sondermuster exakt.
         */
        fun vorhandeneRegelFuerVorlage(
            vorlage: SchnellstartVorlage,
            schichtName: String?,
            alle: List<DimRule>
        ): DimRule? {
            val muster = if (vorlage.brauchtSchicht) {
                schichtName?.takeIf { it.isNotBlank() } ?: return null
            } else {
                DimRule.SHIFT_UNIVERSAL
            }
            return alle.firstOrNull { it.enabled && it.shiftPattern.equals(muster, ignoreCase = true) }
        }

        /**
         * Baut die Regel einer Schnellstart-Vorlage - rein und ohne Seiteneffekt, damit die
         * Fenster-Anker pruefbar sind, ohne den Speicherweg nachzustellen.
         *
         * `null`, wenn eine schichtbezogene Vorlage keinen brauchbaren Schichtnamen hat.
         */
        fun baueVorlagenRegel(
            vorlage: SchnellstartVorlage,
            regelName: String,
            schichtName: String?,
            rhythmus: NachtdienstRhythmus = NachtdienstRhythmus()
        ): DimRule? {
            val schicht = schichtName?.takeIf { it.isNotBlank() }
            if (vorlage.brauchtSchicht && schicht == null) return null
            return when (vorlage) {
                // EIN Fenster fuer JEDE Kalendernacht: der CLOCK-Start macht es
                // schicht-unabhaengig, der Ende-Anker nimmt das Minimum aus Weckzeit und 07:00.
                // Genau diese beiden Eigenschaften ersetzen den alten Nacht-Standard samt seinem
                // Fensterpaar und der Bedingung "ausser der Folgetag hat selbst einen Wecker".
                SchnellstartVorlage.NACHT_DIMMEN -> DimRule(
                    name = regelName,
                    shiftPattern = DimRule.SHIFT_UNIVERSAL,
                    windows = listOf(
                        DimWindow(
                            startAnchor = DimAnchor.CLOCK,
                            startClockMinutes = 22 * 60,
                            endAnchor = DimAnchor.ALARM_SONST_CLOCK,
                            endClockMinutes = 7 * 60
                        )
                    ),
                    strength = DimOverlayPrefs.DEFAULT_STRENGTH,
                    warmth = DimOverlayPrefs.DEFAULT_WARMTH
                )

                // DREI Fenster an EINEM Kalendertag, Zeiten aus [rhythmus]: nach dem Dienst der
                // Vormittagsschlaf (ab Schichtende bis 14:00 - nach der LETZTEN Nacht des Blocks
                // nur bis 12:00, weil dort die Umstellung zurueck auf den Tag ansteht), am
                // Nachmittag der Vorschlaf (16:00 bis zur Weckzeit). Weil die Regel spezifisch
                // ist, verdraengt sie an diesen Tagen die UNIVERSAL-Nachtregel vollstaendig - die
                // Nacht selbst bleibt also hell, und das ist der Sinn der Sache: da ist der
                // Nutzer im Dienst. Ein alleinstehender Nachtdienst zaehlt wie ein letzter.
                SchnellstartVorlage.NACHTDIENST_RHYTHMUS -> DimRule(
                    name = regelName,
                    shiftPattern = schicht!!,
                    windows = listOf(
                        DimWindow(
                            startAnchor = DimAnchor.SHIFT_END,
                            startOffsetMinutes = 0,
                            endAnchor = DimAnchor.CLOCK,
                            endClockMinutes = rhythmus.schlafNachDienstBis,
                            blockPositionen = setOf(Blockposition.ERSTER, Blockposition.MITTLERER)
                        ),
                        DimWindow(
                            startAnchor = DimAnchor.SHIFT_END,
                            startOffsetMinutes = 0,
                            endAnchor = DimAnchor.CLOCK,
                            endClockMinutes = rhythmus.nachLetzterNachtBis,
                            blockPositionen = setOf(Blockposition.LETZTER, Blockposition.EINZELNER)
                        ),
                        DimWindow(
                            startAnchor = DimAnchor.CLOCK,
                            startClockMinutes = rhythmus.schlafVorDienstAb,
                            endAnchor = DimAnchor.ALARM,
                            endOffsetMinutes = 0,
                            // Vorschlaf = SELBER Tag. Wecker vor dieser Uhrzeit: kein Fenster,
                            // statt 21,5 h ab dem Vortag (#151).
                            startNurAmWecktag = true
                        )
                    ),
                    strength = DimOverlayPrefs.DEFAULT_STRENGTH,
                    warmth = DimOverlayPrefs.DEFAULT_WARMTH
                )

                // Die LEERE Fensterliste ist bedeutungstragend: "an diesen Tagen NICHT dimmen".
                // Sie verdraengt die UNIVERSAL-Regel, ohne selbst ein Fenster beizusteuern -
                // niemals auf "keine Regel" wegoptimieren.
                SchnellstartVorlage.SCHICHT_AUSNEHMEN -> DimRule(
                    name = regelName,
                    shiftPattern = schicht!!,
                    windows = emptyList()
                )
            }
        }
    }
}
