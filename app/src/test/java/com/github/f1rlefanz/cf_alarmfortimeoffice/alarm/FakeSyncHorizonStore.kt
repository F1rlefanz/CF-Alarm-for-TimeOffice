package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import org.mockito.kotlin.mock

/**
 * Handgeschriebener Doppelgaenger fuer [SyncHorizonStore] - dieselbe Rolle wie
 * `FakeShiftChangeNotifier`: die Klasse ist `open`, also braucht kein Test eine
 * DataStore-Attrappe zu bauen.
 *
 * Voreinstellung ist bewusst `null` ("es gab noch keinen vollstaendigen Sync"): das ist die
 * MELDENDE Richtung. Bestehende Tests, die eine "Neue Schicht erkannt"-Meldung erwarten, bleiben
 * damit unveraendert gueltig - wer den Horizont-Fall pruefen will, setzt [letzterSync] ausdruecklich.
 */
open class FakeSyncHorizonStore(
    letzterSync: Long? = null,
    private val leseFehler: Throwable? = null,
    private val schreibFehler: Throwable? = null
) : SyncHorizonStore(mock()) {

    /** Wird von [merkeVollstaendigenSync] fortgeschrieben - wie im echten Store. */
    var letzterSync: Long? = letzterSync
        private set

    /** Womit wurde zuletzt fortgeschrieben (null = gar nicht)? */
    var gemerkt: Long? = null
        private set

    var merkeAufrufe = 0
        private set

    override suspend fun letzterVollstaendigerSync(): Result<Long?> =
        leseFehler?.let { Result.failure(it) } ?: Result.success(letzterSync)

    override suspend fun merkeVollstaendigenSync(syncAt: Long): Result<Unit> {
        merkeAufrufe++
        schreibFehler?.let { return Result.failure(it) }
        gemerkt = syncAt
        letzterSync = syncAt
        return Result.success(Unit)
    }
}
