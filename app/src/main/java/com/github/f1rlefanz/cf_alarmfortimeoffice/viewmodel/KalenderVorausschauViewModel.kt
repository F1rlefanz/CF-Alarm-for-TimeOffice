package com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.KalenderVorausschauPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Duennes ViewModel fuer die Einstellungskarte "Kalender-Vorausschau" (#51).
 *
 * Es SPEICHERT nur. Das Neuladen der Termine und der Alarm-Sync danach gehoeren dem
 * `CalendarViewModel` (Laden gehoert ausschliesslich ihm): es beobachtet dieselbe Einstellung und
 * laedt nach jeder Umstellung neu - ein zweiter Ladepfad hier waere genau der, den der
 * Kalender-Skill verbietet.
 *
 * Gespeichert wird erst auf "Übernehmen" bzw. Chip-Tipp, nie pro Tastendruck: jede Aenderung
 * kostet einen Kalenderabruf und einen Alarm-Sync.
 */
@HiltViewModel
class KalenderVorausschauViewModel @Inject constructor(
    private val prefs: KalenderVorausschauPrefs
) : ViewModel() {

    /**
     * Die gespeicherte Vorausschau - `null`, solange sie noch nicht gelesen ist. Bewusst kein
     * Startwert 14: die Karte zeigte sonst kurz einen Wert, der nicht eingestellt ist.
     */
    val tage: StateFlow<Int?> = prefs.tage
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _meldung = MutableStateFlow<String?>(null)

    /** Warum die letzte Eingabe NICHT uebernommen wurde - oder `null`. */
    val meldung: StateFlow<String?> = _meldung.asStateFlow()

    /**
     * Zahlenfeld + "Übernehmen": ungueltig wird mit Text abgelehnt, nicht geklemmt.
     *
     * @return `true`, wenn die Eingabe gueltig war und gespeichert wird (die Karte leert dann das
     *   Feld) - `false`, wenn sie abgelehnt wurde (das Feld bleibt zum Korrigieren stehen).
     */
    fun uebernehmen(eingabe: String): Boolean =
        when (val urteil = KalenderVorausschauPrefs.pruefeEingabe(eingabe)) {
            is KalenderVorausschauPrefs.EingabeUrteil.Abgelehnt -> {
                _meldung.value = urteil.grund
                false
            }
            is KalenderVorausschauPrefs.EingabeUrteil.Gueltig -> {
                speichere(urteil.tage)
                true
            }
        }

    /** Schnellwahl-Chip. */
    fun waehle(tage: Int) = speichere(tage)

    /** Den Ablehnungstext verwerfen, sobald der Nutzer neu tippt. */
    fun eingabeGeaendert() {
        _meldung.value = null
    }

    private fun speichere(tage: Int) {
        viewModelScope.launch {
            prefs.setTage(tage)
                .onSuccess { _meldung.value = null }
                .onFailure { _meldung.value = "Die Vorausschau ließ sich nicht speichern. Bitte noch einmal versuchen." }
        }
    }
}
