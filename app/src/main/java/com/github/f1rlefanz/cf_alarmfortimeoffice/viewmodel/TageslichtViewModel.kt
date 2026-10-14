package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.ZeitkettenArmierer
import com.github.f1rlefanz.cf_alarmfortimeoffice.hue.scheduling.HueSmartScheduler
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.ITageslichtRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.IStandortAbfrage
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtQuelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.OrtsAufloeser
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.PlzLand
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.PlzTabelle
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.SonnenstandRechner
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.StandortErgebnis
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtEinstellungen
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.TageslichtRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht.ZeitzonenOrte
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.OrtBildschirmZustand
import com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tageslicht.StandortFehler
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject

/**
 * Ort-Bildschirm fuer "nur solange es dunkel ist" (#140): zeigt die genutzte Stufe der Ort-Kette
 * samt heutiger Sonnenzeiten und schreibt Standort bzw. Land + PLZ.
 *
 * NACHZUG: Jede WIRKSAME Schreibaktion zieht beide Zeitketten ([ZeitkettenArmierer], Dimmer vor
 * DND) und die Hue-Sonnenaufgangsplanung ([HueSmartScheduler.recalculateSchedule]) nach - ein neuer
 * Ort verschiebt Dimmer-Nachlauf und Hue-Vorlauf. Ohne Nachzug galte er erst mit der naechsten
 * Wartung, bis zu 6 h spaeter. Schreiben und Nachzug laufen in [NonCancellable]: verlaesst der
 * Nutzer den Bildschirm direkt nach der Eingabe, stirbt der `viewModelScope` - der Wert waere
 * geschrieben, die Ketten hingen am alten Ort (dieselbe Falle wie im `ZeitkettenArmierer`-KDoc).
 *
 * PLZ OHNE ENTPRELLUNG: gespeichert wird sofort, sobald die Eingabe eine bekannte Region trifft oder
 * leer ist. Eine Entprellung verloere die letzte Eingabe, wenn der Nutzer innerhalb der Wartezeit
 * zurueckgeht (Skill Dimmer: "unentprellt"). Damit das Weitertippen ("80" -> "803" -> "80331") nicht
 * jedes Mal alle Ketten neu rechnet, zieht nur eine Aenderung der aufgeloesten REGION nach; ein
 * blosses Weitertippen innerhalb derselben Region aendert keinen Sonnenzeitpunkt.
 *
 * Eine unbekannte PLZ wird NICHT gespeichert: es gilt weiter, was vorher galt (Fail-safe), und die
 * Aktuell-Karte nennt den gespeicherten Stand, nicht die Eingabe.
 *
 * KOORDINATEN: verlassen dieses ViewModel nur auf 0,1° gerundet (das Repository rundet zusaetzlich)
 * und stehen nur in `Logger.d` - nie im Release-Log.
 *
 * Konstruktor fuer Tests mit allen Nahtstellen; Hilt nimmt den `@Inject`-Konstruktor darunter.
 * Die Hue-Planung steht hinter einem Lambda, weil `HueSmartScheduler.getInstance()` sich beim
 * ersten Aufruf mit einem echten Context initialisiert.
 */
@HiltViewModel
class TageslichtViewModel internal constructor(
    private val repo: ITageslichtRepository,
    private val abfrage: IStandortAbfrage,
    private val armierer: ZeitkettenArmierer,
    private val hueNachzug: () -> Unit,
    private val plzTabelle: suspend (PlzLand) -> PlzTabelle,
    /** Geraeteregion (`Locale.country`), nur fuer die Land-Vorauswahl. */
    private val region: String?,
    private val zone: () -> ZoneId,
    private val jetzt: () -> Long
) : ViewModel() {

    // Konkrete Klasse statt Interface: die PLZ-Tabellen kommen aus DEMSELBEN Singleton-Cache wie
    // die Laufzeit (TageslichtRepository.plzTabelle) - kein zweiter Asset-Leser im ViewModel.
    @Inject
    constructor(
        repo: TageslichtRepository,
        abfrage: IStandortAbfrage,
        armierer: ZeitkettenArmierer,
        @ApplicationContext ctx: Context
    ) : this(
        repo = repo,
        abfrage = abfrage,
        armierer = armierer,
        hueNachzug = { HueSmartScheduler.getInstance(ctx).recalculateSchedule() },
        plzTabelle = repo::plzTabelle,
        region = Locale.getDefault().country,
        zone = { ZoneId.systemDefault() },
        jetzt = System::currentTimeMillis
    )

    /** Lokale, noch nicht (oder nie) gespeicherte Eingabe; `null` = zeige den gespeicherten Wert. */
    private data class Eingabe(val land: PlzLand? = null, val plz: String? = null)

    private data class StandortStatus(val laeuft: Boolean = false, val fehler: StandortFehler? = null)

    private val eingabe = MutableStateFlow(Eingabe())
    private val standortStatus = MutableStateFlow(StandortStatus())

    /** Haelt Schreibvorgaenge in Reihenfolge - ein spaet fertiges "80" darf kein "803" ueberholen. */
    private val schreibSperre = Mutex()

    private val tabellenSperre = Mutex()
    private val tabellenCache = mutableMapOf<PlzLand, PlzTabelle>()

    /** Lesefehler degradieren auf "nichts eingestellt" = kein Ort = keine Einschraenkung. */
    private val gespeichert = repo.einstellungen.catch { e ->
        Logger.w(LogTags.TAGESLICHT, "Ort-Einstellungen nicht lesbar - Anzeige ohne Ort", e)
        emit(TageslichtEinstellungen.LEER)
    }

    val zustand: StateFlow<OrtBildschirmZustand> =
        combine(gespeichert, eingabe, standortStatus) { e, ein, status -> Triple(e, ein, status) }
            .map { (e, ein, status) -> baueZustand(e, ein, status) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), startZustand())

    // --- Aktionen ------------------------------------------------------------------------

    fun plzEingabe(s: String) {
        val ziffern = s.filter { it in '0'..'9' }.take(5)
        eingabe.update { it.copy(plz = ziffern) }
        viewModelScope.launch { speicherePlzWennSinnvoll() }
    }

    fun landWahl(l: PlzLand) {
        eingabe.update { it.copy(land = l) }
        viewModelScope.launch { speicherePlzWennSinnvoll() }
    }

    /** Nur auf Knopfdruck, nur im Vordergrund; die Berechtigung hat die Route schon geholt. */
    fun standortUebernehmen() {
        if (standortStatus.value.laeuft) return
        standortStatus.value = StandortStatus(laeuft = true)
        viewModelScope.launch {
            val ergebnis = try {
                abfrage.einmalig()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(LogTags.TAGESLICHT, "Standortabfrage geworfen", e)
                null
            }
            val fehler = when (ergebnis) {
                is StandortErgebnis.Gefunden -> {
                    val breite = aufZehntel(ergebnis.breite)
                    val laenge = aufZehntel(ergebnis.laenge)
                    Logger.d(LogTags.TAGESLICHT, "Standort uebernommen: $breite/$laenge")
                    val ok = schreibe("STANDORT") {
                        repo.speichereStandort(breite, laenge, jetzt())
                        true
                    }
                    if (ok) null else StandortFehler.FEHLGESCHLAGEN
                }
                StandortErgebnis.KeineBerechtigung -> StandortFehler.ABGELEHNT
                StandortErgebnis.Abgeschaltet -> StandortFehler.ABGESCHALTET
                StandortErgebnis.Zeitueberschreitung, null -> StandortFehler.FEHLGESCHLAGEN
            }
            if (fehler != null) Logger.i(LogTags.TAGESLICHT, "Standort nicht uebernommen: $fehler")
            standortStatus.value = StandortStatus(laeuft = false, fehler = fehler)
        }
    }

    /**
     * Neuer Besuch des Bildschirms: alte Fehlermeldung und nicht gespeicherte Eingabe verwerfen.
     *
     * WARUM: Ohne Navigation-Compose haengt das ViewModel an der Activity und ueberlebt das
     * Verlassen des Bildschirms. Ein "ließ sich nicht ermitteln" von vorhin stuende sonst beim
     * naechsten Oeffnen da, als waere es gerade passiert, und eine unbekannte PLZ-Eingabe verdeckte
     * die gespeicherte. Eine laufende Abfrage bleibt sichtbar - sie laeuft ja wirklich noch.
     */
    fun beimOeffnen() {
        eingabe.value = Eingabe()
        standortStatus.update { if (it.laeuft) it else StandortStatus() }
    }

    /** Der Systemdialog wurde abgelehnt - der Bildschirm zeigt den Weg ueber die PLZ. */
    fun standortAbgelehnt() {
        standortStatus.value = StandortStatus(laeuft = false, fehler = StandortFehler.ABGELEHNT)
    }

    fun standortEntfernen() {
        standortStatus.value = StandortStatus()
        viewModelScope.launch {
            schreibe("STANDORT_ENTFERNT") {
                repo.entferneStandort()
                true
            }
        }
    }

    // --- Schreiben -----------------------------------------------------------------------

    /**
     * Speichert die aktuelle Eingabe, wenn sie eine bekannte Region trifft (oder leer ist, dann wird
     * geloescht). Liest die Eingabe erst UNTER der Sperre: liegen mehrere Aufrufe an, schreibt jeder
     * den neuesten Stand, und der zweite erkennt "schon gespeichert".
     */
    private suspend fun speicherePlzWennSinnvoll() {
        schreibe("PLZ") {
            val e = gespeichert.first()
            val ein = eingabe.value
            // Unberuehrtes Feld zeigt die gespeicherte PLZ - ein Landwechsel meint dann DIESE
            val plz = ein.plz ?: e.plz ?: return@schreibe false
            val land = ein.land ?: e.land ?: PlzLand.ausRegion(region)
            val vorher = e.land?.let { l -> e.plz?.let { p -> tabelle(l).suche(l, p) } }
            if (plz.isEmpty()) {
                if (e.plz.isNullOrEmpty()) return@schreibe false
                repo.setzePlz(land, "")
                return@schreibe true
            }
            val neu = tabelle(land).suche(land, plz) ?: return@schreibe false
            if (e.land == land && e.plz == plz) return@schreibe false
            repo.setzePlz(land, plz)
            Logger.d(LogTags.TAGESLICHT, "PLZ gespeichert: $land ${neu.name}")
            neu != vorher
        }
    }

    /**
     * Fuehrt [block] unabbrechbar und in Reihenfolge aus; liefert er `true` (der Ort hat sich
     * wirksam geaendert), wird nachgezogen. Ein Fehler wird geloggt und als `false` gemeldet -
     * er darf weder den Bildschirm noch den Prozess mitreissen. Kein Nachzug nach einem
     * gescheiterten Schreiben: es hat sich nichts geaendert.
     */
    private suspend fun schreibe(anlass: String, block: suspend () -> Boolean): Boolean =
        withContext(NonCancellable) {
            schreibSperre.withLock {
                val geaendert = try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(LogTags.TAGESLICHT, "$anlass: Ort nicht gespeichert", e)
                    return@withLock false
                }
                if (geaendert) ziehNach()
                true
            }
        }

    private suspend fun ziehNach() {
        armierer.armiere(ANLASS)
        runCatching { hueNachzug() }
            .onFailure { Logger.w(LogTags.TAGESLICHT, "$ANLASS: Hue-Planung nicht neu berechnet", it) }
    }

    // --- Anzeige -------------------------------------------------------------------------

    private suspend fun tabelle(land: PlzLand): PlzTabelle = tabellenSperre.withLock {
        tabellenCache.getOrPut(land) { plzTabelle(land) }
    }

    private suspend fun baueZustand(
        e: TageslichtEinstellungen,
        ein: Eingabe,
        status: StandortStatus
    ): OrtBildschirmZustand {
        val z = zone()
        val tabellen = PlzLand.entries.associateWith { tabelle(it) }
        val land = ein.land ?: e.land ?: PlzLand.ausRegion(region)
        val plz = ein.plz ?: e.plz.orEmpty()
        val treffer = if (plz.length >= PlzTabelle.MIN_ZIFFERN) tabellen.getValue(land).suche(land, plz) else null

        val ort = OrtsAufloeser.loese(e, { tabellen.getValue(it) }, z)
        val heute = Instant.ofEpochMilli(jetzt()).atZone(z).toLocalDate()

        return OrtBildschirmZustand(
            genutzt = ort?.quelle,
            daemmerungHeute = ort?.let { uhrzeit(SonnenstandRechner.daemmerungsBeginn(heute, it.breite, it.laenge, z), z) },
            sonnenaufgangHeute = ort?.let { uhrzeit(SonnenstandRechner.sonnenaufgang(heute, it.breite, it.laenge, z), z) },
            standortUebernommenAm = e.standort?.let { DATUM.format(Instant.ofEpochMilli(it.uebernommenAm).atZone(z)) },
            land = land,
            plzEingabe = plz,
            plzRegion = treffer?.name,
            plzUnbekannt = plz.length >= PlzTabelle.MIN_ZIFFERN && treffer == null,
            zeitzone = z.id,
            zeitzoneOrt = ZeitzonenOrte.fuer(z)?.bezeichnung,
            standortLaeuft = status.laeuft,
            standortFehler = status.fehler,
            genutztPlz = if (ort?.quelle == OrtQuelle.PLZ) e.plz else null,
            genutztName = if (ort?.quelle == OrtQuelle.PLZ) ort.bezeichnung else null
        )
    }

    /** Bis die erste Emission da ist: keine Behauptung ueber einen Ort. */
    private fun startZustand(): OrtBildschirmZustand {
        val z = zone()
        return OrtBildschirmZustand(
            genutzt = null,
            daemmerungHeute = null,
            sonnenaufgangHeute = null,
            standortUebernommenAm = null,
            land = PlzLand.ausRegion(region),
            plzEingabe = "",
            plzRegion = null,
            plzUnbekannt = false,
            zeitzone = z.id,
            zeitzoneOrt = ZeitzonenOrte.fuer(z)?.bezeichnung
        )
    }

    private fun uhrzeit(zeitpunkt: Instant?, z: ZoneId): String? = zeitpunkt?.let { UHRZEIT.format(it.atZone(z)) }

    companion object {
        /** Praefix der WARN-Zeile im `ZeitkettenArmierer`. */
        const val ANLASS = "TAGESLICHT"

        private val UHRZEIT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY)
        private val DATUM = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY)

        /** 0,1° (etwa 10 km) - genauer verlaesst kein Standort das ViewModel. */
        internal fun aufZehntel(grad: Double): Double = Math.round(grad * 10) / 10.0
    }
}
