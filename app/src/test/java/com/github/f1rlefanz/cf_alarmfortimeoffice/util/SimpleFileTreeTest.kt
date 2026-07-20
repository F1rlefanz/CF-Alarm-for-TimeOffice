package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testet ausschliesslich die reine Alters-Pruefung [SimpleFileTree.isExpired] - die einzige
 * echte "Kernlogik" der Log-Bereinigung. `cleanupOldLogs`/`deleteOldLogs` selbst sind duenne
 * File-I/O-Wrapper um diese Funktion und werden bewusst nicht separat getestet (gleiche
 * Konvention wie bei [UnusedAppRestrictionsHelperTest]).
 */
class SimpleFileTreeTest {

    private val retentionDays = 8
    private val retentionMillis = retentionDays * 24 * 60 * 60 * 1000L

    @Test
    fun `Datei knapp unter der Grenze ist nicht abgelaufen`() {
        val now = 1_000_000_000_000L
        val lastModified = now - retentionMillis + 1
        assertFalse(SimpleFileTree.isExpired(lastModified, now, retentionDays))
    }

    @Test
    fun `Datei exakt an der Grenze ist NICHT abgelaufen (strikt kleiner)`() {
        val now = 1_000_000_000_000L
        val lastModified = now - retentionMillis
        assertFalse(SimpleFileTree.isExpired(lastModified, now, retentionDays))
    }

    @Test
    fun `Datei deutlich ueber der Grenze ist abgelaufen`() {
        val now = 1_000_000_000_000L
        val lastModified = now - retentionMillis - 1
        assertTrue(SimpleFileTree.isExpired(lastModified, now, retentionDays))
    }

    @Test
    fun `Zeitstempel in der Zukunft (Uhr-Drift) ist nicht abgelaufen`() {
        val now = 1_000_000_000_000L
        val lastModified = now + 1_000L
        assertFalse(SimpleFileTree.isExpired(lastModified, now, retentionDays))
    }
}
