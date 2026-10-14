package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.content.Context
import android.os.SystemClock
import androidx.annotation.MainThread
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probehoeren des Wecktons: spielt den echten Weckton mit dem eingestellten [WecktonAnstieg] kurz
 * an, damit man Anlaufzeit und Startpegel beurteilen kann, ohne auf den naechsten Wecker zu warten.
 *
 * ## Was das NICHT ist
 *
 * Kein Wecker. Es gibt bewusst keinen Weg in die Wecker-Kette - kein `AlarmReceiver`, kein Start
 * des [AlarmSoundService], keine Wecker-Notification, kein AlarmManager. Die einzige Invariante,
 * die zaehlt: ein echter Wecker klingelt, egal was hier passiert. Deshalb
 * - liest die Probe den Wecker-Zustand nur ([AlarmSoundService.alarmActive]) und schreibt nichts,
 *   was dem Wecker gehoert;
 * - startet sie nicht, solange ein Wecker klingelt, und hoert auf, sobald einer beginnt;
 * - nimmt sie sich den Audio-Fokus nur voruebergehend (`GAIN_TRANSIENT`) und gibt bei JEDEM
 *   Fokusverlust auf. Der Wecker selbst fordert seinen Fokus ohne Verlust-Listener an - laufen
 *   beide fuer einen Augenblick parallel, klingelt der Wecker trotzdem.
 *
 * Ein Fehler beim Probehoeren endet in [ProbeZustand.Aus] mit [ProbeFehler], nie in einer
 * Exception - die Probe darf den Prozess nicht reissen, in dem nachts der Wecker laeuft.
 *
 * ## Eine Wahrheit
 *
 * Tonquelle aus [WecktonQuellen], Pegelkurve aus [LautstaerkeAnstieg] - dieselben Funktionen wie
 * beim Wecken. Was man hier hoert, ist, womit man geweckt wird.
 *
 * ## Wer stoppt
 *
 * Besitzer ist dieser Prozess-Singleton, NICHT ein `viewModelScope` (Lehre aus der
 * Dimmer-Vorschau: haengt das Aufraeumen am ViewModel, laeuft der Effekt weiter, wenn der Nutzer
 * die App verlaesst). Die Probe endet von selbst nach [probeDauerMs]. Zusaetzlich MUSS der Aufrufer
 * (Bildschirm) bei Lifecycle `ON_STOP` [stop] rufen - wer die App verlaesst, will keinen Weckton
 * im Hintergrund.
 *
 * ## Direct Boot
 *
 * Der Application-Graph wird auch im Direct-Boot-Prozess gebaut. Deshalb ist die Klasse strikt
 * lazy: kein MediaPlayer, kein AudioManager, kein Speicherzugriff im Konstruktor oder in
 * Property-Initializern - alles entsteht erst beim ersten [start].
 *
 * Alle oeffentlichen Funktionen laufen auf dem Main-Thread; Rueckrufe des Players und des
 * Audio-Fokus werden auf den Dispatcher der Probe zurueckgeholt, damit der Zustand nur von einem
 * Thread veraendert wird.
 */
@Singleton
class WecktonProbe internal constructor(
    private val audioFabrik: () -> ProbeAudio,
    private val dispatcherFabrik: () -> CoroutineDispatcher,
    private val uhr: () -> Long,
    private val weckerAktiv: () -> StateFlow<Boolean>
) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        audioFabrik = { AndroidProbeAudio(context) },
        dispatcherFabrik = { Dispatchers.Main.immediate },
        uhr = SystemClock::elapsedRealtime,
        weckerAktiv = { AlarmSoundService.alarmActive }
    )

    // Beide erst beim ersten start(): die Fabriken fassen AudioManager bzw. Main-Dispatcher an.
    private val audio: ProbeAudio by lazy { audioFabrik() }
    private val scope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + dispatcherFabrik()) }

    private val _zustand = MutableStateFlow<ProbeZustand>(ProbeZustand.Aus())
    val zustand: StateFlow<ProbeZustand> = _zustand.asStateFlow()

    /**
     * Jeder Lauf bekommt eine eigene Nummer. Rueckrufe eines frueheren Laufs (ein spaetes
     * `onError` des alten Players, ein Fokusverlust, der erst nach dem Neustart ankommt) duerfen
     * den neuen nicht beenden - derselbe Schutz wie die `playerGeneration` im Wecker.
     */
    private var generation = 0
    private var lauf: Job? = null
    private var klang: ProbeKlang? = null
    private var fokusAngefordert = false

    /**
     * Startet die Probe mit [anstieg]. Laeuft schon eine, wird sie ersetzt (neuer Anstieg von
     * vorn) - so hoert man eine geaenderte Einstellung sofort.
     */
    @MainThread
    fun start(anstieg: WecktonAnstieg) {
        raeumeAuf()
        val gen = ++generation

        if (weckerAktiv().value) {
            _zustand.value = ProbeZustand.Aus(ProbeFehler.WECKER_LAEUFT)
            return
        }

        val gesamtMs = probeDauerMs(anstieg)
        val startPegel = LautstaerkeAnstieg.pegel(anstieg.startAnteil, anstieg.dauerMs, 0L)

        try {
            fokusAngefordert = true
            val fokus = audio.fordereFokus { imLauf(gen) { beende(ProbeFehler.UNTERBROCHEN) } }
            if (!fokus) {
                // Anders als der Wecker spielt die Probe NICHT ueber einen Anruf o. ae. hinweg:
                // sie ist verzichtbar, und wer gerade telefoniert, will keinen Weckton im Ohr.
                beende(ProbeFehler.FOKUS_VERWEIGERT)
                return
            }
            val neuerKlang = audio.spiele(startPegel) {
                imLauf(gen) { beende(ProbeFehler.WIEDERGABE_GESCHEITERT) }
            }
            if (neuerKlang == null) {
                beende(ProbeFehler.KEIN_WECKTON)
                return
            }
            klang = neuerKlang
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "Probehoeren nicht startbar - der Wecker selbst ist davon unberuehrt", e)
            beende(ProbeFehler.WIEDERGABE_GESCHEITERT)
            return
        }

        _zustand.value = ProbeZustand.Laeuft(gesamtMs = gesamtMs, vergangenMs = 0L, pegel = startPegel)
        val beginn = uhr()

        lauf = scope.launch {
            // Ein echter Wecker hat Vorrang: beginnt er waehrend der Probe, endet sie. Der
            // Fokusverlust faengt das in der Regel schon ab - das hier gilt auch dann, wenn der
            // Wecker (z. B. ohne Fokus) einfach losspielt.
            launch {
                weckerAktiv().first { it }
                if (gen == generation) beende(ProbeFehler.WECKER_LAEUFT)
            }

            while (gen == generation) {
                val vergangen = uhr() - beginn
                if (vergangen >= gesamtMs) {
                    beende(null)
                    break
                }
                val pegel = LautstaerkeAnstieg.pegel(anstieg.startAnteil, anstieg.dauerMs, vergangen)
                try {
                    klang?.setzePegel(pegel)
                } catch (e: Exception) {
                    // Ein misslungener Schritt beendet die Probe nicht; der naechste liegt hoeher.
                    Logger.w(LogTags.ALARM, "Probehoeren: Pegel nicht setzbar", e)
                }
                _zustand.value = ProbeZustand.Laeuft(gesamtMs, vergangen, pegel)
                delay(minOf(LautstaerkeAnstieg.SCHRITT_MS, gesamtMs - vergangen))
            }
        }

        Logger.business(
            LogTags.ALARM,
            "Probehoeren gestartet",
            "Dauer ${gesamtMs} ms, Anstieg ${if (anstieg.aktiv) "${anstieg.startProzent} % in ${anstieg.sekunden} s" else "aus"}"
        )
    }

    /** Beendet die Probe sofort. Auch im Ruhezustand gefahrlos; ein angezeigter Fehler verschwindet. */
    @MainThread
    fun stop() {
        beende(null)
    }

    /** Fuehrt [block] auf dem Dispatcher der Probe aus - aber nur, wenn [gen] noch der aktuelle Lauf ist. */
    private fun imLauf(gen: Int, block: () -> Unit) {
        scope.launch { if (gen == generation) block() }
    }

    private fun beende(fehler: ProbeFehler?) {
        raeumeAuf()
        _zustand.value = ProbeZustand.Aus(fehler)
    }

    /**
     * Gibt alles frei, was ein Lauf haelt, und entwertet seine ausstehenden Rueckrufe. Jeder
     * Schritt einzeln abgesichert: ein Fehler beim Freigeben des Players darf den Audio-Fokus
     * nicht festhalten (sonst bliebe fremde Musik pausiert).
     */
    private fun raeumeAuf() {
        generation++
        lauf?.cancel()
        lauf = null
        klang?.let { alt ->
            try {
                alt.freigeben()
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "Probehoeren: Player nicht freigebbar", e)
            }
        }
        klang = null
        if (fokusAngefordert) {
            fokusAngefordert = false
            try {
                audio.gibFokusFrei()
            } catch (e: Exception) {
                Logger.w(LogTags.ALARM, "Probehoeren: Audio-Fokus nicht freigebbar", e)
            }
        }
    }

    companion object {
        /** Wie lange nach Erreichen der vollen Lautstaerke noch weitergespielt wird. */
        const val NACHLAUF_MS = 5_000L

        /** Dauer der Probe ohne Anstieg - lang genug, um den Ton zu erkennen. */
        const val OHNE_ANSTIEG_MS = 10_000L

        /**
         * Dauer einer Probe: mit Anstieg dessen Anlaufzeit plus [NACHLAUF_MS] (man soll auch
         * hoeren, wo er ankommt), ohne Anstieg [OHNE_ANSTIEG_MS].
         */
        fun probeDauerMs(anstieg: WecktonAnstieg): Long {
            val anlauf = anstieg.dauerMs
            return if (anlauf > 0L) anlauf + NACHLAUF_MS else OHNE_ANSTIEG_MS
        }
    }
}

/** Was die Probe gerade tut. */
sealed interface ProbeZustand {

    /** Keine Probe. [fehler] sagt, warum die letzte endete, falls nicht regulaer. */
    data class Aus(val fehler: ProbeFehler? = null) : ProbeZustand

    /**
     * Die Probe spielt.
     *
     * @param gesamtMs geplante Gesamtdauer ([WecktonProbe.probeDauerMs]).
     * @param vergangenMs seit dem Start vergangene Zeit.
     * @param pegel aktueller Pegel, relativ zur Alarm-Lautstaerke des Systems (0..1).
     */
    data class Laeuft(val gesamtMs: Long, val vergangenMs: Long, val pegel: Float) : ProbeZustand {
        val restMs: Long get() = (gesamtMs - vergangenMs).coerceAtLeast(0L)
    }
}

/**
 * Warum eine Probe nicht lief oder vorzeitig endete. Bewusst ohne Text: die Nutzerformulierung
 * gehoert der Oberflaeche.
 */
enum class ProbeFehler {
    /** Ein echter Wecker klingelt gerade oder hat waehrend der Probe begonnen - er hat Vorrang. */
    WECKER_LAEUFT,

    /** Das System hat den Audio-Fokus verweigert (typisch: laufendes Telefonat). */
    FOKUS_VERWEIGERT,

    /** Ein anderer Ton hat den Audio-Fokus uebernommen (Anruf, Navigation, ...). */
    UNTERBROCHEN,

    /** Keine Weckton-Quelle liess sich oeffnen. Der Wecker haette dann ebenfalls keinen Ton. */
    KEIN_WECKTON,

    /** Der Player ist beim Starten oder Abspielen gescheitert. */
    WIEDERGABE_GESCHEITERT
}

/** Naht zum Audio-System - in Tests durch eine Attrappe ersetzt. */
internal interface ProbeAudio {
    /**
     * Fordert den Audio-Fokus voruebergehend an. [beiVerlust] wird bei jedem Fokusverlust
     * gerufen (auch dem "darf leiser weiterspielen"-Fall).
     *
     * @return ob der Fokus erteilt wurde.
     */
    fun fordereFokus(beiVerlust: () -> Unit): Boolean

    fun gibFokusFrei()

    /**
     * Startet den Weckton in Schleife mit [startPegel], probiert dabei die Quellen der Reihe nach.
     * [beiFehler] meldet ein spaeteres Scheitern (asynchrones Vorbereiten, Abspielfehler).
     *
     * @return den laufenden Klang, oder `null`, wenn keine Quelle nutzbar war.
     */
    fun spiele(startPegel: Float, beiFehler: () -> Unit): ProbeKlang?
}

/** Ein laufender Probe-Ton. */
internal interface ProbeKlang {
    fun setzePegel(pegel: Float)
    fun freigeben()
}
