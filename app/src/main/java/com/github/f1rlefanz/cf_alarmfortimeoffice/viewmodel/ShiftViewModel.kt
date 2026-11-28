package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import java.time.ZoneId
import java.time.LocalDate
import java.time.Instant
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.HellAb
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TageslichtEnde
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.HellZeitpunkt
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindowResolver
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimWindow
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimAnchor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.state.CalendarStateHolder
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimOverlayPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRule
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.DimRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TypDimmQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.dnd.DndPrefs
import com.github.f1rlefanz.cf_alarmfortimeoffice.error.ErrorHandler
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.repository.interfaces.IHueConfigRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.HueRuleUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.usecase.TypLichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.CalendarEvent
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.OhneEintragWahl
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.SchichttypEinordnen
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IAlarmUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.usecase.interfaces.IShiftUseCase
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.LetzterSchichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftCodeSuggester
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpan
import com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftSpanStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.LichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.Quelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.TagesleisteQuellen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.VorlaufMinuten
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.alsQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tagesleiste.fehlerAlsUnbekannt
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtStand
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ShiftUiState(
    val isLoading: Boolean = false,
    val currentShiftConfig: ShiftConfig? = null,
    val recognizedShifts: List<ShiftInfo> = emptyList(),
    val upcomingShift: ShiftInfo? = null,
    val error: String? = null,
    /**
     * Hinweis zum Regel-Nachzug beim UMBENENNEN einer Schicht - bewusst ein EIGENER Kanal neben
     * [error].
     *
     * WARUM NICHT [error] (Regression aus Pruefrunde 8): Diese Meldung sagt "gespeichert, aber
     * eine von dir eingerichtete Funktion wirkt nicht mehr" - etwas anderes als ein gescheiterter
     * Lade- oder Speichervorgang. Und vor allem: [error] wird von [processCalendarEvents] bei
     * JEDEM Durchgang auf `null` gesetzt, und genau so ein Durchgang folgt unmittelbar auf das
     * Speichern (`delay(200)`, sobald Events vorliegen - im Normalbetrieb also immer). Die Meldung
     * war damit gesetzt und Sekundenbruchteile spaeter wieder weg, bevor sie irgendwo ankam.
     *
     * Dieses Feld ueberlebt den folgenden Ladevorgang und wird ausschliesslich vom Nutzer
     * ([clearRegelNachzugHinweis]) bzw. nach dem Anzeigen geloescht. Gerendert wird es im
     * `ShiftConfigScreen` (dort steht der Nutzer beim Umbenennen) als bleibende Karte und im
     * `MainContentScreen` als Snackbar (dort steht er beim Konfigurations-Import). Beide Screens
     * schliessen sich gegenseitig aus, es zeigt also immer genau einer.
     */
    val regelNachzugHinweis: String? = null,
    /**
     * Kuerzel, die im Kalender des Nutzers vorkommen, aber von keinem Erkennungsmuster getroffen
     * werden - siehe [com.github.f1rlefanz.cf_alarmfortimeoffice.shift.ShiftCodeSuggester].
     * Bewusst nur Vorschlaege: zugeordnet wird von Hand.
     */
    val codeSuggestions: ShiftCodeSuggester.SuggestionResult =
        ShiftCodeSuggester.SuggestionResult(emptyList(), 0),
    /**
     * Was der letzte Abgleich ueber die Schichten wusste - NUR fuer die Anzeige, wenn der Kalender
     * gerade nicht erreichbar ist (Karte "Naechste Schicht"). `null` = nicht lesbar oder noch nicht
     * gelesen; die Karte zeigt dann den bisherigen Hinweis. Daraus entsteht nie ein Wecker.
     */
    val letzterSchichtStand: LetzterSchichtStand? = null,
    /**
     * Was die Seite „Ohne Kalendereintrag“ zuletzt beauftragt hat und was noch gespeichert wird - sonst
     * `null`. Gelesen wird ueber [ohneEintragStand].
     */
    val ohneEintragBeauftragt: ShiftConfig? = null
) {
    /**
     * Der Stand der festen Zeile: der beauftragte, solange er gespeichert wird, sonst der gespeicherte. Die
     * Seite zeigt ihn, und die naechste Aenderung rechnet auf ihm - sie hat keinen Entwurf, und
     * [currentShiftConfig] zieht erst nach dem Speichern nach.
     */
    val ohneEintragStand: ShiftConfig? get() = ohneEintragBeauftragt ?: currentShiftConfig
}

/** Was das Blatt „Weitere Optionen“ der Typ-Seite aus dem Bestand braucht (#224). */
data class WeitereOptionenStand(
    /** Die Farbwaerme aus den Einstellungen (`dim_warmth`), die ein Typ ohne eigenen Wert benutzt. */
    val vorgabeWaerme: Int,
    /** Die aktiven Weckzeiten fuer die Beispielzeile zum Nachlauf. */
    val weckzeiten: List<Long>
)

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

/**
 * Die Beispielzeile zum Tageslicht-Nachlauf (#140) - ueber [DimWindowResolver.nachlaufEnde], also
 * DIESELBE Formel wie die Wirkung.
 *
 * Gewaehlt wird die frueheste aktive Weckzeit nach [jetzt], die dieses Fenster faengt. Das
 * laesst sich nur fuer einen CLOCK-Start allein aus dem Fenster beantworten (die Nacht
 * beginnt am letzten Start vor dem Wecker und endet an der naechsten Schranke danach); fuer
 * ALARM-/SHIFT_END-Starts haengt es an der Schicht - dort lieber kein Beispiel als ein
 * falsches. `null` auch ohne passenden Wecker, ohne Nachlauf und bei [HellAb.IMMER].
 */
fun nachlaufBeispiel(
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
 * ShiftViewModel - liest Kalender-Events aus dem CalendarStateHolder, nicht vom CalendarViewModel.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val shiftUseCase: IShiftUseCase,
    private val alarmUseCase: IAlarmUseCase,
    private val calendarStateHolder: CalendarStateHolder,
    private val errorHandler: ErrorHandler,
    /**
     * Fuer den Muster-Nachzug beim Umbenennen einer Schicht ([zieheRegelmusterNach]). Statuszeile
     * [schichtFolgen] und [tagesleisteQuellen] lesen die Dimm-Regeln seit #219 NICHT mehr: Dimmen und
     * "Nicht stoeren" liegen am Typ. [hueRuleUseCase] bleibt der Statuszeile bewusst fern, siehe
     * [hueConfigRepository]; die Tagesleiste greift nur ueber [vorlaufMinuten] darauf zu, also erst,
     * wenn `licht()` die Vorlauf-Rechnung braucht.
     *
     * BEWUSST `dagger.Lazy`, wie in `CFAlarmApplication`: dieses ViewModel entsteht beim
     * App-Start. Direkt injiziert wuerde damit der komplette Hue-Graph (ApiClient/OkHttp mit
     * eigenem TrustManager) mit aufgebaut, obwohl der Nutzer vielleicht nie eine Schicht
     * umbenennt - und Hue womoeglich gar nicht nutzt.
     */
    private val dimRuleUseCase: dagger.Lazy<DimRuleUseCase>,
    private val hueRuleUseCase: dagger.Lazy<HueRuleUseCase>,
    /**
     * Nach einem geaenderten Dimm-Regelmuster muessen die Fenster neu berechnet und die
     * Tick-Kette neu armiert werden. Der Vorlaeufer ist `ConfigBackupUseCase`: auch
     * dort armiert ein generischer Schreiber beide Ketten selbst, sonst stuende die neue Regel im
     * Store, waehrend bis zur naechsten 6h-Wartung nach dem ALTEN Plan gedimmt wird.
     */
    private val armierer: ZeitkettenArmierer,
    /**
     * Die DRITTE Stelle, die ueber den Schichtnamen bindet: die Dienstzeit-Ausnahmen von
     * "Nicht stoeren" ([DndPrefs.renameShiftName]). Sie wurde beim Nachzug in v1.30.0
     * uebersehen - warum das teuer ist, steht an der Funktion. Die Rufbereitschaft-Auswahl war
     * bis v1.40.8 eine weitere solche Liste; sie ist heute ein Flag AM Schichttyp
     * (`ShiftDefinition.isOnCall`) und reist bei einer Umbenennung von selbst mit.
     *
     * Die Nacht-Ausnahmen des Dimmers waren einmal eine VIERTE solche Stelle; mit dem
     * eingebauten Nacht-Standard ist auch seine Namensliste entfallen. Der Dimmer bindet nur
     * noch ueber `DimRule.shiftPattern`, und das zieht `dimRuleUseCase` oben nach.
     *
     * Gelesen wird er seit #219 nicht mehr (Statuszeile und Tagesleiste lesen den Typ).
     *
     * Ebenfalls `dagger.Lazy`, aus demselben Grund wie oben: dieses ViewModel entsteht beim
     * App-Start, und die Klasse soll erst angefasst werden, wenn wirklich umbenannt wird oder
     * jemand die Statuszeile abonniert.
     */
    private val dndPrefs: dagger.Lazy<DndPrefs>,
    /**
     * Nur LESEND, fuer [ShiftUiState.letzterSchichtStand] und die Schichtspannen von
     * [tagesleisteQuellen]. `dagger.Lazy` wie oben: der Read passiert erst nach der ersten
     * Event-Emission bzw. beim Abo, nicht beim Bauen des ViewModels.
     */
    private val shiftSpanStore: dagger.Lazy<ShiftSpanStore>,
    /**
     * Nur LESEND, fuer die Vorgabe-Farbwaerme in [weitereOptionenStand] (#224); den Hauptschalter `dim_enabled`
     * liest seit #219 niemand mehr. `dagger.Lazy` wie oben: angefasst erst, wenn das Blatt offen ist.
     */
    private val dimOverlayPrefs: dagger.Lazy<DimOverlayPrefs>,
    /**
     * Nur LESEND, fuer die Statuszeile [schichtFolgen] (Licht) und [tagesleisteQuellen]: ob eine Bridge
     * eingerichtet ist ([bridgeEingerichtet]). Die Lichtregeln liegen seit #253 am Typ. BEWUSST das
     * Repository und NICHT `hueRuleUseCase.get()`: das Repository liest nur den `@HueDataStore`, der
     * Use-Case dagegen baut den Lampen- und HTTP-Graphen (OkHttp) - fuer eine Anzeige, die keine Bridge
     * braucht.
     */
    private val hueConfigRepository: dagger.Lazy<IHueConfigRepository>,
    /**
     * Nur LESEND, fuer den Tageslicht-Stand von [tagesleisteQuellen]: derselbe Weg wie zur Laufzeit
     * (Entsperr-Gate VOR jedem Lesen, nie werfend, sonst [TageslichtStand.ohneOrt]). `dagger.Lazy` wie
     * oben - angefasst erst beim Abo. Dagger liefert immer den vollen Konstruktor; der Default erspart
     * nur den Tests den Parameter (wie `HueRuleUseCase`) und heisst: kein Ort, keine Einschraenkung.
     */
    private val tageslichtQuelle: dagger.Lazy<TageslichtQuelle> = dagger.Lazy { TageslichtQuelle.OHNE }
) : ViewModel() {

    private val _uiState = MutableStateFlow(ShiftUiState())
    val uiState: StateFlow<ShiftUiState> = _uiState.asStateFlow()

    /**
     * Die Konfiguration, die DIESES ViewModel gerade selbst schreibt - damit
     * [observeExternalConfigChanges] sie nicht fuer eine fremde Aenderung haelt. Gesetzt VOR dem
     * Write (DataStore veroeffentlicht vor `onSuccess`), geloescht beim ersten Treffer.
     * Hergang: schichterkennung.md.
     *
     * MUSS VOR dem `init{}`-Block stehen - der Collector im `init{}` kann synchron anlaufen.
     */
    @Volatile
    private var selfWrittenConfig: ShiftConfig? = null

    /**
     * Was jede AKTIVE Schicht ausserhalb des Weckers ausloest - die Statuszeile
     * "Dimmen: … · Licht: … · Nicht stören: …" im `ShiftConfigScreen` (Issue #70). Schluessel ist die
     * `ShiftDefinition.id`; eine DEAKTIVIERTE Definition fehlt bewusst (ihre Karte sagt schon
     * "kein Dimmer- und kein DND-Fenster", "Dimmen: 2 Fenster" widerspraeche dem).
     *
     * Dimmen, "Nicht stoeren" (seit #219) und die Lichtregeln (seit #253, [TypLichtQuelle], wie zur
     * Laufzeit) liest die Zeile am TYP - aus derselben Konfiguration, aus der die Liste ohnehin besteht,
     * also ohne eigenen Lesefehler. Nur "Bridge eingerichtet" braucht noch einen Store.
     *
     * GELESEN WIRD NUR BEIM ABO (`WhileSubscribed`), nichts beim Bauen: die Hue-Quelle steckt in
     * einem `flow {}`, das sein `dagger.Lazy` erst beim Sammeln anfasst. Dieses ViewModel entsteht
     * beim App-Start, und ein frueher CE-Store-Read waere genau der fremde Leser, vor dem
     * CLAUDE.md ("Persistenz") warnt.
     *
     * Lesefehler: "Bridge eingerichtet" ist ein `Result` ([bridgeEingerichtet]); ein Fehlschlag wird
     * "nicht lesbar", nie "keine Bridge" (#210).
     *
     * MUSS VOR dem `init{}`-Block stehen (wie [selfWrittenConfig]) und nach [_uiState].
     */
    val schichtFolgen: StateFlow<Map<String, SchichtFolgen>> = combine(
        _uiState.map { it.currentShiftConfig }.distinctUntilChanged(),
        bridgeEingerichtet()
    ) { config, bridge ->
        val lichtRegeln = Result.success(config?.let { TypLichtQuelle(it).regeln }.orEmpty())
        config?.definitions.orEmpty()
            // #142: ein Krankmeldungs-Typ stellt nie einen Wecker und spannt keine Schicht auf -
            // DND-, Dimm- oder Licht-Aussagen ueber ihn waeren erfunden. Die Karte zeigt
            // stattdessen ihre eigene Statuszeile.
            .filter { it.isEnabled && !it.isSickDay }
            .associate { definition ->
                definition.id to ermittleSchichtFolgen(
                    definition = definition,
                    hueRegeln = lichtRegeln,
                    hueKonfiguriert = bridge.getOrNull()
                )
            }
    }
        .catch { e ->
            // Reine Anzeige: lieber keine Zeile als ein abgestuerzter Screen. Kein Wecker haengt daran.
            Logger.w(LogTags.SHIFT, "Statuszeile je Schicht nicht berechenbar", e)
            emit(emptyMap())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /**
     * Zaehler, der die Schichtspannen von [tagesleisteQuellen] neu lesen laesst - hochgezaehlt am Ende jedes
     * Alarm-Syncs nach einer Konfigurationsaenderung ([triggerAlarmCreationFromConfigUpdate]). Der Sync
     * SCHREIBT die Spannen neu, waehrend die Eventliste dieselbe bleibt; ohne diesen Ausloeser sah die
     * Leiste nach dem Speichern oder Umbenennen eines Typs weiter den Stand von davor. MUSS VOR
     * [tagesleisteQuellen] stehen (Initialisierungsreihenfolge).
     */
    private val spannenAusloeser = MutableStateFlow(0)

    /**
     * `IHueRuleUseCase.getPreAlarmSunriseLeadMinutes` fuer `licht()` der Tagesleiste. Die Lambda
     * fasst [hueRuleUseCase] erst beim AUFRUF an - nie beim Bauen und nie beim Abo von
     * [tagesleisteQuellen]. MUSS VOR [tagesleisteQuellen] stehen (Initialisierungsreihenfolge).
     */
    private val vorlaufMinuten: VorlaufMinuten = { regeln, schichtname, weckzeit, stand ->
        hueRuleUseCase.get().getPreAlarmSunriseLeadMinutes(regeln, schichtname, weckzeit, stand)
    }

    /**
     * Alles, was die Tagesleiste der Typ-Seite aus dem Bestand braucht (Umbau #196, SPEC 3.3 Punkt 3):
     * die Schicht-Konfiguration (Dimmen und "Nicht stoeren" liegen seit #219 am Typ, die Lichtregeln seit
     * #253), "Bridge eingerichtet", die Schichtspannen und der Tageslicht-Stand.
     *
     * DIESELBEN Stores und DASSELBE Abo wie [schichtFolgen]: gelesen wird erst beim Sammeln
     * (`WhileSubscribed`), nichts beim Bauen - die Quellen stecken in kalten `flow {}`.
     *
     * `null` = noch nicht gelesen. [Quelle.Unbekannt] heisst dagegen LESEFEHLER und wird je Quelle
     * EINZELN gefuehrt (Fang VOR dem `combine`): eine unlesbare Quelle macht nur ihre Spuren
     * unbekannt ([TagesleisteQuellen.stand]), und sie wird NIE zur leeren Liste - "kein Typ hat ein
     * Fenster" hiesse "kein Dimmen". Anders als bei [schichtFolgen] steht hinter dem `combine` deshalb
     * KEIN Fang, der alles auf "leer" setzt.
     *
     * Dimmen und "Nicht stoeren" kommen seit #219 aus der Konfiguration ([typenQuelle], ein `Result`):
     * scheitert der Read, stehen beide Spuren auf "unbekannt" (#210). Das Licht haengt seit #253 an
     * derselben Konfiguration UND an "Bridge eingerichtet" ([bridgeQuelle], strikt): scheitert einer der
     * beiden Reads, steht seine Spur auf "unbekannt" ([lichtQuelle]).
     *
     * MUSS VOR dem `init{}`-Block stehen (wie [schichtFolgen]).
     */
    val tagesleisteQuellen: StateFlow<TagesleisteQuellen?> = combine(
        typenQuelle().fehlerAlsUnbekannt("Typ-Einstellungen"),
        bridgeQuelle().fehlerAlsUnbekannt("Bridge-Einrichtung"),
        spannenQuelle().fehlerAlsUnbekannt("Schichtspannen"),
        tageslichtStand()
    ) { typen, bridge, spannen, tageslicht ->
        TagesleisteQuellen(typen, lichtQuelle(typen, bridge), spannen, tageslicht)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Vorgabe-Farbwaerme und aktive Weckzeiten fuer das Blatt „Weitere Optionen“ der Typ-Seite (#224). Kalt:
     * gelesen wird erst, solange das Blatt offen ist. Ein unlesbarer Alarm-Bestand heisst nur „kein Beispiel“ -
     * nie eine erfundene Weckzeit.
     */
    fun weitereOptionenStand(): Flow<WeitereOptionenStand> = flow {
        val weckzeiten = alarmUseCase.getAllAlarms().getOrNull()
            ?.filter { it.isActive }
            ?.map { it.triggerTime }
            .orEmpty()
        emitAll(dimOverlayPrefs.get().warmth.map { WeitereOptionenStand(it, weckzeiten) })
    }.catch { e ->
        if (e is CancellationException) throw e
        // Reine Anzeige: ohne Stand zeigt das Blatt weder „Zurzeit …“ noch ein Beispiel.
        Logger.w(LogTags.SHIFT, "Weitere Optionen: Vorgabe oder Weckzeiten nicht lesbar", e)
    }

    init {
        loadShiftConfig()
        observeCalendarEvents() // Reactive Schichterkennung via StateHolder
        observeExternalConfigChanges()
    }

    /**
     * Faengt Konfigurationsaenderungen auf, die NICHT ueber [updateShiftConfig] dieses ViewModels
     * kamen (z.B. Import), und zieht Anzeige, Schichterkennung UND Alarme nach. Ein zentraler
     * Beobachter deckt jeden Schreiber ab; eigene Writes werden uebersprungen, sonst liefen
     * Erkennung und Sync doppelt und nebenlaeufig. Hergang: schichterkennung.md.
     */
    private fun observeExternalConfigChanges() {
        viewModelScope.launch {
            shiftUseCase.shiftConfig
                .drop(1) // Erste Emission ist der Ist-Zustand, den loadShiftConfig() ohnehin holt.
                .collect { flowConfig ->
                    // EIGENEN Write ueber den VOR dem Write gesetzten Merker erkennen, nicht ueber
                    // `currentShiftConfig` (verliert das Rennen gegen DataStore).
                    if (flowConfig == selfWrittenConfig) {
                        selfWrittenConfig = null
                        return@collect
                    }
                    if (flowConfig == _uiState.value.currentShiftConfig) return@collect

                    // DIE VIERTE TUER: der Flow degradiert bei Defekt auf Standardwerte - nur ein
                    // Erfolg von `getCurrentShiftConfig()` gilt als echte Aenderung.
                    val authoritative = shiftUseCase.getCurrentShiftConfig().getOrElse { error ->
                        Logger.e(
                            LogTags.SHIFT_CONFIG,
                            "❌ EXTERNE AENDERUNG ignoriert: Schicht-Konfiguration nicht lesbar - es " +
                                "werden KEINE Standardwerte uebernommen und kein Alarm-Sync ausgeloest.",
                            error
                        )
                        return@collect
                    }
                    if (authoritative == _uiState.value.currentShiftConfig) return@collect
                    val config = authoritative

                    // Der Stand VOR dem Uebernehmen - die einzige Gelegenheit, eine Umbenennung
                    // zu erkennen. Muss vor dem Schreiben an `_uiState` gelesen werden.
                    val vorherigeConfig = _uiState.value.currentShiftConfig

                    Logger.business(
                        LogTags.SHIFT_CONFIG,
                        "🔄 EXTERNE KONFIGURATIONSAENDERUNG erkannt (${config.definitions.size} " +
                            "Definitionen) - Anzeige, Erkennung und Alarme werden nachgezogen"
                    )
                    _uiState.value = _uiState.value.copy(currentShiftConfig = config)

                    // Dimmer-/Hue-Regelmuster mitziehen - HIER, am geteilten Einstieg, statt nur
                    // in `updateShiftConfig()`. Eine Ruecksicherung oder ein Geraetewechsel bringt
                    // dieselbe Definition (gleiche `id`) unter anderem Namen zurueck; genau dieser
                    // Fall kommt nur ueber diesen Weg herein und haette die Migration sonst
                    // umgangen. Die Notlage-Standardkonfiguration ist oben bereits ausgefiltert
                    // ("DIE VIERTE TUER") - sie erreicht diese Zeile nicht und kann deshalb keine
                    // Regel auf einen Standardnamen umziehen.
                    val nacharmieren = zieheRegelmusterNach(vorherigeConfig, config)

                    // Erkennung neu laufen lassen (aktualisiert auch die Kuerzel-Vorschlaege) und
                    // die Alarme an die neuen Weckzeiten anpassen. Das Nacharmieren der Zeitketten
                    // haengt am Ausgang des Syncs (siehe [armiereZeitkettenNeu]) - auch auf DIESEM
                    // Weg, nicht nur in [updateShiftConfig]: eine Ruecksicherung bringt genau die
                    // Kombination "neue Namen, alte Spannen" herein, gegen die der Fix gerichtet ist.
                    val events = calendarStateHolder.events.value
                    if (events.isNotEmpty()) {
                        processCalendarEvents(events)
                    }
                    triggerAlarmCreationFromConfigUpdate(config, nacharmieren)
                }
        }
    }

    /**
     * Observiert Calendar Events vom StateHolder.
     * distinctUntilChanged() entfernt - StateFlow ist bereits distinct.
     */
    private fun observeCalendarEvents() {
        viewModelScope.launch {
            calendarStateHolder.events
                .debounce(400) // ENHANCED: Längeres Debouncing für teure Shift-Recognition (400ms)
                .collect { events: List<CalendarEvent> ->
                    // Bei JEDER Emission, auch der leeren: offline ist die Liste leer, und genau
                    // dann braucht die Karte den letzten bekannten Stand. Nach einem gelungenen
                    // Abgleich holt die naechste Emission den frisch geschriebenen.
                    aktualisiereLetztenSchichtStand()
                    if (events.isNotEmpty()) {
                        processCalendarEvents(events)
                    } else {
                        // Clear recognized shifts wenn keine Events vorhanden
                        _uiState.value = _uiState.value.copy(
                            recognizedShifts = emptyList(),
                            upcomingShift = null
                        )
                    }
                }
        }
    }

    /**
     * Liest [ShiftSpanStore.letzterStand] fuer die Offline-Anzeige. Ein Fehlschlag (oder ein
     * fehlender Store) laesst den Wert auf `null` - die Karte faellt dann auf den bisherigen,
     * wahren Hinweis zurueck, statt einen Stand zu erfinden.
     */
    private fun aktualisiereLetztenSchichtStand() {
        viewModelScope.launch {
            val stand = try {
                shiftSpanStore.get().letzterStand().getOrNull()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(LogTags.SHIFT, "Letzter Schichtstand nicht lesbar", e)
                null
            }
            _uiState.value = _uiState.value.copy(letzterSchichtStand = stand)
        }
    }

    private fun loadShiftConfig() {
        viewModelScope.launch {
            shiftUseCase.getCurrentShiftConfig()
                .onSuccess { config ->
                    _uiState.value = _uiState.value.copy(currentShiftConfig = config)
                    Logger.business(LogTags.SHIFT_CONFIG, "✅ SINGLETON-STARTUP: ShiftConfig loaded successfully - autoAlarm=${config.autoAlarmEnabled}, definitions=${config.definitions.size}")
                }
                .onFailure { error ->
                    // KEIN Default-Fallback, der SCHREIBT: Fehlschlag heisst "Konfiguration defekt",
                    // Ueberschreiben waere Datenverlust; der Weg zum Default (Knopf "Auf
                    // Standardwerte zurücksetzen") gehoert dem Nutzer.
                    // Hergang: schichterkennung.md.
                    _uiState.value = _uiState.value.copy(
                        error = errorHandler.getErrorMessage(error)
                    )
                    Logger.e(
                        LogTags.SHIFT_CONFIG,
                        "❌ SINGLETON-STARTUP: Schicht-Konfiguration nicht lesbar - sie wird NICHT mit " +
                            "Standardwerten ueberschrieben. Rohdaten liegen als shift_config_broken.",
                        error
                    )
                }
        }
    }

    /**
     * Ordnet ein im Kalender gefundenes Kuerzel einer bestehenden Schichtdefinition zu - der
     * Handgriff, den [ShiftCodeSuggester] vorbereitet.
     *
     * Bewusst KEINE Automatik: die App schlaegt vor, der Mensch entscheidet. Eine stille Zuordnung,
     * die danebengreift, stellt einen Wecker auf die falsche Uhrzeit, und darauf verlaesst sich
     * jemand.
     *
     * Das Kuerzel wird als exaktes Keyword ergaenzt. Damit greift Stufe 2 der Staffelung in
     * [ShiftConfig.findDefinitionFor] (exaktes Keyword) und - entscheidend fuer die Erkennung -
     * [ShiftDefinition.matchesKeywords] mit Wortgrenzen. Auch einbuchstabige Kuerzel sind erlaubt:
     * sie treffen dort nur als eigenstaendiges Wort, und genau solche Codes stehen real im
     * Dienstplan. Die unscharfe Teiltreffer-Stufe bleibt von ihnen unberuehrt
     * ([ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH]).
     *
     * Geht ueber [updateShiftConfig], damit alles daran Haengende mitlaeuft: Speichern,
     * Cache-Invalidierung, erneute Erkennung und Alarm-Sync.
     */
    fun assignCodeToDefinition(code: String, definitionId: String) {
        val config = _uiState.value.currentShiftConfig
        if (config == null) {
            Logger.w(LogTags.SHIFT_CONFIG, "⚠️ KUERZEL-ZUORDNUNG: keine Konfiguration geladen - abgebrochen")
            return
        }
        // Die eigentliche Entscheidung liegt als reine Funktion im Modell (aktiviert das Ziel,
        // entfernt das Kuerzel bei allen anderen - siehe dort, warum jedes davon noetig ist).
        val updated = config.withCodeAssignedTo(code, definitionId)
        if (updated == null) {
            Logger.d(
                LogTags.SHIFT_CONFIG,
                "KUERZEL-ZUORDNUNG: nichts zu tun fuer '$code' -> $definitionId (steht schon so, " +
                    "leeres Kuerzel oder unbekannte Definition)"
            )
            return
        }

        val target = updated.definitions.first { it.id == definitionId }
        Logger.business(
            LogTags.SHIFT_CONFIG,
            "✅ KUERZEL-ZUORDNUNG: '${code.trim()}' gehoert jetzt zu '${target.name}' (aktiviert, " +
                "bei allen anderen Schichten entfernt)"
        )
        updateShiftConfig(updated)
    }

    /**
     * „Ignorieren“ im Fund „Im Kalender gefunden“ (Umbau #176, SPEC E4/3.1): der Code gehoert danach
     * dem ersten aktiven Ignorieren-Typ, den es bei Bedarf anlegt ([SchichttypEinordnen.ignorieren]),
     * und steht an keinem anderen Typ mehr. Ein Ignorieren-Typ ergibt nie einen Wecker (#167).
     *
     * Geht - wie jeder Schreibweg - ueber [updateShiftConfig].
     */
    fun ignoriereCode(code: String) {
        val config = _uiState.value.currentShiftConfig
        if (config == null) {
            Logger.w(LogTags.SHIFT_CONFIG, "⚠️ CODE-IGNORIEREN: keine Konfiguration geladen - abgebrochen")
            return
        }
        val updated = SchichttypEinordnen.ignorieren(config, code)
        if (updated == null) {
            Logger.d(
                LogTags.SHIFT_CONFIG,
                "CODE-IGNORIEREN: nichts zu tun fuer '$code' (leer oder steht schon allein am Ignorieren-Typ)"
            )
            return
        }
        Logger.business(LogTags.SHIFT_CONFIG, "✅ CODE-IGNORIEREN: '${code.trim()}' wird uebergangen")
        updateShiftConfig(updated)
    }

    /**
     * „Als neuer Typ in der Kategorie“ im Fund (Umbau #176): haengt den im Editor bestaetigten Typ
     * [neu] an. Erst DIESER Aufruf speichert - bis dahin ist der Typ aus
     * [SchichttypEinordnen.neuerTypAusCode] nur eine Vorlage, und eine geratene Weckzeit wird nie scharf.
     *
     * Traegt [neu] den [code] noch, geht es durch [ShiftConfig.withCodeAssignedTo]: der Code wird bei
     * allen anderen Typen entfernt (Absicherung gegen einen veralteten Fund, etwa nach einem Import
     * waehrend der Editor offen war - `findDefinitionFor` entschiede sonst nach Listenreihenfolge).
     * `withCodeAssignedTo` schaltet das Ziel aber EIN; den Schalter „Schichtdefinition aktiviert“ im
     * Editor entscheidet der Nutzer, deshalb behaelt [neu] seinen `isEnabled`-Wert (ein bewusst
     * ausgeschalteter Dienst-Typ stellte sonst an jedem Tag des Codes einen Wecker). Hat der Nutzer das
     * Muster im Editor bewusst entfernt, wird es NICHT wieder eingetragen - der Code bleibt dann im
     * Fund, statt still einem Typ zu gehoeren, dem er ihn gerade abgenommen hat.
     *
     * Eine `id`, die es schon gibt (doppelter Tipp auf „Erstellen“), legt nichts ein zweites Mal an.
     */
    fun legeTypAusCodeAn(code: String, neu: ShiftDefinition) {
        val config = _uiState.value.currentShiftConfig
        if (config == null) {
            Logger.w(LogTags.SHIFT_CONFIG, "⚠️ NEUER-TYP-AUS-CODE: keine Konfiguration geladen - abgebrochen")
            return
        }
        if (config.definitions.any { it.id == neu.id }) {
            Logger.d(LogTags.SHIFT_CONFIG, "NEUER-TYP-AUS-CODE: '${neu.name}' existiert schon - nichts zu tun")
            return
        }
        val mitTyp = config.copy(definitions = config.definitions + neu)
        val traegtCode = neu.keywords.any { it.trim().equals(code.trim(), ignoreCase = true) }
        val updated = if (traegtCode) {
            mitTyp.withCodeAssignedTo(code, neu.id)?.let { zugeordnet ->
                zugeordnet.copy(
                    definitions = zugeordnet.definitions.map {
                        if (it.id == neu.id) it.copy(isEnabled = neu.isEnabled) else it
                    }
                )
            } ?: mitTyp
        } else {
            mitTyp
        }
        Logger.business(
            LogTags.SHIFT_CONFIG,
            "✅ NEUER-TYP-AUS-CODE: '${neu.name}' (${neu.kategorie.schluessel}) aus '${code.trim()}' angelegt"
        )
        updateShiftConfig(updated)
    }

    /**
     * Die Wahl „Tage ohne Eintrag verhalten sich“ (Umbau #177, SPEC E5): [typId] = der Typ, wie den sich
     * ein Tag ganz ohne Kalendereintrag verhalten soll, `null` = „Eigene Einstellungen“. Die Entscheidung
     * (was zur Wahl steht, was nichts aendert) liegt in [OhneEintragWahl.waehle].
     *
     * Geht - wie jeder Schreibweg - ueber [updateShiftConfig] ([schreibeOhneEintrag]); der Alarm-Sync
     * dahinter stellt den Wecker an den leeren Tagen bzw. raeumt ihn ab (`AlarmUseCase`, #175).
     */
    fun waehleOhneEintrag(typId: String?) {
        val config = _uiState.value.ohneEintragStand
        if (config == null) {
            Logger.w(LogTags.SHIFT_CONFIG, "⚠️ OHNE-EINTRAG-WAHL: keine Konfiguration geladen - abgebrochen")
            return
        }
        val updated = OhneEintragWahl.waehle(config, typId)
        if (updated == null) {
            Logger.d(
                LogTags.SHIFT_CONFIG,
                "OHNE-EINTRAG-WAHL: nichts zu tun fuer '$typId' (steht schon so, unbekannt oder Ignorieren)"
            )
            return
        }
        Logger.business(
            LogTags.SHIFT_CONFIG,
            "✅ OHNE-EINTRAG-WAHL: Tage ohne Eintrag verhalten sich jetzt " +
                (OhneEintragWahl.gewaehlterTyp(updated)?.let { "wie '${it.name}'" } ?: "nach eigenen Einstellungen")
        )
        schreibeOhneEintrag(updated)
    }

    /**
     * Die eigenen Dimm- und „Nicht stoeren“-Einstellungen der festen Zeile (Umbau #223): [aenderung] formt
     * den zuletzt beauftragten Stand um, geschrieben wird SOFORT wie bei der Wahl - die Seite hat keinen
     * Entwurf. Definitionen und Wahl bleiben. Was nichts aendert, schreibt nicht (kein Alarm-Sync je Tipp).
     *
     * Das Armieren beider Zeitketten haengt am selben Weg wie beim Speichern eines Typs: nach dem Sync,
     * ueber [NacharmierBedarf] (`TypDimmQuelle.dimmenOderNichtStoerenGeaendert` vergleicht die feste Zeile).
     */
    fun aendereOhneEintrag(aenderung: (OhneEintragEinstellungen) -> OhneEintragEinstellungen) {
        val config = _uiState.value.ohneEintragStand
        if (config == null) {
            Logger.w(LogTags.SHIFT_CONFIG, "⚠️ OHNE-EINTRAG-EINSTELLUNGEN: keine Konfiguration geladen - abgebrochen")
            return
        }
        val eigene = aenderung(config.ohneKalendereintrag)
        if (eigene == config.ohneKalendereintrag) return
        schreibeOhneEintrag(config.copy(ohneKalendereintrag = eigene))
    }

    /**
     * Der Schreibweg der Seite „Ohne Kalendereintrag“: merkt [neu] als beauftragt vor
     * ([ShiftUiState.ohneEintragStand]), damit eine zweite Aenderung vor dem Ende des Speicherns darauf
     * aufbaut statt die erste zu ueberschreiben. Verglichen wird beim Armieren gegen den Vorgaenger, nicht
     * gegen den gespeicherten Stand: stellt die zweite Aenderung ihn wieder her, armiert sie sonst nicht.
     */
    private fun schreibeOhneEintrag(neu: ShiftConfig) {
        val vorgaenger = _uiState.value.ohneEintragStand
        _uiState.value = _uiState.value.copy(ohneEintragBeauftragt = neu)
        speichereShiftConfig(neu, vorgaenger)
    }

    /**
     * Loescht den Schichttyp [definition]. Verwies „Ohne Kalendereintrag“ auf ihn, faellt die Zeile im
     * SELBEN Schreibvorgang auf „Eigene Einstellungen“ zurueck ([OhneEintragWahl.ohneTyp]) - nicht in einem
     * zweiten, den ein Sync dazwischen sehen koennte (Umbau #177).
     *
     * Geht - wie jeder Schreibweg - ueber [updateShiftConfig]; der Sync dahinter raeumt den Wecker des
     * Typs ab (Cancel vor Delete, `AlarmUseCase`).
     */
    fun loescheSchichttyp(definition: ShiftDefinition) {
        val config = _uiState.value.currentShiftConfig
        if (config == null) {
            Logger.w(LogTags.SHIFT_CONFIG, "⚠️ TYP-LOESCHEN: keine Konfiguration geladen - abgebrochen")
            return
        }
        val verwiesDarauf = config.ohneKalendereintragWie == definition.id
        Logger.business(
            LogTags.SHIFT_CONFIG,
            "🗑️ TYP-LOESCHEN: '${definition.name}'" +
                if (verwiesDarauf) " (Ohne Kalendereintrag fiel dabei auf eigene Einstellungen zurueck)" else ""
        )
        updateShiftConfig(OhneEintragWahl.ohneTyp(config, definition))
    }

    fun updateShiftConfig(config: ShiftConfig) = speichereShiftConfig(config, vorgaenger = null)

    /** [vorgaenger]: der noch nicht gespeicherte Stand, auf dem [config] aufbaut (`null` = der gespeicherte). */
    private fun speichereShiftConfig(config: ShiftConfig, vorgaenger: ShiftConfig?) {
        viewModelScope.launch {
            // Der Stand VOR dem Schreiben - die einzige Gelegenheit, eine Umbenennung ueberhaupt
            // zu erkennen (die Definition behaelt ihre id, nur der Name aendert sich). Muss vor
            // jedem Schreiben von `currentShiftConfig` gelesen werden. Siehe [zieheRegelmusterNach].
            val vorherigeConfig = vorgaenger ?: _uiState.value.currentShiftConfig

            // VOR dem Write vormerken, nicht danach - siehe [selfWrittenConfig]. Die
            // DataStore-Emission kann den Beobachter erreichen, BEVOR `saveShiftConfig()`
            // zurueckkehrt; ein Merker, der erst im `onSuccess` gesetzt wird, kommt zu spaet.
            selfWrittenConfig = config
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Hier stand bis v1.22.1 ein `recognizeShiftsInEvents(emptyList())` mit dem Kommentar
            // "Force clear the recognition cache" und einem Erfolgs-Log "Recognition cache cleared
            // successfully". Der Aufruf loeschte NICHTS: er fuehrte eine vollstaendige Erkennung
            // mit leerer Eventliste durch und BEFUELLTE den Cache dabei sogar neu
            // (lastRecognitionHash = emptyList().hashCode(), cachedMatches = emptyList()). Der
            // echte Reset passiert eine Zeile weiter unten in `saveShiftConfig()` ->
            // `invalidateAllCaches()` -> `ShiftRecognitionEngine.clearRecognitionCache()`, und
            // zwar nur bei erfolgreichem Speichern - genau richtig. Das falsche Erfolgs-Log war
            // aktiv schaedlich: es liess im Datei-Log einen Cache-Reset als bewiesen erscheinen,
            // der nie stattgefunden hat. Kein Erfolgs-Log fuer eine Operation, die nicht passiert.

            shiftUseCase.saveShiftConfig(config)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        currentShiftConfig = config,
                        // Nur der EIGENE Auftrag faellt weg; ein juengerer steht noch aus.
                        ohneEintragBeauftragt = _uiState.value.ohneEintragBeauftragt?.takeUnless { it === config }
                    )

                    // VOR der Erkennung und vor dem Alarm-Sync: beides fuehrt (ueber ShiftSpanStore
                    // -> DimScheduleUseCase bzw. den Hue-Pfad) auf die Regelmuster, und die sollen
                    // dabei schon den neuen Namen tragen. Das NACHARMIEREN der Zeitketten gehoert
                    // dagegen hinter den Sync - es braucht die Spannen mit dem neuen Namen, siehe
                    // [armiereZeitkettenNeu]. Deshalb wandert der Bedarf weiter, statt hier sofort
                    // ausgefuehrt zu werden.
                    //
                    // #217: Die Dimm-Fenster und „Nicht stoeren“-Schalter liegen am TYP. Hat dieses
                    // Speichern sie veraendert (auch an der festen Zeile oder ueber „wie <Typ>“),
                    // verschieben sich Fenstergrenzen - beide Ketten muessen neu armiert werden
                    // (Pflichtregel). Bewusst ueber denselben Bedarf: armiert wird dann ebenfalls
                    // erst nach dem Sync und genau einmal.
                    val nacharmieren = zieheRegelmusterNach(vorherigeConfig, config)
                        .mitTypEinstellungen(TypDimmQuelle.dimmenOderNichtStoerenGeaendert(vorherigeConfig, config))

                    // DAS try/finally UMSCHLIESST AUCH DAS delay(200) (Befund 21.08.2026):
                    // `zieheRegelmusterNach` hat die Namenslisten unter NonCancellable bereits auf
                    // den neuen Namen gezogen. Das `delay` darunter ist ein garantierter
                    // Abbruchpunkt - verlaesst der Nutzer den Bildschirm genau dort, wurde das
                    // Nacharmieren nie erreicht, und die Dimm-/DND-Kette stuende bis zum naechsten
                    // Keep-alive oder zur 6h-Wartung auf dem Plan von VORHER: neue Liste, alter
                    // Tick. Genau die Nacht dazwischen ist die, um die es geht.
                    try {
                        val currentEvents = calendarStateHolder.events.value
                        if (currentEvents.isNotEmpty()) {
                            // Small delay to ensure config is fully persisted
                            kotlinx.coroutines.delay(200)

                            processCalendarEvents(currentEvents)
                        }

                        // 🚨 CRITICAL FIX: Trigger automatic alarm creation after shift config update!
                        // Unconditional (auch ohne Events): ein Ausschalten von "Automatische Alarme"
                        // muss die Alarme sofort raeumen, nicht nur wenn gerade Events geladen sind.
                        triggerAlarmCreationFromConfigUpdate(config, nacharmieren)
                    } finally {
                        // Beide finally-Bloecke (dieser und der von
                        // `triggerAlarmCreationFromConfigUpdate`) stossen das Nacharmieren an;
                        // `NacharmierBedarf.beanspruche()` sorgt dafuer, dass genau einmal armiert
                        // wird. Dieser Aufruf ist die Versicherung fuer den Fall, dass der Abbruch
                        // im `delay` darueber zuschlug und das erste finally nie erreicht wurde.
                        armiereZeitkettenNeu(nacharmieren)
                    }
                }
                .onFailure { error ->
                    // Merker zuruecksetzen: es kommt keine passende Emission mehr, und ein
                    // haengender Merker wuerde die NAECHSTE echte externe Aenderung mit demselben
                    // Inhalt (Import derselben Datei, zweiter Zuordnungsversuch) verschlucken.
                    selfWrittenConfig = null
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = errorHandler.getErrorMessage(error),
                        // Die Seite zeigt dann wieder, was gespeichert ist.
                        ohneEintragBeauftragt = _uiState.value.ohneEintragBeauftragt?.takeUnless { it === config }
                    )
                }
        }
    }

    /**
     * Zieht ALLE ueber den Schichtnamen gebundenen Einstellungen nach, wenn eine Schichtdefinition
     * UMBENANNT wurde: Dimm-Regeln, Hue-Regeln und die Dienstzeit-Ausnahmen von "Nicht stoeren".
     * Wer eine WEITERE Stelle ergaenzt, die einen Schichtnamen persistent speichert, gehoert hierher.
     *
     * `NonCancellable`: stellt einen konsistenten Zustand HER (sonst halb migriert).
     * Fehler werden ueber [ShiftUiState.regelNachzugHinweis] gemeldet, NICHT ueber `error`.
     * Zwei Aufrufer: [updateShiftConfig] UND [observeExternalConfigChanges] (fremde Schreiber wie
     * Import und Ruecksicherung).
     *
     * @return was danach neu armiert werden muss. Diese Funktion armiert bewusst NICHT selbst -
     *   das Nacharmieren braucht die Schichtspannen mit dem NEUEN Namen, und die schreibt erst
     *   `syncAlarms()`. Begruendung im Detail bei [armiereZeitkettenNeu].
     */
    private suspend fun zieheRegelmusterNach(
        vorher: ShiftConfig?,
        nachher: ShiftConfig
    ): NacharmierBedarf {
        val plan = planeSchichtUmbenennungen(vorher, nachher)
        // Das Rufbereitschaft-Flag ist KEINE Namensliste mehr, aber es hat dieselben Leser: den
        // DND-Cutoff und die stuendliche Kalender-Abfrage. Aendert sich die Menge der
        // Rufbereitschafts-Schichten, muss die DND-Kette (und mit ihr die Abfrage, siehe
        // ZeitkettenArmierer) neu armiert werden - frueher tat das der Chip-Tap im DND-Bildschirm
        // selbst. Ohne Vorher-Stand (erster Load) gibt es keine Aenderung zu erkennen.
        val rufbereitschaftGeaendert = vorher != null &&
            rufbereitschaftsNamen(vorher) != rufbereitschaftsNamen(nachher)
        if (plan.umbenennungen.isEmpty() && plan.blockiert.isEmpty()) {
            return NacharmierBedarf(dimmer = false, dnd = rufbereitschaftGeaendert)
        }

        val fehlgeschlagen = mutableListOf<String>()
        // GETRENNT von `fehlgeschlagen`: ein gescheitertes RAEUMEN ist die gefaehrlichere Lage.
        // Der Umstell-Text ("wirkt wieder, wenn du dort den neuen Namen auswaehlst") lenkt auf eine
        // harmlose Nacharbeit - beim Raeumen bleibt dagegen ein Eintrag stehen, der scharf fuer die
        // FALSCHE Schicht wirkt. Das braucht einen eigenen Satz.
        val raeumenFehlgeschlagen = mutableListOf<String>()
        var nachgezogen = 0
        var dimmGeaendert = 0
        var dndGeaendert = 0
        /** Blockaden, bei denen wirklich ein scharfer Falscheintrag geraeumt wurde - fuer den Text. */
        val geraeumt = mutableMapOf<SchichtUmbenennung, MutableSet<String>>()

        withContext(NonCancellable) {
            for (umbenennung in plan.umbenennungen) {
                dimRuleUseCase.get().renameShiftPattern(umbenennung.alterName, umbenennung.neuerName)
                    .onSuccess { dimmGeaendert += it }
                    .onFailure { fehlgeschlagen += "Dimmer" }

                hueRuleUseCase.get().renameShiftPattern(umbenennung.alterName, umbenennung.neuerName)
                    .onSuccess { nachgezogen += it }
                    .onFailure { fehlgeschlagen += "Hue" }

                // Die uebersehene Namensliste (Befund 21.08.2026): die Dienstzeit-Ausnahmen in
                // "Nicht stoeren". Die Rufbereitschaft-Auswahl daneben ist seit dem Flag am
                // Schichttyp keine Liste mehr; die dritte lag im Dimmer (Nacht-Ausnahmen) und ist
                // mit dem eingebauten Nacht-Standard entfallen.
                // Der Fehlschlag wird EINZELN gemeldet - eine unlesbare DND-Auswahl darf den
                // Nachzug der Dimm-Regeln nicht verhindern und umgekehrt. Der Nutzer sieht die
                // Namen der BILDSCHIRME ("Nicht stören", "Dimmer"), nicht die Speicherschluessel.
                dndPrefs.get().renameShiftName(umbenennung.alterName, umbenennung.neuerName)
                    .onSuccess { dndGeaendert += it }
                    .onFailure { fehlgeschlagen += "Nicht stören" }
            }
            // VOR der Blockade-Schleife: `nachgezogen` zaehlt nur MIGRIERTES. Ein geraeumter
            // Falscheintrag ist kein Nachzug - er darf weder im Erfolgs-Log noch im Satz "alle
            // uebrigen wurden korrekt mitgezogen" auftauchen.
            nachgezogen += dimmGeaendert + dndGeaendert

            // BLOCKIERTE UMBENENNUNGEN: Gehoert der Altname jetzt einer ANDEREN Definition, ist ein
            // Namenslisten-Eintrag scharf fuer die falsche Schicht und wird geraeumt. Bei den
            // uebrigen Blockaden zeigt er auf keine Definition mehr - Loeschen waere Datenverlust.
            for (blockade in plan.blockiert) {
                if (!blockade.alterNameGehoertJetztAnderer) continue
                val alterName = blockade.umbenennung.alterName
                val betroffen = geraeumt.getOrPut(blockade.umbenennung) { mutableSetOf() }

                // Der PARTNERNAME (der neue Name derselben Schicht) entscheidet ueber den
                // Tauschfall: steht er ebenfalls in der Liste, ist deren Inhalt weiterhin exakt
                // richtig und darf nicht geraeumt werden - siehe entferneSchichtnamen().
                val partnerName = blockade.umbenennung.neuerName

                dndPrefs.get().removeShiftName(alterName, partnerName)
                    .onSuccess {
                        dndGeaendert += it
                        if (it > 0) betroffen += "„Nicht stören“"
                    }
                    .onFailure { raeumenFehlgeschlagen += "„Nicht stören“" }
            }
        }

        plan.blockiert.forEach { blockade ->
            val zusatz = if (blockade.alterNameGehoertJetztAnderer) {
                " - Namenslisten geraeumt, damit der Eintrag nicht fuer die falsche Schicht wirkt"
            } else {
                " - Altname gehoert keiner Definition mehr, Eintraege bleiben unberuehrt"
            }
            Logger.w(
                LogTags.SHIFT_CONFIG,
                "⚠️ UMBENENNUNG '${blockade.umbenennung.alterName}' -> " +
                    "'${blockade.umbenennung.neuerName}': Regelmuster NICHT nachgezogen " +
                    "(${blockade.grund})$zusatz"
            )
        }

        if (nachgezogen > 0) {
            Logger.business(
                LogTags.SHIFT_CONFIG,
                "🔁 SCHICHT UMBENANNT: $nachgezogen Regel(n)/Auswahl(en) in Dimmer, Hue und " +
                    "\"Nicht stoeren\" auf den neuen Namen nachgezogen"
            )
        }

        // Der Nutzer erfaehrt es - sonst haelt er eine Regel fuer aktiv, die es nicht mehr ist.
        // Beschreibt die WIRKUNG, nicht die Innerei, und sagt, was zu tun ist.
        //
        // ZUSAMMENGESETZT statt `when`: beide Planlisten koennen gleichzeitig gefuellt sein. Der
        // Text nennt die betroffene Schicht und die BILDSCHIRME, nicht die Speicherschluessel.
        val teile = mutableListOf<String>()

        if (fehlgeschlagen.isNotEmpty()) {
            teile += "Die Schicht wurde umbenannt, aber die Einstellungen für " +
                "${fehlgeschlagen.distinct().joinToString(" und ")} konnten nicht auf den neuen " +
                "Namen umgestellt werden. Sie wirken für diese Schicht erst wieder, wenn du dort " +
                "den neuen Namen auswählst."
        }

        if (raeumenFehlgeschlagen.isNotEmpty()) {
            teile += "Achtung: in ${raeumenFehlgeschlagen.distinct().joinToString(" und ")} steht " +
                "noch ein alter Schichtname, der jetzt zu einer ANDEREN Schicht gehört – die " +
                "Einstellung wirkt dort, wo du sie nie wolltest. Bitte dort einmal nachsehen und " +
                "die Auswahl neu setzen."
        }

        plan.blockiert.forEach { blockade ->
            val alt = blockade.umbenennung.alterName
            val neu = blockade.umbenennung.neuerName
            val satz = StringBuilder(
                "„$alt“ heißt jetzt „$neu“, aber die Regeln und Einstellungen dazu wurden NICHT " +
                    "mitgezogen: ${blockade.grund}."
            )
            val betroffen = geraeumt[blockade.umbenennung].orEmpty()
            if (betroffen.isNotEmpty()) {
                // Der Nutzer muss WISSEN, dass hier etwas entfernt wurde - sonst sucht er den
                // verschwundenen Haken als Fehler.
                satz.append(
                    " Die gespeicherte Auswahl „$alt“ in ${betroffen.sorted().joinToString(" und ")} " +
                        "wurde entfernt, weil dieser Name inzwischen zu einer anderen Schicht " +
                        "gehört und dort still gewirkt hätte."
                )
            }
            satz.append(" Stell für „$neu“ ein, was du brauchst – sonst wirkt dort nichts.")
            teile += satz.toString()
        }

        if (nachgezogen > 0 && plan.blockiert.isNotEmpty()) {
            // Ohne diesen Satz liest sich die Blockade-Meldung, als sei der ganze Speichervorgang
            // schiefgegangen - dabei sind die uebrigen Schichten korrekt migriert.
            teile += "Alle übrigen umbenannten Schichten wurden korrekt mitgezogen."
        }

        if (teile.isNotEmpty()) {
            // EIGENES Feld, nicht `error`: die unmittelbar folgende `processCalendarEvents()`
            // setzt `error` auf null (siehe [ShiftUiState.regelNachzugHinweis]) - die Meldung war
            // weg, bevor sie jemand lesen konnte.
            _uiState.value = _uiState.value.copy(regelNachzugHinweis = teile.joinToString(" "))
        }

        // WAS NEU ARMIERT WERDEN MUSS - aber NICHT hier, siehe [armiereZeitkettenNeu].
        //
        // WARUM DIE DND-AUSWAHL NUR DIE DND-KETTE NACHARMIERT: `dnd_shift_excluded_shifts` (und
        // das Rufbereitschaft-Flag) liest ausschliesslich `DndScheduleUseCase` (Dienstzeit-Fenster
        // und Rufbereitschaft-Cutoff). Der Dimmer kennt sie nicht - ihn mit zu armieren
        // dafuer waere Arbeit ohne jede Wirkung. Umgekehrt zieht ein geaendertes Dimm-Fenster die
        // DND-Kette sehr wohl mit: im Modus "folgt dem Dimmer" ist die Dimm-Zeitleiste die
        // Fensterquelle von "Nicht stoeren".
        return NacharmierBedarf(
            dimmer = dimmGeaendert > 0,
            dnd = dimmGeaendert > 0 || dndGeaendert > 0 || rufbereitschaftGeaendert
        )
    }

    private fun rufbereitschaftsNamen(config: ShiftConfig): Set<String> =
        config.definitions.filter { it.isOnCall }.map { it.name }.toSet()

    /**
     * Armiert die Dimm- und DND-Zeitketten neu, nachdem eine Umbenennung nachgezogen oder die
     * Dimm-/„Nicht stoeren“-Einstellungen eines Typs geaendert wurden (#217): erst die Spannen
     * (`syncAlarms()`), dann armieren - im `finally` von [triggerAlarmCreationFromConfigUpdate],
     * weil der Sync oft ausfaellt. Hergang: schichterkennung.md.
     */
    private suspend fun armiereZeitkettenNeu(bedarf: NacharmierBedarf) {
        if (!bedarf.beanspruche()) return
        armierer.armiere(bedarf.anlass, dimmer = bedarf.dimmer, dnd = bedarf.dnd)
    }

    /**
     * `suspend`, kein fire-and-forget `launch`: damit die Erkennung endet, bevor
     * `triggerAlarmCreationFromConfigUpdate()` startet. Hergang: schichterkennung.md.
     */
    suspend fun processCalendarEvents(events: List<CalendarEvent>) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        // Interface-Version verwendet recognizeShiftsInEvents
        shiftUseCase.recognizeShiftsInEvents(events)
            .onSuccess { shiftMatches ->
                // Konvertiere ShiftMatch zu ShiftInfo für UI-Kompatibilität
                val shifts = shiftMatches.map { match ->
                    ShiftInfo(
                        id = match.calendarEvent.id,
                        shiftType = com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftType(
                            name = match.shiftDefinition.id,
                            displayName = match.shiftDefinition.name
                        ),
                        startTime = match.calendarEvent.startTime,
                        endTime = match.calendarEvent.endTime,
                        alarmTime = match.calculatedAlarmTime
                    )
                }

                // Upcoming shift calculation
                val upcomingShift = shifts
                    .filter { it.startTime.isAfter(java.time.LocalDateTime.now()) }
                    .minByOrNull { it.startTime }

                // Kuerzel-Vorschlaege im SELBEN Durchgang berechnen: hier liegen Events und
                // Konfiguration beide vor, und die Erkennung ist gerade gelaufen - was jetzt keinen
                // Treffer hatte, ist genau der Kandidat, den der Nutzer zuordnen soll. Rein
                // rechnerisch, kein Netz, kein DataStore.
                //
                // BLEIBT BEWUSST AUF DEM AUFRUFER-THREAD (Pruefrunde 7): die teure Haelfte dieses
                // Durchgangs - die Schichterkennung - wechselt inzwischen selbst auf
                // `Dispatchers.Default` (siehe ShiftRecognitionEngine.getAllMatchingShifts). Was
                // hier uebrig bleibt, ist eine Schleife ueber Termine x aktivierte Definitionen mit
                // VORKOMPILIERTEN Mustern (WordBoundaryPatterns) - genau dieselben Muster, die die
                // Erkennung eben benutzt hat, also garantiert bereits im Vorrat. Ein eigener
                // Dispatcher-Wechsel wuerde hier nur einen zweiten Thread-Wechsel pro
                // Kalender-Aktualisierung kosten.
                val suggestions = _uiState.value.currentShiftConfig?.let { config ->
                    ShiftCodeSuggester.suggest(events, config)
                } ?: ShiftCodeSuggester.SuggestionResult(emptyList(), 0)

                if (suggestions.suggestions.isNotEmpty()) {
                    Logger.business(
                        LogTags.SHIFT_RECOGNITION,
                        "💡 KUERZEL-VORSCHLAEGE: ${suggestions.suggestions.size} unbekannte Kuerzel im " +
                            "Kalender (${suggestions.suggestions.joinToString { "${it.code}×${it.occurrences}" }})" +
                            if (suggestions.droppedCount > 0) ", ${suggestions.droppedCount} weitere nicht gezeigt" else ""
                    )
                }

                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    recognizedShifts = shifts,
                    upcomingShift = upcomingShift,
                    codeSuggestions = suggestions
                )
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = errorHandler.getErrorMessage(error)
                )
            }
    }

    /**
     * Triggers alarm creation after shift config updates.
     *
     * `suspend` STATT `viewModelScope.launch` (Befund 21.08.2026): Beide Aufrufer laufen bereits
     * in einer eigenen Coroutine, und [nacharmieren] MUSS nach dem Sync ablaufen - ein
     * fire-and-forget-`launch` haette dafuer keinen definierten Zeitpunkt. Nebenbei faellt damit
     * dieselbe Ueberlappung weg, die schon bei `processCalendarEvents()` teuer war: der Aufruf
     * liegt jetzt sichtbar in der Reihenfolge seines Aufrufers.
     *
     * @param nacharmieren was nach dem Sync neu armiert werden muss - im `finally`, also auf JEDEM
     *   Ausgang dieser Funktion. Warum gerade dort und nicht im Erfolgszweig, steht bei
     *   [armiereZeitkettenNeu].
     */
    private suspend fun triggerAlarmCreationFromConfigUpdate(
        config: ShiftConfig,
        nacharmieren: NacharmierBedarf = NacharmierBedarf.KEINE
    ) {
        try {
            Logger.business(LogTags.ALARM, "🔄 CONFIG-UPDATE: Triggering alarm sync for updated shift config")

            // "Automatische Alarme" ausgeschaltet: soll ein sofortiger, echter Pause sein - nicht
            // nur ein Gate fuer kuenftige Alarme. Bestehende Alarme muessen JETZT verschwinden,
            // unabhaengig davon ob gerade Kalender-Events vorliegen.
            if (!config.autoAlarmEnabled) {
                alarmUseCase.deleteAllAlarms()
                    .onSuccess {
                        Logger.business(LogTags.ALARM, "✅ CONFIG-UPDATE: Alarme pausiert (autoAlarmEnabled=false) - alle Alarme geloescht")
                    }
                    .onFailure { error ->
                        Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: Pausieren der Alarme fehlgeschlagen", error)
                    }
                return
            }

            // Events aus dem CalendarStateHolder - aber NUR, wenn sie nachweislich der vollstaendige
            // Bestand sind (dort liegt oft das Lazy-Praefix). Hergang: kalender-datenfluss.md.
            val currentEvents = calendarStateHolder.events.value
            // Der Horizont reist mit der Liste (#51) - zusammen mit ihr gelesen, nicht spaeter.
            val currentHorizontEnde = calendarStateHolder.horizontEnde.value

            if (currentEvents.isEmpty()) {
                Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: No events available for alarm sync")
                return
            }

            if (!calendarStateHolder.eventsComplete.value) {
                // Fail-safe: lieber ein spaeter nachgezogener Wecker als ein geloeschter. Die
                // Konfiguration ist bereits gespeichert; der naechste vollstaendige Ladevorgang
                // (Vordergrund-Sync, 6h-Wartung, Pre-Alarm-Refresh) synchronisiert sie nach.
                Logger.w(
                    LogTags.ALARM,
                    "⚠️ CONFIG-UPDATE: Eventliste ist nur ein Ausschnitt (${currentEvents.size} Events) - " +
                        "kein Alarm-Sync, bestehende Alarme bleiben unveraendert"
                )
                return
            }

            // Orchestrator: syncAlarms erkennt die Schichten selbst (frische Engine dank
            // Cache-Invalidierung in saveShiftConfig) und setzt die System-Alarme intern.
            // Kein Vor-Recognize und kein delay()-Hack mehr noetig.
            // Vorher-Stand fuer den Dimmer-/DND-Nachzug, siehe planeNachVordergrundSync.
            val schichtStandVorher = armierer.schichtStand()
            alarmUseCase.syncAlarms(currentEvents, config, currentHorizontEnde)
                .onSuccess { alarms ->
                    Logger.business(LogTags.ALARM, "✅ CONFIG-UPDATE: Alarm-Sync erfolgreich - ${alarms.size} Alarme aktiv")
                    // Neue oder verschobene Weckzeiten (z. B. ein neues Kuerzel) brauchen ihren
                    // Vorab-Abgleich 3 h vorher jetzt, nicht erst nach der naechsten Wartung -
                    // derselbe Nachzug wie nach dem Sync im CalendarViewModel (Review 1.48.0).
                    armierer.planeNachVordergrundSync(schichtStandVorher)
                }
                .onFailure { error ->
                    Logger.w(LogTags.ALARM, "⚠️ CONFIG-UPDATE: Alarm-Sync fehlgeschlagen", error)
                }
        } finally {
            // Die Spannen der Tagesleiste neu lesen lassen: ein Sync (auch ein gescheiterter) hat sie
            // womoeglich geschrieben, die Eventliste hat sich dabei nicht geaendert. VOR dem Nacharmieren,
            // damit ein dort geworfener Fehler es nicht ueberspringt; nicht suspendierend.
            spannenAusloeser.update { it + 1 }

            // AUF JEDEM AUSGANG - auch auf den drei fruehen `return` oben (Master-Pause, keine
            // Termine, unvollstaendige Liste) und auch bei einem Abbruch. Die Namenslisten sind zu
            // diesem Zeitpunkt bereits umgeschrieben; ein ausgefallenes Nacharmieren liesse den
            // armierten Tick auf dem Stand von davor stehen. `armiereZeitkettenNeu` haelt seine
            // Arbeit selbst in `NonCancellable` - deshalb ueberlebt sie auch einen abgebrochenen
            // Scope.
            armiereZeitkettenNeu(nacharmieren)
        }
    }

    // --- Quelle der Statuszeile [schichtFolgen] und der Tagesleiste ---
    //
    // Ein kaltes `flow {}`: das `dagger.Lazy` wird erst beim SAMMELN angefasst, also erst, wenn der
    // ShiftConfigScreen oder die Typ-Seite abonniert - nie beim Bauen des ViewModels.

    /**
     * "Bridge eingerichtet" aus dem `@HueDataStore`, STRIKT (#210, #253). `getConfiguration()` ist nur der
     * AUSLOESER: er emittiert bei jeder Aenderung dieses Stores, degradiert bei einem Lesefehler aber auf
     * "nicht eingerichtet" und taugt deshalb nicht als Wert. Der Wert kommt aus der strikten Lese-Stelle.
     */
    private fun bridgeEingerichtet(): Flow<Result<Boolean>> = flow {
        val repo = hueConfigRepository.get()
        emitAll(repo.getConfiguration().map { repo.bridgeEingerichtetOderFehler() })
    }

    // --- Quellen der Tagesleiste [tagesleisteQuellen] ---
    //
    // Die Konfiguration (Typ-Einstellungen), "Bridge eingerichtet" und die Spannen. Jede Quelle fuehrt
    // ihren Lesefehler selbst als [Quelle.Unbekannt] - siehe dort.

    /**
     * Einmal je Abo, kein [Quelle]: ohne Ort heisst fail-safe "keine Einschraenkung" (Licht an, voller
     * Nachlauf). Die Quelle wirft laut Vertrag nie; tut sie es doch, gilt dasselbe - und ein WARN.
     */
    private fun tageslichtStand(): Flow<TageslichtStand> = flow {
        emit(tageslichtQuelle.get().stand())
    }.catch { e ->
        if (e is CancellationException) throw e
        Logger.w(LogTags.SHIFT, "Tagesleiste: Tageslicht-Stand nicht lesbar - rechne ohne Ort", e)
        emit(TageslichtStand.ohneOrt())
    }

    /**
     * Die Schicht-Konfiguration, in der seit #219 Dimmen und "Nicht stoeren" am Typ liegen. Gelesen ueber
     * `getCurrentShiftConfig()` (ein `Result`), NICHT ueber den Flow `shiftConfig`: der degradiert bei
     * einem Defekt auf Standardwerte, und "Standard" hiesse "kein Typ hat ein Fenster" - die Spur zeigte
     * "kein Dimmen" statt "unbekannt". `_uiState.currentShiftConfig` ist nur der AUSLOESER fuer das
     * Neu-Lesen (`null` heisst "noch nicht geladen" ebenso wie "Lesefehler" und taugt nicht als Wert).
     * Die Quelle bleibt kalt: gelesen wird erst beim Sammeln.
     */
    private fun typenQuelle(): Flow<Quelle<ShiftConfig>> =
        _uiState.map { it.currentShiftConfig }.distinctUntilChanged().map {
            shiftUseCase.getCurrentShiftConfig().alsQuelle("Typ-Einstellungen") { config -> config }
        }

    private fun bridgeQuelle(): Flow<Quelle<Boolean>> =
        bridgeEingerichtet().map { bridge -> bridge.alsQuelle("Bridge-Einrichtung") { it } }

    /**
     * Licht der Leiste: die Regeln am Typ aus derselben Naht wie die Laufzeit ([TypLichtQuelle]), aus der
     * schon strikt gelesenen Konfiguration [typen], dazu [bridge]. Fehlt eines davon, ist die Quelle
     * [Quelle.Unbekannt] (das WARN steht bei der Quelle) - nie "keine Regel" oder "keine Bridge".
     */
    private fun lichtQuelle(typen: Quelle<ShiftConfig>, bridge: Quelle<Boolean>): Quelle<LichtQuelle> =
        if (typen is Quelle.Bekannt && bridge is Quelle.Bekannt) {
            Quelle.Bekannt(LichtQuelle(bridge.wert, TypLichtQuelle(typen.wert).regeln, vorlaufMinuten))
        } else {
            Quelle.Unbekannt
        }

    /**
     * Die Spannen stehen nur als Einmal-Lesung bereit (`spansNow()`, kein Flow) und werden deshalb bei
     * ZWEI Anlaessen erneut gelesen:
     *
     * 1. Ein neuer Stand der Eventliste ([CalendarStateHolder.events]).
     * 2. Das Ende eines Alarm-Syncs nach einer Konfigurationsaenderung ([spannenAusloeser]): der Sync
     *    schreibt die Spannen neu, die Eventliste bleibt gleich. Beispiel: nach dem Umbenennen eines Typs
     *    fand `dienstzeitAus` ohne Neu-Lesen nichts mehr ("Im Kalender steht noch kein Termin dieses Typs").
     *
     * GRENZE: Setzt der Kalender-Sync (`CalendarViewModel`) die Events VOR seinem `syncAlarms()`, liest
     * Anlass 1 den Stand davor - diesen Sync sieht das ViewModel nicht, und kein Signal daraus kommt hier
     * an (#211). Die naechste Eventliste oder Speicherung holt ihn nach.
     */
    private fun spannenQuelle(): Flow<Quelle<List<ShiftSpan>>> = flow {
        val store = shiftSpanStore.get()
        emitAll(
            combine(calendarStateHolder.events, spannenAusloeser) { _, _ ->
                store.spansNow().alsQuelle("Schichtspannen") { spannen -> spannen }
            }
        )
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Bestaetigt den Hinweis zum Regel-Nachzug ([ShiftUiState.regelNachzugHinweis]).
     *
     * Nur der Nutzer raeumt ihn weg - kein Ladevorgang, kein Folgezustand. Er beschreibt eine
     * Funktion, die ab jetzt nicht mehr tut, was sie soll; sie wegzuwischen, weil gerade Events
     * nachgeladen wurden, war genau der Fehler, den dieses Feld behebt.
     */
    fun clearRegelNachzugHinweis() {
        _uiState.value = _uiState.value.copy(regelNachzugHinweis = null)
    }
}

/** Eine erkannte Umbenennung: dieselbe Definition (gleiche `id`), neuer Name. */
internal data class SchichtUmbenennung(val alterName: String, val neuerName: String)

/**
 * Welche Zeitketten nach einem Nachzug neu armiert werden muessen - siehe
 * [ShiftViewModel.armiereZeitkettenNeu] fuer das Warum und vor allem fuer das WANN.
 *
 * Ein eigener Wert statt zweier Booleans im Aufruf: Er reist vom Nachzug bis hinter den Alarm-Sync,
 * und an dieser Strecke soll man lesen koennen, was transportiert wird.
 */
internal data class NacharmierBedarf(
    val dimmer: Boolean,
    val dnd: Boolean,
    /** Praefix der WARN-Zeile, falls eine Kette nicht neu armiert werden konnte (`ZeitkettenArmierer`). */
    val anlass: String = ANLASS_UMBENENNUNG
) {
    val irgendetwas: Boolean get() = dimmer || dnd

    /**
     * Derselbe Bedarf, erweitert um geaenderte Dimm-/„Nicht stoeren“-Einstellungen am Typ (#217):
     * dann BEIDE Ketten, denn ein verschobenes Dimm-Fenster zieht DND im Modus „Solange gedimmt
     * wird“ immer mit. Neu gebaut statt kopiert - die Einweg-Sperre [erledigt] ist hier noch frei.
     */
    fun mitTypEinstellungen(geaendert: Boolean): NacharmierBedarf =
        if (geaendert) NacharmierBedarf(dimmer = true, dnd = true, anlass = ANLASS_TYP) else this

    /**
     * EINWEG-SPERRE. Das Nacharmieren wird an ZWEI Stellen angestossen: im `finally` von
     * `triggerAlarmCreationFromConfigUpdate` (der Normalfall, nach dem Alarm-Sync) und im `finally`
     * eine Ebene darueber, das auch das `delay(200)` umschliesst - dieses `delay` ist ein
     * Abbruchpunkt, und ohne die zweite Stelle fiele das Nacharmieren dort ersatzlos aus.
     * Beide Wege sollen greifen, aber die Kette soll genau EINMAL neu armiert werden: doppelt
     * dasselbe zu rechnen ist Arbeit ohne Wirkung, und ein Testlauf soll die Zusicherung
     * "genau einmal" auch pruefen koennen.
     */
    private val erledigt = java.util.concurrent.atomic.AtomicBoolean(false)

    /** true nur beim ERSTEN Aufrufer - siehe [erledigt]. */
    fun beanspruche(): Boolean = irgendetwas && erledigt.compareAndSet(false, true)

    companion object {
        val KEINE = NacharmierBedarf(dimmer = false, dnd = false)
        private const val ANLASS_UMBENENNUNG = "UMBENENNUNG"
        private const val ANLASS_TYP = "SCHICHTTYP"
    }
}

/**
 * Eine Umbenennung, deren Regelmuster bewusst NICHT nachgezogen wird - mit dem Warum.
 *
 * [alterNameGehoertJetztAnderer] trennt die beiden sehr verschiedenen Blockade-Ausgaenge: Zeigt
 * der gespeicherte Altname nach der Umbenennung auf KEINE Definition mehr, ist er ein toter
 * Eintrag - er tut nichts, und er wird wieder richtig, wenn der Nutzer die Umbenennung zuruecknimmt.
 * Gehoert er dagegen inzwischen einer ANDEREN Definition, ist er scharf fuer die falsche Schicht;
 * dann muessen die reinen Namenslisten geraeumt werden (siehe [ShiftViewModel] -
 * `zieheRegelmusterNach`).
 *
 * EXAKTER Vergleich, bewusst anders als bei den Blockade-Gruenden daneben: Die Namenslisten pruefen
 * Mengen-Zugehoerigkeit ohne Toleranz. Ein Eintrag, der sich nur in der Schreibweise von der neuen
 * Eigentuemerin unterscheidet, gehoert ihr also gerade NICHT und darf nicht mit ihr begruendet
 * geloescht werden.
 */
internal data class BlockierteUmbenennung(
    val umbenennung: SchichtUmbenennung,
    val grund: String,
    val alterNameGehoertJetztAnderer: Boolean
)

internal data class SchichtUmbenennungsPlan(
    val umbenennungen: List<SchichtUmbenennung>,
    val blockiert: List<BlockierteUmbenennung>
)

/**
 * PURE, TESTBAR: Welche Umbenennungen muessen in Dimmer-/Hue-Regelmustern nachgezogen werden?
 *
 * Verglichen wird ueber die stabile `id` - nur so laesst sich eine Umbenennung von einer
 * geloeschten plus neu angelegten Definition unterscheiden. Eine geloeschte Definition ist
 * ausdruecklich KEINE Umbenennung: ihre Regeln sollen dort stehen bleiben, wo sie sind, statt
 * auf eine fremde Schicht zu wandern.
 *
 * AUCH EINE REINE SCHREIBWEISENAENDERUNG IST EINE UMBENENNUNG: die Dienstzeit-Ausnahmen
 * vergleichen EXAKT (`DndShiftSpanResolver`); fuer die Regeln ist der Schreibvorgang folgenlos.
 *
 * DIE VIER BLOCKADEN (Nichtstun plus Meldung, sonst schaltet eine Regel zur falschen Schicht):
 *  - Sondermuster ("alle Schichten", "freie Tage") meinen keinen Namen.
 *  - Eine ANDERE Definition heisst jetzt genauso (mehrdeutig).
 *  - Der ALTE Name gehoert jetzt einer anderen Definition (Namenstausch).
 *  - Der neue Name gehoerte bisher einer anderen Definition (Zusammenlegen nicht umkehrbar).
 */
internal fun planeSchichtUmbenennungen(
    vorher: ShiftConfig?,
    nachher: ShiftConfig
): SchichtUmbenennungsPlan {
    if (vorher == null) return SchichtUmbenennungsPlan(emptyList(), emptyList())

    val altePerId = vorher.definitions.associateBy { it.id }
    val umbenennungen = mutableListOf<SchichtUmbenennung>()
    val blockiert = mutableListOf<BlockierteUmbenennung>()

    nachher.definitions.forEach { neu ->
        val alt = altePerId[neu.id] ?: return@forEach
        val alterName = alt.name
        val neuerName = neu.name
        if (alterName.isBlank() || neuerName.isBlank()) return@forEach
        // EXAKT, nicht `ignoreCase`: siehe KDoc - eine reine Schreibweisenaenderung MUSS nachgezogen
        // werden, weil die Namensliste (Dienstzeit-Ausnahmen) exakt vergleicht.
        if (alterName == neuerName) return@forEach

        val andereJetzt = nachher.definitions.filter { it.id != neu.id }.map { it.name }
        val andereVorher = vorher.definitions.filter { it.id != neu.id }.map { it.name }

        val grund = when {
            istReserviertesMuster(neuerName) || istReserviertesMuster(alterName) ->
                "der Name ist ein reserviertes Regelmuster"

            andereJetzt.any { it.equals(neuerName, ignoreCase = true) } ->
                "eine andere Schicht heisst jetzt ebenfalls '$neuerName'"

            andereJetzt.any { it.equals(alterName, ignoreCase = true) } ->
                "der bisherige Name '$alterName' gehoert jetzt einer anderen Schicht"

            andereVorher.any { it.equals(neuerName, ignoreCase = true) } ->
                "'$neuerName' war bisher der Name einer anderen Schicht"

            else -> null
        }

        val umbenennung = SchichtUmbenennung(alterName, neuerName)
        if (grund == null) {
            umbenennungen += umbenennung
        } else {
            // EXAKT: die Namenslisten vergleichen exakt, also gehoert der gespeicherte Altname nur
            // dann wirklich einer anderen Schicht, wenn deren Name zeichengleich ist. Unabhaengig
            // davon berechnet, welcher Blockade-Grund oben zuerst gegriffen hat - die Gruende
            // schliessen einander nicht aus, und der gefaehrliche Fall darf nicht davon abhaengen,
            // welcher `when`-Zweig zuerst dran war.
            blockiert += BlockierteUmbenennung(
                umbenennung = umbenennung,
                grund = grund,
                alterNameGehoertJetztAnderer = nachher.definitions.any {
                    it.id != neu.id && it.name == alterName
                }
            )
        }
    }

    return SchichtUmbenennungsPlan(umbenennungen, blockiert)
}

/**
 * Namen, die als Regelmuster eine Sonderbedeutung tragen und deshalb nie wie ein Schichtname
 * behandelt werden duerfen. Die Konstanten kommen aus den Regel-Modellen selbst - ein zweites
 * Literal hier waere eine zweite Wahrheit.
 */
private fun istReserviertesMuster(name: String): Boolean =
    name.equals(DimRule.SHIFT_UNIVERSAL, ignoreCase = true) ||
        name.equals(DimRule.SHIFT_FREE, ignoreCase = true) ||
        name.equals(HueRuleUseCase.UNIVERSAL_SHIFT_PATTERN, ignoreCase = true)
