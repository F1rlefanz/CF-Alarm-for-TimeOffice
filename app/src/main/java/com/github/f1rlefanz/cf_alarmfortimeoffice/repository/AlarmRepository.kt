@file:Suppress("UnusedImport") // False positive - encodeToString is used in persistToDataStore()

package com.github.f1rlefanz.cf_alarmfortimeoffice.repository

import kotlinx.coroutines.flow.onStart
import dagger.hilt.android.qualifiers.ApplicationContext
import android.os.UserManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmEntry
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.DirectBootAlarmStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.AlarmInfo
import com.github.f1rlefanz.cf_alarmfortimeoffice.repository.interfaces.IAlarmRepository
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.business.DateTimeFormats
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository für Alarm-Daten - implementiert IAlarmRepository Interface
 *
 * ✅ FIXED: Verwendet DataStore für persistente Speicherung
 * ✅ Alarme überleben App-Neustarts
 * ✅ Automatisches Laden beim Repository-Init
 * ✅ Typsicher mit Kotlin Serialization
 * ✅ Asynchron & nicht-blockierend
 *
 * CRITICAL FIX für Bug: "Alarme verschwinden nach App-Schließen"
 * - Ersetzt In-Memory Storage durch DataStore
 * - Lädt Alarme automatisch beim Start
 * - Synchronisiert Änderungen sofort in DataStore
 */
@Serializable
data class AlarmInfoData(
    val id: Int,
    val shiftId: String,
    val shiftName: String,
    val triggerTime: Long,
    val formattedTime: String,
    val eventId: String = "",
    val eventChecksum: String = "",
    val shiftEndTime: Long = 0,  // Schichtende (Epoch-Millis) für SHIFT_END-Dimmfenster; 0 = unbekannt
    val shiftStartTime: Long = 0,  // Schichtbeginn (Epoch-Millis) für DND-"Dienstzeit"-Fenster; 0 = unbekannt
    val isSilent: Boolean = false  // "Stille Schicht" - siehe AlarmInfo.isSilent
)

@Singleton
class AlarmRepository @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>,
    private val directBootAlarmStore: DirectBootAlarmStore,
    @param:ApplicationContext private val appContext: Context
) : IAlarmRepository {

    companion object {
        private val ALARMS_KEY = stringPreferencesKey("active_alarms")

        /** Sicherung des rohen, nicht dekodierbaren Bestands - siehe [backupBrokenAlarms]. */
        internal const val BROKEN_ALARMS_KEY_NAME = "active_alarms_broken"
        private val BROKEN_ALARMS_KEY = stringPreferencesKey(BROKEN_ALARMS_KEY_NAME)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Coroutine Scope für asynchrone Operationen
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory cache für schnellen Zugriff + reaktive UI
    private val _activeAlarms = MutableStateFlow<List<AlarmInfo>>(emptyList())
    /**
     * `onStart { awaitInitialLoad() }` ist KEIN Beiwerk: der Flow ist die Quelle fuer die UI, und
     * ohne diesen Haken heilt sich ein im gesperrten Zustand ergebnislos gebliebener Init-Load nur
     * bei einem METHODEN-Aufruf (`getAllAlarms()` & Co.). Ein Bildschirm, der ausschliesslich
     * beobachtet, saehe die Notlage-Leere fuer die gesamte Prozesslaufzeit - und dieser Prozess
     * ueberlebt das Entsperren (am Emulator nachgemessen: pid unveraendert). Der Haken wurde beim
     * ersten Wurf dieses Fixes vergessen und beim Geraetetest bemerkt: nach dem Entsperren kam kein
     * Nachladen, weil niemand eine Methode aufrief.
     */
    override val activeAlarms: Flow<List<AlarmInfo>> =
        _activeAlarms.asStateFlow().onStart { awaitInitialLoad() }

    /**
     * BEREIT-SIGNAL für den asynchronen Init-Load.
     *
     * Ohne dieses Signal war der leere Start-Cache von `_activeAlarms` nicht von „es gibt keine
     * Alarme" zu unterscheiden: ein durch einen Hintergrund-Trigger (Wartung/Worker/Boot) frisch
     * gestarteter Prozess bekam von `getAllAlarms()` eine leere Liste, der Delta-Sync hielt jede
     * erkannte Schicht für neu — und der danach zurückkehrende Init-Load überschrieb Cache,
     * DataStore UND Direct-Boot-Spiegel mit seinem alten Snapshot (Last-Writer-Wins gegen den
     * eigenen Initialisierer).
     *
     * Wird in `finally` IMMER erfüllt, damit ein Lesefehler die Aufrufer nicht hängen lässt.
     */
    private val initialLoadDone = CompletableDeferred<Unit>()

    /**
     * Serialisiert ALLE Read-Modify-Write-Pfade auf dem Ganzlisten-Bestand (Init-Bereinigung,
     * save/delete/deleteAll/cleanup). Ohne ihn schreiben zwei unabhängige Aufrufer je eine
     * komplette Liste aus ihrem eigenen Snapshot — eine Änderung geht verloren. Vorbild:
     * `DimOverlayPrefs.withOverrideLock`.
     *
     * NICHT reentrant: `persistToDataStore()`/`cleanupExpiredAlarms()` nehmen den Lock deshalb
     * bewusst NICHT selbst, sie werden nur von Lock-Haltern aufgerufen.
     */
    private val stateMutex = Mutex()

    /**
     * SCHREIBSPERRE nach einem gescheiterten Init-Load.
     *
     * Ein Dekodier- oder Lesefehler degradiert den Cache zwangsläufig auf `emptyList()` — mehr ist
     * nicht bekannt. Was NICHT passieren darf: dass diese Notlage-Leere über die noch vorhandenen
     * Rohdaten UND über den Direct-Boot-Spiegel geschrieben wird. Genau das tat der Delta-Sync:
     * `getAllAlarms()` liefert leer → jede erkannte Schicht gilt als neu → `saveAlarm()` →
     * `persistToDataStore()` überschreibt `active_alarms` und spiegelt per
     * `directBootAlarmStore.saveAll()`. Verloren gehen dabei genau die Alarme, die sich NICHT aus
     * dem Kalender rekonstruieren lassen (manuelle Alarme) — und der Direct-Boot-Spiegel, der der
     * einzige Weg zurück nach einem Reboot vor der ersten Entsperrung ist.
     *
     * Dieselbe Entscheidung wie in `DimRuleRepository.editRules()` und
     * `ShiftConfigRepository.getCurrentShiftConfig()`: nicht speichern ist besser als alles
     * verlieren. Die System-Alarme werden trotzdem gesetzt (der Wecker klingelt), nur die
     * Persistenz bleibt für diesen Prozess außen vor. Einzige bewusste Ausnahme:
     * [deleteAllAlarms] (Master-Pause / autoAlarmEnabled=false) MUSS auch dann wirklich räumen,
     * sonst re-armt ein Direct-Boot-Restore Alarme, die der Nutzer gerade pausiert hat.
     */
    @Volatile
    private var persistenceBlocked = false

    /**
     * Wurde der Bestand schon EINMAL im entsperrten Zustand gelesen?
     *
     * DER GRUND (am Emulator mit PIN im Zustand RUNNING_LOCKED nachgemessen): der `settings`-Store
     * liegt im CREDENTIAL-ENCRYPTED Storage. Liest man ihn in einem Prozess, der VOR der ersten
     * Entsperrung gestartet wurde (Android startet genau so einen fuer den `directBootAware`
     * `BootReceiver`), dann WIRFT DataStore NICHT - die Datei ist nicht oeffenbar, `exists()` ist
     * false, und die Bibliothek liefert `serializer.defaultValue`, also `emptyPreferences()`, als
     * ERFOLG. Im Log stand daraufhin "📭 No saved alarms found in DataStore", `persistenceBlocked`
     * blieb false, und der Cache galt als Wahrheit.
     *
     * Und dieser Prozess stirbt beim Entsperren NICHT - er ist derselbe, in dem der Nutzer die App
     * danach bedient (pid im Test unveraendert). Der Init-Load lief aber nur EINMAL. Folge: die App
     * hielt dauerhaft "keine Alarme" fuer wahr, obwohl `active_alarms` sie noch enthielt. Der
     * naechste Sync hielt damit JEDE Schicht fuer neu und schrieb Bestand und Direct-Boot-Spiegel
     * neu - der manuelle Wecker des Nutzers war weg, und im Log sah das wie ein normaler Erstsync
     * aus. Auch die beiden anderen Wachen liefen ins Leere: `keepManualAlarms` kann nichts schonen,
     * was nicht in der Liste steht, und `isPersistenceBlocked()` meldet nichts, weil nie eine
     * Sperre gesetzt wurde.
     *
     * Deshalb wird der Read bei gesperrtem Nutzer NICHT als Ergebnis akzeptiert, sondern beim
     * naechsten Zugriff nach dem Entsperren WIEDERHOLT (siehe [awaitInitialLoad]). Bis dahin gilt
     * die Persistenz als gesperrt, damit kein Schreibpfad die Notlage-Leere festschreibt.
     */
    @Volatile
    private var loadedWhileUnlocked = false

    /** Laeuft gerade ein Nachlade-Versuch? Verhindert, dass zehn Aufrufer zehn Reads starten. */
    private val reloadMutex = Mutex()

    /**
     * IST DER LETZTE SCHREIBVERSUCH GESCHEITERT?
     *
     * Die zweite Art, wie der Bestand nur im Arbeitsspeicher landen kann - und sie war bisher
     * unsichtbar. [persistenceBlocked] deckt den gescheiterten Init-Load ab; ein voller Speicher,
     * eine IOException oder eine beschaedigte `preferences_pb` treffen aber erst den SCHREIBWEG:
     * `persistToDataStore()` faengt die Exception, loggt sie und kehrt zurueck, ohne die Sperre zu
     * setzen (das waere auch falsch - der naechste Versuch soll wieder schreiben duerfen). Wer
     * danach `isPersistenceBlocked()` fragte, bekam "nein" und hielt den Wecker fuer dauerhaft
     * gesichert, obwohl `saveAlarm()` unmittelbar davor "liegt NUR im Arbeitsspeicher" geloggt hat.
     *
     * Deshalb wird das Ergebnis JEDES Schreibvorgangs hier festgehalten - abfragbar ueber
     * [istLetzterSchreibvorgangGescheitert].
     *
     * NICHT MIT [persistenceBlocked] VERODERN. Der erste Wurf dieses Merkers tat genau das in
     * [isPersistenceBlocked], und damit bedeuteten zwei voellig verschiedene Lagen dasselbe
     * Signal: "der Bestand ist in diesem Prozess nicht lesbar" und "der letzte Schreibvorgang ist
     * gescheitert". `AlarmUseCase.clearInternalAlarms()` deutet das Signal als das erste und
     * ueberspringt bei einer ausdruecklichen Abschaltung (Master-Pause, "Automatische Alarme aus",
     * `deleteAllAlarms`) bewusst die gesamte `cancelSystemAlarm`-Schleife - ohne Bestandsliste
     * geht es nicht besser. Nach einem geworfenen Schreibvorgang ist der Bestand im Speicher aber
     * VOLLSTAENDIG lesbar; die Schleife waere faelschlich uebersprungen worden, die Master-Pause
     * haette Repository und Direct-Boot-Spiegel geleert und alle System-Alarme armiert
     * zurueckgelassen. Sie feuern dann trotz Pause und sind ohne Bestandsliste durch nichts mehr
     * abbrechbar - genau die verbotene Kombination "Raeumen ohne Cancellen".
     *
     * Dieser Merker sagt NUR: "der zuletzt geschriebene Stand liegt vielleicht nur im
     * Arbeitsspeicher". Er sagt NICHTS darueber, ob der Bestand lesbar ist, und er darf deshalb
     * nie einen Raeum- oder Cancel-Weg anhalten. Sein einziger Konsument ist die Anzeige des
     * manuellen Weckers.
     *
     * KEINE Dauersperre: ein erfolgreicher Schreibvorgang setzt den Merker sofort wieder auf
     * `false`. Er beschreibt den LETZTEN Versuch, nicht die Vergangenheit - sonst haette ein
     * einmaliger, laengst behobener Speicherplatzmangel die Warnung bis zum App-Neustart
     * stehenlassen.
     */
    @Volatile
    private var letzterSchreibvorgangGescheitert = false

    private val userUnlocked: Boolean
        get() = try {
            appContext.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        } catch (e: Exception) {
            // Im Zweifel als entsperrt behandeln: ein falsch-positives "gesperrt" wuerde die
            // Persistenz dauerhaft sperren.
            Logger.w(LogTags.ALARM, "UserManager nicht abfragbar - Nutzer gilt als entsperrt", e)
            true
        }

    init {
        // CRITICAL: Lade Alarme beim Repository-Init
        loadAlarmsFromDataStore()
    }

    /**
     * CRITICAL: Lädt Alarme aus DataStore beim Start
     * Wird im init{} Block aufgerufen
     */
    private fun loadAlarmsFromDataStore() {
        repositoryScope.launch {
            try {
                stateMutex.withLock {
                    // GESPERRTER NUTZER: gar nicht erst lesen. Der Read wuerde nicht werfen,
                    // sondern still eine leere Preferences-Instanz liefern (siehe
                    // [loadedWhileUnlocked]) - und diese Leere waere von "keine Alarme"
                    // nicht zu unterscheiden.
                    if (!userUnlocked) {
                        persistenceBlocked = true
                        _activeAlarms.value = emptyList()
                        Logger.w(
                            LogTags.ALARM,
                            "🔐 PERSISTENCE: Nutzer noch nicht entsperrt - Alarm-Bestand NICHT " +
                                "gelesen (ein Read wuerde still 'leer' liefern). Persistenz bis zum " +
                                "Entsperren gesperrt, danach wird nachgeladen. Der Direct-Boot-Restore " +
                                "arbeitet unabhaengig davon aus dem Device-Protected-Spiegel."
                        )
                        return@withLock
                    }
                    val preferences = dataStore.data.first()
                    val alarmsJson = preferences[ALARMS_KEY]

                    if (alarmsJson != null) {
                        // Dekodieren EIGENSTÄNDIG abfangen: ein defekter Wert ist etwas anderes als
                        // ein IO-Fehler und darf nicht als "keine Alarme" durchgehen (siehe
                        // persistenceBlocked).
                        val alarmsData = try {
                            json.decodeFromString<List<AlarmInfoData>>(alarmsJson)
                        } catch (e: Exception) {
                            persistenceBlocked = true
                            backupBrokenAlarms(alarmsJson)
                            _activeAlarms.value = emptyList()
                            Logger.e(
                                LogTags.ALARM,
                                "❌ PERSISTENCE: Alarm-Bestand (${alarmsJson.length} Zeichen) ist NICHT " +
                                    "dekodierbar - Sicherung unter '$BROKEN_ALARMS_KEY_NAME', Persistenz " +
                                    "und Direct-Boot-Spiegel werden fuer diesen Prozess NICHT mehr " +
                                    "ueberschrieben",
                                e
                            )
                            return@withLock
                        }
                        val alarms = alarmsData.map { it.toAlarmInfo() }

                        // Cleanup: Entferne automatisch abgelaufene Alarme
                        val currentTime = System.currentTimeMillis()
                        val validAlarms = alarms.filter { it.triggerTime > currentTime }

                        _activeAlarms.value = validAlarms
                        loadedWhileUnlocked = true
                        persistenceBlocked = false

                        Logger.business(
                            LogTags.ALARM,
                            "✅ PERSISTENCE: Loaded ${validAlarms.size} alarms from DataStore (removed ${alarms.size - validAlarms.size} expired)"
                        )

                        // Wenn wir abgelaufene Alarme entfernt haben, speichere die bereinigte Liste
                        if (validAlarms.size < alarms.size) {
                            persistToDataStore(validAlarms)
                        } else {
                            // SPIEGEL-ABGLEICH bei JEDEM erfolgreichen Load.
                            //
                            // `persistToDataStore()` schreibt zuerst den DataStore (durabel) und
                            // danach den Device-Protected-Spiegel. Faellt der zweite Schritt aus
                            // (Prozess-Tod, IO-Fehler), divergieren beide - und die Divergenz war
                            // PERMANENT: es gibt genau einen Schreiber und einen Leser, und
                            // nachgespiegelt wurde bisher nur, wenn der Load selbst abgelaufene
                            // Alarme entfernt hatte. Ein App-Neustart mit intaktem Bestand
                            // reparierte den Spiegel also NICHT, und der haeufigste Sync-Zweig
                            // ("unveraendert - nur re-armen") schreibt das Repository gar nicht: der
                            // Spiegel konnte wochenlang falsch bleiben und nach einem Reboot vor
                            // der ersten Entsperrung die falschen (oder keine) Alarme
                            // wiederherstellen. `saveAll` ist idempotent und billig.
                            directBootAlarmStore.saveAll(
                                validAlarms.map {
                                    DirectBootAlarmEntry(
                                        it.id,
                                        it.shiftName,
                                        it.triggerTime,
                                        formatShiftStartTime(it.shiftStartTime)
                                    )
                                }
                            )
                        }
                    } else {
                        // Hier ist "leer" belastbar: der Nutzer IST entsperrt (oben geprueft), der
                        // Store also wirklich lesbar - es steht schlicht nichts drin.
                        Logger.d(LogTags.ALARM, "📭 PERSISTENCE: No saved alarms found in DataStore")
                        _activeAlarms.value = emptyList()
                        loadedWhileUnlocked = true
                        persistenceBlocked = false
                    }
                }
            } catch (e: Exception) {
                // Auch ein LESEFEHLER (kein Dekodier-Fehler) degradiert den Cache auf leer, während
                // die Datei intakt sein kann - der nächste Write würde sie und den
                // Direct-Boot-Spiegel mit dieser Leere überschreiben. Gleiche Sperre.
                persistenceBlocked = true
                Logger.e(
                    LogTags.ALARM,
                    "❌ PERSISTENCE: Error loading alarms from DataStore - Persistenz und " +
                        "Direct-Boot-Spiegel werden fuer diesen Prozess NICHT mehr ueberschrieben",
                    e
                )
                _activeAlarms.value = emptyList()
            } finally {
                initialLoadDone.complete(Unit)
            }
        }
    }

    /**
     * Legt den rohen, nicht dekodierbaren Alarm-Bestand einmalig unter einem eigenen Schlüssel ab,
     * BEVOR irgendein Schreibpfad ihn überschreiben kann - Vorbild
     * `ShiftConfigRepository.backupBrokenConfig()`. Überschreibt eine vorhandene Sicherung NICHT:
     * die ERSTE ist die dem Original nächste. Fehler werden nur geloggt.
     */
    private suspend fun backupBrokenAlarms(raw: String) {
        try {
            dataStore.edit { preferences ->
                if (preferences[BROKEN_ALARMS_KEY] == null) {
                    preferences[BROKEN_ALARMS_KEY] = raw
                }
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "❌ PERSISTENCE: Sicherung des defekten Alarm-Bestands fehlgeschlagen", e)
        }
    }

    /**
     * Wartet auf den Abschluss des Init-Loads. Erste Anweisung JEDER öffentlichen Operation:
     * Leser bekommen so nie einen noch nicht gefüllten Cache als Wahrheit, und Schreiber können
     * nicht vom nachträglich zurückkehrenden Init-Load überschrieben werden.
     */
    /**
     * Wartet auf den Init-Load - und HOLT IHN NACH, wenn er nur deshalb ergebnislos war, weil der
     * Nutzer noch nicht entsperrt hatte (siehe [loadedWhileUnlocked]).
     *
     * Bewusst HIER: jeder Lese- und jeder Ganzlisten-Schreibpfad geht durch diese Funktion. Damit
     * heilt sich der Zustand beim ersten Zugriff nach dem Entsperren von selbst, ohne neuen
     * Receiver, ohne Scheduler und ohne dass ein kuenftiger Aufrufer daran denken muss.
     */
    private suspend fun awaitInitialLoad() {
        initialLoadDone.await()
        if (loadedWhileUnlocked || !userUnlocked) return

        reloadMutex.withLock {
            if (loadedWhileUnlocked) return
            Logger.business(
                LogTags.ALARM,
                "🔓 PERSISTENCE: Nutzer ist jetzt entsperrt - Alarm-Bestand wird nachgeladen"
            )
            reloadFromDataStore()
        }
    }

    /**
     * Der eigentliche Lesevorgang des Nachladens. Teilt [stateMutex] mit allen anderen
     * Ganzlisten-Pfaden, damit er sich nicht mit einem gleichzeitigen Write kreuzt.
     */
    private suspend fun reloadFromDataStore() {
        try {
            stateMutex.withLock {
                val alarmsJson = dataStore.data.first()[ALARMS_KEY]
                if (alarmsJson == null) {
                    _activeAlarms.value = emptyList()
                    loadedWhileUnlocked = true
                    persistenceBlocked = false
                    Logger.d(LogTags.ALARM, "📭 PERSISTENCE: Nachladen - kein Bestand gespeichert")
                    return@withLock
                }
                val alarms = try {
                    json.decodeFromString<List<AlarmInfoData>>(alarmsJson).map { it.toAlarmInfo() }
                } catch (e: Exception) {
                    // Wie im Init-Load: ein defekter Wert sperrt die Persistenz und wird gesichert,
                    // statt als "keine Alarme" durchzugehen.
                    persistenceBlocked = true
                    loadedWhileUnlocked = true
                    backupBrokenAlarms(alarmsJson)
                    _activeAlarms.value = emptyList()
                    Logger.e(
                        LogTags.ALARM,
                        "❌ PERSISTENCE: Nachgeladener Alarm-Bestand ist nicht dekodierbar - " +
                            "Sicherung unter '$BROKEN_ALARMS_KEY_NAME', Persistenz gesperrt",
                        e
                    )
                    return@withLock
                }
                val valid = alarms.filter { it.triggerTime > System.currentTimeMillis() }
                _activeAlarms.value = valid
                loadedWhileUnlocked = true
                persistenceBlocked = false
                Logger.business(
                    LogTags.ALARM,
                    "✅ PERSISTENCE: ${valid.size} Alarme nachgeladen (${alarms.size - valid.size} abgelaufen)"
                )
            }
        } catch (e: Exception) {
            // NICHT `loadedWhileUnlocked` setzen: ein echter Lesefehler soll beim naechsten Zugriff
            // erneut versucht werden. Die Sperre bleibt, damit nichts ueberschrieben wird.
            persistenceBlocked = true
            Logger.e(LogTags.ALARM, "❌ PERSISTENCE: Nachladen fehlgeschlagen - Persistenz bleibt gesperrt", e)
        }
    }

    /**
     * PERSISTENCE: Speichert Alarme in DataStore
     *
     * ACHTUNG: nimmt [stateMutex] NICHT selbst (nicht reentrant) - nur aus einem Lock-Halter rufen.
     *
     * @param force überschreibt die [persistenceBlocked]-Sperre. NUR für ein ausdrückliches Räumen
     *   (siehe [deleteAllAlarms]) - dort ist "nichts" der gewollte Zustand, nicht eine Notlage.
     * @return `true`, wenn Preferences-Datei UND Direct-Boot-Spiegel wirklich geschrieben wurden.
     *   `false` heißt: der Bestand lebt für diesen Prozess nur noch im Arbeitsspeicher. Beide
     *   Fälle sind bewusst KEINE Exception (siehe [saveAlarm]), aber sie dürfen sich auch nicht
     *   gleich anfühlen - deshalb dieser Rückgabewert statt eines reinen Log-Eintrags.
     */
    private suspend fun persistToDataStore(alarms: List<AlarmInfo>, force: Boolean = false): Boolean {
        if (persistenceBlocked && !force) {
            Logger.w(
                LogTags.ALARM,
                "⛔ PERSISTENCE: Schreiben von ${alarms.size} Alarmen uebersprungen - der Init-Load " +
                    "ist gescheitert, der vorhandene Bestand und der Direct-Boot-Spiegel bleiben " +
                    "unangetastet (Sicherung: '$BROKEN_ALARMS_KEY_NAME')"
            )
            return false
        }
        try {
            val alarmsData = alarms.map { it.toAlarmInfoData() }
            val alarmsJson = json.encodeToString(alarmsData)

            dataStore.edit { preferences ->
                preferences[ALARMS_KEY] = alarmsJson
            }

            // Device-Protected-Spiegel synchron mitschreiben, damit die Alarme nach einem Reboot
            // schon VOR der ersten Entsperrung wiederhergestellt werden koennen (Direct Boot).
            // shiftStartTime (nicht formattedTime/triggerTime, das ist die Weckzeit!) fuellt nach
            // dem Reboot dieselbe "Deine Schicht beginnt um"-Anzeige wie der reguläre Pfad.
            directBootAlarmStore.saveAll(
                alarmsData.map {
                    DirectBootAlarmEntry(it.id, it.shiftName, it.triggerTime, formatShiftStartTime(it.shiftStartTime))
                }
            )

            // Erst NACH dem Spiegel zuruecksetzen: vorher waere "dauerhaft" eine Behauptung ueber
            // einen Vorgang, der noch werfen kann.
            letzterSchreibvorgangGescheitert = false

            Logger.d(LogTags.ALARM, "💾 PERSISTENCE: Saved ${alarms.size} alarms to DataStore (+ Direct-Boot-Spiegel)")
            return true
        } catch (e: Exception) {
            // MERKEN, nicht nur loggen: sonst meldet `isPersistenceBlocked()` gleich danach "alles
            // in Ordnung", und der manuelle Wecker gibt sich als dauerhaft gespeichert aus,
            // obwohl weder Preferences-Datei noch Direct-Boot-Spiegel ihn haben.
            letzterSchreibvorgangGescheitert = true
            Logger.e(LogTags.ALARM, "❌ PERSISTENCE: Error saving alarms to DataStore", e)
            return false
        }
    }

    /**
     * WARUM DIESE FUNKTION AUCH DANN `success` MELDET, WENN NICHTS GESCHRIEBEN WURDE.
     *
     * Bei gesperrter Persistenz kehrt [persistToDataStore] ohne zu schreiben und ohne zu werfen
     * zurück - der Alarm liegt dann nur im Arbeitsspeicher, ohne Preferences-Eintrag und ohne
     * Direct-Boot-Spiegel, und ist nach einem Prozesstod weg. Das als `Result.failure`
     * herauszureichen wäre die naheliegende, aber falsche Antwort, und zwar aus zwei Gründen:
     *
     * 1. Es bräche die ausdrückliche Zusicherung der Sperre (siehe [persistenceBlocked]): "Die
     *    System-Alarme werden trotzdem gesetzt (der Wecker klingelt), nur die Persistenz bleibt
     *    für diesen Prozess außen vor."
     * 2. Es machte den Kalender-Sync SCHLECHTER, nicht besser. `AlarmUseCase.syncAlarms()` ruft
     *    `saveAlarm(...).getOrThrow()` und erst DANACH `scheduleSystemAlarm(...)`. Ein Wurf hier
     *    landet zwar im Pro-Event-`try/catch` (der Sync bricht also nicht ab), lässt den Alarm
     *    aber im Cache stehen, ohne ihn je zu armieren - genau die Kombination "stummer Wecker MIT
     *    Anzeige", gegen die anderswo im Projekt eigens zurückgerollt wird. Und ein Rollback des
     *    Cache-Eintrags hier würde in diesem Prozess ALLE Wecker verhindern, statt sie wenigstens
     *    klingeln zu lassen.
     *
     * Dasselbe gilt fuer den ZWEITEN Weg in den reinen Arbeitsspeicher: wirft der Schreibpfad
     * (voller Speicher, IOException, beschaedigte Datei), faengt [persistToDataStore] das ebenfalls
     * und meldet `false`. Dieser Fall hat ein EIGENES Signal
     * ([istLetzterSchreibvorgangGescheitert]) - bewusst getrennt von [isPersistenceBlocked], die
     * weiterhin nur "der Bestand ist unlesbar" bedeutet. Wer Dauerhaftigkeit anzeigen will, fragt
     * beide; wer entscheidet, ob geraeumt werden darf, fragt ausschliesslich [isPersistenceBlocked].
     *
     * Wer Dauerhaftigkeit braucht, fragt deshalb NACH dem Speichern [isPersistenceBlocked] - so
     * wie `AlarmSkipUseCase.loescheUndPruefeDauerhaftigkeit()` für das Löschen und der manuelle
     * Wecker in `AlarmViewModel.createManualAlarm()` für das Anlegen. Für den Rest bleibt das WARN
     * unten die Spur; es landet auch im Release-Log.
     */
    override suspend fun saveAlarm(alarmInfo: AlarmInfo): Result<Unit> {
        return try {
            // VALIDATION: Check if alarm is in the future
            val currentTime = System.currentTimeMillis()
            if (alarmInfo.triggerTime <= currentTime) {
                Logger.w(
                    LogTags.ALARM,
                    "Rejecting past alarm: ${alarmInfo.formattedTime} (current: ${java.time.LocalDateTime.now()})"
                )
                return Result.failure(IllegalArgumentException("Alarm time is in the past: ${alarmInfo.formattedTime}"))
            }

            awaitInitialLoad()
            stateMutex.withLock {
                val currentAlarms = _activeAlarms.value.toMutableList()
                val existingIndex = currentAlarms.indexOfFirst { it.id == alarmInfo.id }

                if (existingIndex != -1) {
                    // Update existing alarm
                    currentAlarms[existingIndex] = alarmInfo
                    Logger.d(LogTags.ALARM, "Alarm updated: ${alarmInfo.id}")
                } else {
                    // Add new alarm
                    currentAlarms.add(alarmInfo)
                    Logger.business(LogTags.ALARM, "Alarm added", alarmInfo.id.toString())
                }

                // Update cache
                _activeAlarms.value = currentAlarms

                // PERSIST to DataStore
                val dauerhaft = persistToDataStore(currentAlarms)
                if (!dauerhaft) {
                    Logger.w(
                        LogTags.ALARM,
                        "⚠️ PERSISTENCE: Alarm ${alarmInfo.id} (${alarmInfo.formattedTime}) liegt NUR " +
                            "im Arbeitsspeicher - nicht in der Preferences-Datei und nicht im " +
                            "Direct-Boot-Spiegel. Er klingelt in diesem Prozess, ist aber nach " +
                            "Prozesstod oder Neustart weg (siehe isPersistenceBlocked)"
                    )
                }

                // CLEANUP: Trigger cleanup after save
                cleanupExpiredAlarms()
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error saving alarm: ${alarmInfo.id}", e)
            Result.failure(e)
        }
    }

    /**
     * "IST DER BESTAND IN DIESEM PROZESS UNLESBAR?" - genau das und nichts anderes.
     *
     * Bedeutet: der Init-Load ist gescheitert (oder lief vor der ersten Entsperrung und lieferte
     * still leere Preferences), der Cache ist auf eine Notlage-Leere degradiert, und jeder
     * Schreibpfad ist gesperrt, damit diese Leere nicht festgeschrieben wird. `getAllAlarms()`
     * meldet das NICHT als Fehler - deshalb gibt es diese Frage ueberhaupt.
     *
     * Bedeutet NICHT "der letzte Schreibvorgang ist gescheitert". Das ist eine ANDERE Lage (voller
     * Speicher, IOException, beschaedigte Datei), sie hat ihr eigenes Signal
     * [istLetzterSchreibvorgangGescheitert], und die beiden duerfen nie wieder zu einem Signal
     * verschmelzen: bei unlesbarem Bestand darf `AlarmUseCase.clearInternalAlarms()` nicht
     * raeumen (bzw. nur den Direct-Boot-Spiegel), nach einem Schreibfehler dagegen ist der
     * Bestand vollstaendig lesbar und die `cancelSystemAlarm`-Schleife MUSS laufen. Wer beides
     * verodert, laesst die Master-Pause armierte System-Alarme zuruecklassen, die niemand mehr
     * abbrechen kann.
     */
    override suspend fun isPersistenceBlocked(): Boolean {
        // Auf den Init-Load warten: vorher ist die Sperre noch nicht entschieden.
        return try {
            awaitInitialLoad()
            persistenceBlocked
        } catch (e: kotlinx.coroutines.CancellationException) {
            // NICHT als Defekt deuten: `awaitInitialLoad()` wirft eine CancellationException, wenn
            // die AUFRUFENDE Coroutine gecancelt wird - das sagt nichts ueber den Bestand. Als
            // "gesperrt" gedeutet haette es `clearInternalAlarms()` in den Fehlerpfad geschickt und
            // z. B. eine Master-Pause scheitern lassen, obwohl alles in Ordnung ist.
            throw e
        } catch (e: Exception) {
            Logger.w(LogTags.ALARM, "Init-Load nicht abschliessbar - Persistenz gilt als gesperrt", e)
            true
        }
    }

    /**
     * "IST DER ZULETZT GESCHRIEBENE STAND MOEGLICHERWEISE NUR IM ARBEITSSPEICHER?"
     *
     * Der zweite, bis Pruefrunde 8 stumme Weg dorthin: der Schreibweg selbst wirft (voller
     * Speicher, IOException, beschaedigte `preferences_pb`). `persistToDataStore()` faengt das
     * bewusst - der Alarm wird trotzdem armiert und klingelt -, aber ohne diesen Merker konnte es
     * niemand erfahren.
     *
     * NUR FUER ANZEIGE UND WARNUNG gedacht (einziger Konsument: der manuelle Wecker im
     * `AlarmViewModel`). Diese Antwort darf NIEMALS einen Raeum-, Cancel- oder Loeschweg anhalten:
     * der Bestand ist in dieser Lage vollstaendig lesbar, und ein uebersprungenes
     * `cancelSystemAlarm()` hinterliesse armierte Alarme, die niemand mehr abbrechen kann.
     * Fuer "darf ich raeumen?" ist [isPersistenceBlocked] zustaendig, und nur die.
     *
     * Kein Warten auf den Init-Load noetig: der Merker beschreibt einen Schreibvorgang, den es
     * ohne abgeschlossenen Init-Load noch gar nicht gegeben haben kann.
     */
    override suspend fun istLetzterSchreibvorgangGescheitert(): Boolean =
        letzterSchreibvorgangGescheitert

    override suspend fun getAllAlarms(): Result<List<AlarmInfo>> {
        return try {
            // Ohne dieses Warten wäre die leere Startliste im Prozess-Startfenster nicht von
            // "keine Alarme" zu unterscheiden - der Delta-Sync hätte alles für neu gehalten.
            awaitInitialLoad()
            Result.success(_activeAlarms.value)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error getting all alarms", e)
            Result.failure(e)
        }
    }

    override suspend fun getAlarmById(alarmId: Int): Result<AlarmInfo?> {
        return try {
            awaitInitialLoad()
            val alarm = _activeAlarms.value.find { it.id == alarmId }
            Result.success(alarm)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error getting alarm by ID: $alarmId", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteAlarm(alarmId: Int): Result<Unit> {
        return try {
            awaitInitialLoad()
            stateMutex.withLock {
                val updatedAlarms = _activeAlarms.value.filter { it.id != alarmId }
                _activeAlarms.value = updatedAlarms

                // PERSIST to DataStore
                persistToDataStore(updatedAlarms)
            }

            Logger.business(LogTags.ALARM, "Alarm removed", alarmId.toString())
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error deleting alarm: $alarmId", e)
            Result.failure(e)
        }
    }

    override suspend fun deleteAllAlarms(): Result<Unit> {
        return try {
            // Master-Pause/autoAlarmEnabled=false laufen hierüber: das Leeren darf NICHT vom
            // nachträglich zurückkehrenden Init-Load wieder aufgefüllt werden.
            awaitInitialLoad()
            stateMutex.withLock {
                _activeAlarms.value = emptyList()

                // PERSIST to DataStore - force: ein ausdrückliches Räumen muss auch bei blockierter
                // Persistenz durchgehen, sonst re-armt ein Direct-Boot-Restore genau die Alarme,
                // die Master-Pause/autoAlarmEnabled=false gerade abgeschaltet haben. Der defekte
                // Rohbestand ist unter BROKEN_ALARMS_KEY_NAME gesichert.
                persistToDataStore(emptyList(), force = true)
            }

            Logger.business(LogTags.ALARM, "All alarms cleared")
            Result.success(Unit)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error clearing all alarms", e)
            Result.failure(e)
        }
    }

    override suspend fun alarmExists(alarmId: Int): Result<Boolean> {
        return try {
            awaitInitialLoad()
            val exists = _activeAlarms.value.any { it.id == alarmId }
            Result.success(exists)
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error checking if alarm exists: $alarmId", e)
            Result.failure(e)
        }
    }

    /**
     * CLEANUP: Remove expired alarms automatically
     *
     * ACHTUNG: nimmt [stateMutex] NICHT selbst (nicht reentrant) - nur aus einem Lock-Halter rufen.
     */
    private suspend fun cleanupExpiredAlarms() {
        try {
            val currentTime = System.currentTimeMillis()
            val validAlarms = _activeAlarms.value.filter { it.triggerTime > currentTime }
            val expiredCount = _activeAlarms.value.size - validAlarms.size

            if (expiredCount > 0) {
                _activeAlarms.value = validAlarms
                persistToDataStore(validAlarms)
                Logger.w(LogTags.ALARM, "Cleaned up $expiredCount expired alarms")
            }
        } catch (e: Exception) {
            Logger.e(LogTags.ALARM, "Error during alarm cleanup", e)
        }
    }

    /** shiftStartTime (Epoch-Millis, 0 = unbekannt) -> "dd.MM.yyyy HH:mm", leer bei unbekannt. */
    private fun formatShiftStartTime(shiftStartTime: Long): String {
        if (shiftStartTime <= 0) return ""
        val formatter = DateTimeFormatter.ofPattern(DateTimeFormats.STANDARD_DATETIME)
        return Instant.ofEpochMilli(shiftStartTime).atZone(ZoneId.systemDefault()).format(formatter)
    }

    // Extension functions for conversion
    private fun AlarmInfo.toAlarmInfoData() = AlarmInfoData(
        id = id,
        shiftId = shiftId,
        shiftName = shiftName,
        triggerTime = triggerTime,
        formattedTime = formattedTime,
        eventId = eventId,
        eventChecksum = eventChecksum,
        shiftEndTime = shiftEndTime,
        shiftStartTime = shiftStartTime,
        isSilent = isSilent
    )

    private fun AlarmInfoData.toAlarmInfo() = AlarmInfo(
        id = id,
        shiftId = shiftId,
        shiftName = shiftName,
        triggerTime = triggerTime,
        formattedTime = formattedTime,
        eventId = eventId,
        eventChecksum = eventChecksum,
        shiftEndTime = shiftEndTime,
        shiftStartTime = shiftStartTime,
        isSilent = isSilent
    )
}
