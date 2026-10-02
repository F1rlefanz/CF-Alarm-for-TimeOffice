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
 *
 * [fensterTage] ist das Fenster des vorbelegten Merkers; Voreinstellung 14 = der Altbestand
 * (Merker ohne Fensterangabe, siehe [SyncHorizonStore.ALTBESTAND_FENSTER_TAGE]).
 */
open class FakeSyncHorizonStore(
    letzterSync: Long? = null,
    fensterTage: Int = SyncHorizonStore.ALTBESTAND_FENSTER_TAGE,
    private val leseFehler: Throwable? = null,
    private val schreibFehler: Throwable? = null
) : SyncHorizonStore(mock()) {

    /** Wird von [merkeVollstaendigenSync] fortgeschrieben - wie im echten Store. */
    var letzterSync: Long? = letzterSync
        private set

    /** Fenster des aktuellen Merkers - wird mit [letzterSync] zusammen fortgeschrieben. */
    var fensterTage: Int = fensterTage
        private set

    /** Womit wurde zuletzt fortgeschrieben (null = gar nicht)? */
    var gemerkt: Long? = null
        private set

    /** Welches Fenster wurde zuletzt fortgeschrieben (null = gar nicht)? */
    var gemerktesFenster: Int? = null
        private set

    var merkeAufrufe = 0
        private set

    override suspend fun letzterVollstaendigerSync(): Result<SyncHorizonStore.SyncMerker?> =
        leseFehler?.let { Result.failure(it) }
            ?: Result.success(letzterSync?.let { SyncHorizonStore.SyncMerker(it, fensterTage) })

    override suspend fun merkeVollstaendigenSync(syncAt: Long, fensterTage: Int): Result<Unit> {
        merkeAufrufe++
        schreibFehler?.let { return Result.failure(it) }
        gemerkt = syncAt
        gemerktesFenster = fensterTage
        letzterSync = syncAt
        this.fensterTage = fensterTage
        return Result.success(Unit)
    }
}
