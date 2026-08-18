package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
 * Gedaechtnis der Entprellung fuer [CalendarUnavailableNotifier] (im bestehenden [MainDataStore]).
 *
 * Zwei Mengen, beide notwendig:
 * - [Zustand.zuletztGescheitert] beantwortet "ist das beharrlich oder war es ein Aussetzer?"
 * - [Zustand.bereitsGemeldet] beantwortet "habe ich das schon gesagt?" - ohne sie meldete sich
 *   dieselbe Stoerung alle sechs Stunden erneut.
 *
 * Der Toggle [enabled] liegt bewusst hier und nicht bei [ShiftChangeNotificationPrefs]: das sind
 * zwei verschiedene Aussagen ("dein Dienstplan hat sich geaendert" vs. "deine Datenquelle ist
 * kaputt"), und wer die eine abschaltet, will die andere nicht mitverlieren. Default AN - es ist
 * die einzige Meldung ueber einen Zustand, der die Wecker langsam versiegen laesst.
 */
@Singleton
class CalendarUnavailablePrefs @Inject constructor(
    @param:MainDataStore private val dataStore: DataStore<Preferences>
) {
    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("calendar_unavailable_notification_enabled")
        private val KEY_LAST_FAILED = stringSetPreferencesKey("calendar_unavailable_last_failed")
        private val KEY_ALREADY_NOTIFIED = stringSetPreferencesKey("calendar_unavailable_notified")
    }

    data class Zustand(
        val zuletztGescheitert: Set<String> = emptySet(),
        val bereitsGemeldet: Set<String> = emptySet()
    )

    /**
     * ALLE Lese-Flows gehen hierueber - `.catch` HINTER dem `.map` waere hier zu spaet, es muss
     * den Store-Read selbst umschliessen (dieselbe Bauart wie `DimOverlayPrefs.safeData`).
     *
     * RICHTUNG DER DEGRADATION, bewusst gewaehlt: Bei einem Lesefehler gelten beide Mengen als
     * LEER, also "noch nichts gescheitert, noch nichts gemeldet". Folge: die naechste Stoerung
     * braucht wieder zwei Laeufe, und eine bereits gemeldete koennte ein zweites Mal melden. Beides
     * ist harmlos. Die Gegenrichtung waere es nicht - "gilt als bereits gemeldet" hiesse, die
     * einzige Warnung ueber versiegende Wecker faellt aus, und zwar dauerhaft und lautlos.
     */
    private val safeData: Flow<Preferences> = dataStore.data
        .catch { e ->
            Logger.e(
                LogTags.CALENDAR,
                "Kalender-Warnungs-Merker nicht lesbar - degradiert auf leer (im Zweifel warnen)",
                e
            )
            emit(emptyPreferences())
        }

    val enabled: Flow<Boolean> = safeData.map { it[KEY_ENABLED] ?: true }

    suspend fun enabledNow(): Boolean = enabled.first()

    suspend fun setEnabled(v: Boolean) = dataStore.edit { it[KEY_ENABLED] = v }

    suspend fun zustandNow(): Zustand = safeData
        .map {
            Zustand(
                zuletztGescheitert = it[KEY_LAST_FAILED] ?: emptySet(),
                bereitsGemeldet = it[KEY_ALREADY_NOTIFIED] ?: emptySet()
            )
        }
        .first()

    suspend fun setZustand(zuletztGescheitert: Set<String>, bereitsGemeldet: Set<String>) {
        dataStore.edit {
            it[KEY_LAST_FAILED] = zuletztGescheitert
            it[KEY_ALREADY_NOTIFIED] = bereitsGemeldet
        }
    }
}
