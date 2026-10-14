package com.github.f1rlefanz.cf_alarmfortimeoffice.tageslicht

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.NutzerEntsperrung
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Speicher und Aufloesung des Orts fuer "nur solange es dunkel ist" (#140).
 *
 * - Land und PLZ: `@MainDataStore` (`settings`), Schluessel `tageslicht_land`/`tageslicht_plz` -
 *   reisen mit Export und Backup.
 * - Standort: [TageslichtStandortPrefs] (eigene Datei, NICHT im Backup, nie im Export).
 * - PLZ-Tabellen: `assets/tageslicht/plz_<land>.tsv`, erst beim ersten Bedarf gelesen und dann
 *   im Prozess gehalten.
 *
 * DAS ENTSPERR-GATE STEHT HIER, an genau EINER Stelle: [stand] und [einstellungen] fragen
 * [NutzerEntsperrung.istEntsperrt] VOR jedem Lesen. Ein CE-DataStore-Read im gesperrten Prozess
 * liefert still "leer" - und DataStore haelt dieses Leer fuer den GANZEN `settings`-Store im
 * Prozess; danach liest auch `AlarmRepository` "kein Bestand gespeichert" (Skill
 * cfalarm-persistenz-und-auth). Der Dimmer-Tick und der AlarmReceiver laufen im Direct Boot, und
 * keiner dieser Aufrufer darf deshalb selbst den Store oder die Standort-Datei lesen.
 *
 * FAIL-SAFE: gesperrt, Lesefehler, kein Ort -> [TageslichtStand.ohneOrt] = keine Einschraenkung
 * (Dimmer-Nachlauf voll, Hue-Regel laeuft). Ein Fehler hier darf nie in den Dimmer-Tick oder den
 * AlarmReceiver werfen.
 *
 * Der Bau des Objekts liest nichts (Application-Graph, Direct Boot) - alles ist lazy.
 *
 * Die Setter armieren NICHT selbst nach (Dimmer/DND, Hue-Sonnenaufgang): das macht der
 * ViewModel nach jedem Schreiben, so bleibt dieses Repository ohne Planer-Abhaengigkeit.
 */
@Singleton
class TageslichtRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val istEntsperrt: () -> Boolean,
    private val standortSpeicher: StandortSpeicher,
    private val tabellenText: (PlzLand) -> String?,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val io: CoroutineDispatcher = Dispatchers.IO
) : ITageslichtRepository {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        @MainDataStore dataStore: DataStore<Preferences>
    ) : this(
        dataStore = dataStore,
        istEntsperrt = { NutzerEntsperrung.istEntsperrt(context, LogTags.TAGESLICHT) },
        standortSpeicher = TageslichtStandortPrefs.speicher(context),
        tabellenText = { land -> leseAsset(context, land) }
    )

    /** Wird bei jedem Standort-Schreiben erhoeht, damit [einstellungen] neu ausgibt (Prefs haben keinen Flow). */
    private val standortVersion = MutableStateFlow(0)

    private val tabellenMutex = Mutex()
    private val tabellen = mutableMapOf<PlzLand, PlzTabelle>()

    override suspend fun stand(): TageslichtStand {
        val z = zone()
        if (!istEntsperrt()) {
            Logger.d(LogTags.TAGESLICHT, "Nutzer gesperrt - kein Ort, keine Einschraenkung")
            return TageslichtStand.ohneOrt(z)
        }
        val e = try {
            withContext(io) { lese(dataStore.data.first()) }
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            // Ohne Werte: Koordinaten gehoeren nie ins Release-Log
            Logger.w(LogTags.TAGESLICHT, "Ort nicht lesbar - keine Einschraenkung (${ex.javaClass.simpleName})")
            return TageslichtStand.ohneOrt(z)
        }
        val land = e.land
        // Die Tabelle nur laden, wenn die PLZ-Stufe ueberhaupt drankommt (ein Standort schlaegt sie)
        val tabelle = if (e.standort == null && land != null && !e.plz.isNullOrBlank()) {
            plzTabelle(land)
        } else {
            PlzTabelle.leer()
        }
        val ort = OrtsAufloeser.loese(e, { tabelle }, z)
        Logger.d(
            LogTags.TAGESLICHT,
            if (ort == null) "Kein Ort fuer Zone ${z.id}"
            else "Ort ${ort.quelle}: ${ort.breite}/${ort.laenge} (${ort.bezeichnung ?: "-"})"
        )
        return TageslichtStand(ort, z)
    }

    override val einstellungen: Flow<TageslichtEinstellungen> = flow {
        if (!istEntsperrt()) {
            emit(TageslichtEinstellungen.LEER)
            return@flow
        }
        emitAll(
            combine(dataStore.data, standortVersion) { prefs, _ -> lese(prefs) }
                // .catch HINTER .map/combine: sonst faengt es nur Upstream-Fehler, und ein Fehler
                // beim Auswerten (Standort-Prefs) liefe in die UI (Skill cfalarm-persistenz-und-auth)
                .catch { ex ->
                    if (ex is CancellationException) throw ex
                    Logger.w(LogTags.TAGESLICHT, "Ort-Einstellungen nicht lesbar - leer (${ex.javaClass.simpleName})")
                    emit(TageslichtEinstellungen.LEER)
                }
        )
    }.flowOn(io)

    override suspend fun setzePlz(land: PlzLand, plz: String) {
        val bereinigt = plz.trim()
        dataStore.edit { p ->
            if (bereinigt.isEmpty()) {
                // Land wird nur MIT einer PLZ gespeichert - ohne PLZ ist es reine Vorauswahl
                p.remove(KEY_LAND)
                p.remove(KEY_PLZ)
            } else {
                p[KEY_LAND] = land.name
                p[KEY_PLZ] = bereinigt
            }
        }
    }

    override suspend fun speichereStandort(breite: Double, laenge: Double, jetzt: Long) {
        require(breite.isFinite() && breite in -90.0..90.0) { "Breite ausserhalb des Wertebereichs" }
        require(laenge.isFinite() && laenge in -180.0..180.0) { "Laenge ausserhalb des Wertebereichs" }
        val gerundet = GespeicherterStandort.gerundet(breite, laenge, jetzt)
        val ok = withContext(io) { standortSpeicher.schreibe(gerundet) }
        if (!ok) throw IOException("Standort nicht gespeichert (commit fehlgeschlagen)")
        standortVersion.value++
        Logger.d(LogTags.TAGESLICHT, "Standort uebernommen: ${gerundet.breiteZehntel}/${gerundet.laengeZehntel} Zehntelgrad")
    }

    override suspend fun entferneStandort() {
        val ok = withContext(io) { standortSpeicher.loesche() }
        if (!ok) throw IOException("Standort nicht entfernt (commit fehlgeschlagen)")
        standortVersion.value++
        Logger.d(LogTags.TAGESLICHT, "Standort entfernt")
    }

    /**
     * Die PLZ-Tabelle eines Landes - beim ersten Bedarf aus dem Asset gelesen, danach gehalten.
     * Nie werfend: ein fehlendes oder unlesbares Asset ergibt eine leere Tabelle (die PLZ-Stufe
     * entfaellt, die Kette faellt auf die Zeitzone durch). Ein Fehlschlag wird NICHT gehalten,
     * der naechste Aufruf versucht es erneut.
     *
     * Oeffentlich fuer den Ort-Bildschirm (Region zur eingegebenen PLZ anzeigen); Assets liegen
     * nicht im CE-Storage und brauchen kein Entsperr-Gate.
     */
    suspend fun plzTabelle(land: PlzLand): PlzTabelle = tabellenMutex.withLock {
        tabellen[land]?.let { return@withLock it }
        val text = try {
            withContext(io) { tabellenText(land) }
        } catch (c: CancellationException) {
            throw c
        } catch (ex: Exception) {
            Logger.w(LogTags.TAGESLICHT, "PLZ-Tabelle $land nicht lesbar (${ex.javaClass.simpleName})")
            null
        }
        if (text == null) return@withLock PlzTabelle.leer()
        PlzTabelle.parse(land, text).also { tabellen[land] = it }
    }

    /** Rohwerte -> Einstellungen. Wirft bei einem Lesefehler der Standort-Datei (Aufrufer degradiert). */
    private fun lese(prefs: Preferences): TageslichtEinstellungen = TageslichtEinstellungen(
        standort = standortSpeicher.lies(),
        land = PlzLand.ausSchluessel(prefs[KEY_LAND]),
        plz = prefs[KEY_PLZ]?.takeIf { it.isNotBlank() }
    )

    companion object {
        private val KEY_LAND = stringPreferencesKey("tageslicht_land")
        private val KEY_PLZ = stringPreferencesKey("tageslicht_plz")

        private fun leseAsset(context: Context, land: PlzLand): String =
            context.assets.open("tageslicht/plz_${land.name.lowercase()}.tsv").use {
                it.readBytes().toString(Charsets.UTF_8)
            }
    }
}
