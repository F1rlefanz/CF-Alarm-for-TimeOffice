package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import com.github.f1rlefanz.cf_alarmfortimeoffice.di.qualifiers.MainDataStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.LogTags
import com.github.f1rlefanz.cf_alarmfortimeoffice.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gedaechtnis der Entprellung aus [WartungTokenFehler] (im bestehenden [MainDataStore]).
 *
 * Zwei Werte, beide notwendig:
 * - [Zustand.zaehler] beantwortet "ist das beharrlich oder war es ein Aussetzer?"
 * - [Zustand.bereitsGemeldet] beantwortet "habe ich das schon gesagt?" - ohne ihn meldete sich
 *   dieselbe Stoerung alle sechs Stunden erneut.
 *
 * Gleiche Bauart wie
 * [com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.CalendarUnavailablePrefs], nur mit einem
 * Zaehler statt zweier Mengen - dort geht es um EINZELNE Kalender, hier um EINEN Zustand.
 */
@Singleton
class WartungStoerungPrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_ZAEHLER = intPreferencesKey("wartung_token_stoerung_zaehler")
        private val KEY_GEMELDET = booleanPreferencesKey("wartung_token_stoerung_gemeldet")
    }

    data class Zustand(val zaehler: Int = 0, val bereitsGemeldet: Boolean = false)

    /**
     * `.catch` umschliesst den Store-Read SELBST und liegt deshalb vor dem `.map` - dieselbe
     * Bauart wie `CalendarUnavailablePrefs.safeData` und `DimOverlayPrefs.safeData`. Das darf
     * nicht mit der Flow-Regel aus CLAUDE.md verwechselt werden ("`.catch` gehoert hinter das
     * `.map`"): die gilt, wo die Abbildung selbst werfen kann. Hier ist jedes `.map` ein reiner
     * Schluesselzugriff.
     *
     * RICHTUNG DER DEGRADATION, bewusst gewaehlt: Bei einem Lesefehler gilt "noch nichts
     * gescheitert, noch nichts gemeldet". Folge: die naechste Stoerung braucht wieder zwei Laeufe,
     * und eine bereits gemeldete koennte ein zweites Mal melden - beides harmlos. Die
     * Gegenrichtung waere es nicht: "gilt als bereits gemeldet" hiesse, die einzige Warnung ueber
     * eine dauerhaft stehende Synchronisation faellt lautlos aus.
     */
    private val safeData: Flow<Preferences> = dataStore.data
        .catch { e ->
            Logger.e(
                LogTags.MAINTENANCE,
                "Stoerungs-Merker der Wartung nicht lesbar - degradiert auf leer (im Zweifel melden)",
                e
            )
            emit(emptyPreferences())
        }

    suspend fun zustandNow(): Zustand = safeData
        .map {
            Zustand(
                zaehler = it[KEY_ZAEHLER] ?: 0,
                bereitsGemeldet = it[KEY_GEMELDET] ?: false
            )
        }
        .first()

    suspend fun setZustand(zaehler: Int, bereitsGemeldet: Boolean) {
        dataStore.edit {
            it[KEY_ZAEHLER] = zaehler
            it[KEY_GEMELDET] = bereitsGemeldet
        }
    }

    /**
     * Nach einem gueltigen Token aufzurufen. Liest vorher, um den DataStore nicht bei jedem der
     * vier taeglichen Wartungslaeufe ohne Anlass zu beschreiben.
     *
     * @return `true`, wenn wirklich eine Stoerungsserie beendet wurde.
     */
    suspend fun zuruecksetzenFallsNoetig(): Boolean {
        val zustand = zustandNow()
        if (zustand.zaehler == 0 && !zustand.bereitsGemeldet) return false
        setZustand(zaehler = 0, bereitsGemeldet = false)
        return true
    }
}
