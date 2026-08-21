package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.kotlin.mock

/**
 * Handgeschriebener Doppelgaenger fuer [FeedNeueinlesenStore] - dieselbe Rolle wie
 * [FakeSyncHorizonStore]: die Klasse ist `open`, also braucht kein Test eine DataStore-Attrappe.
 *
 * [merkeAufrufe] ist der eigentliche Gegenstand der Zusicherung: ein Sync-Lauf OHNE
 * Kennungswechsel darf hier gar nicht erst anklopfen, sonst stuende in der Statuszeile bald
 * dauerhaft "0 Wecker".
 */
open class FakeFeedNeueinlesenStore(
    stand: FeedNeueinlesenStand? = null,
    private val schreibFehler: Throwable? = null
) : FeedNeueinlesenStore(mock()) {

    private val zustand = MutableStateFlow(stand)

    /** Der zuletzt gemerkte Stand - wird von [merkeNeueinlesen] fortgeschrieben. */
    val stand: FeedNeueinlesenStand? get() = zustand.value

    /** Wie oft wurde geschrieben (Aufrufe mit `anzahl <= 0` zaehlen NICHT - der Store weist sie ab). */
    var merkeAufrufe = 0
        private set

    override fun beobachte(): Flow<FeedNeueinlesenStand?> = zustand

    override suspend fun merkeNeueinlesen(anzahl: Int, zeitpunkt: Long): Result<Unit> {
        if (anzahl <= 0) return Result.success(Unit)
        merkeAufrufe++
        schreibFehler?.let { return Result.failure(it) }
        zustand.value = FeedNeueinlesenStand(zeitpunkt, anzahl)
        return Result.success(Unit)
    }
}
