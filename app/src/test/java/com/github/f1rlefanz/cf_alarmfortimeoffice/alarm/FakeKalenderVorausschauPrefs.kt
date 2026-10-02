package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.mock

/**
 * Handgeschriebener Doppelgaenger fuer [KalenderVorausschauPrefs] - wie [FakeSyncHorizonStore]:
 * die Klasse ist `open`, ein Test braucht keine DataStore-Attrappe.
 *
 * Voreinstellung 14 Tage = das Verhalten von vor #51; bestehende Tests bleiben damit unveraendert.
 * Ein `mock<KalenderVorausschauPrefs>()` taugt hier NICHT: der suspend-Getter liefert dort `null`
 * fuer einen `Int` und wirft beim Auspacken.
 */
open class FakeKalenderVorausschauPrefs(
    tage: Int = KalenderVorausschauPrefs.STANDARD_TAGE
) : KalenderVorausschauPrefs(mock()) {

    companion object {
        /**
         * Fuer Sync-Tests, deren Termine FEST im Jahr 2035 liegen und die `syncAlarms()` ohne
         * Abruf-Horizont rufen: der Rueckfall "Sync-Beginn + Vorausschau" muss diese Termine
         * abdecken, sonst gaelten ihre Bestandswecker als "hinter dem Abruf" und wuerden nie
         * geloescht (#51). Rund 270 Jahre - ausserhalb jeder echten Einstellung (7..90), also nur
         * hier sinnvoll.
         */
        const val UEBER_ALLE_TESTDATEN_TAGE = 100_000
    }

    private val zustand = MutableStateFlow(tage)

    /** Wie oft gelesen wurde - fuer Tests, die "einmal pro Abruf" pruefen. */
    var leseAufrufe = 0
        private set

    val gesetzt = mutableListOf<Int>()

    override val tage: Flow<Int> get() = zustand

    override suspend fun tageNow(): Int {
        leseAufrufe++
        return zustand.value
    }

    override suspend fun setTage(tage: Int): Result<Unit> {
        if (!KalenderVorausschauPrefs.istGueltig(tage)) return Result.failure(IllegalArgumentException("ausserhalb"))
        gesetzt += tage
        zustand.value = tage
        return Result.success(Unit)
    }
}
