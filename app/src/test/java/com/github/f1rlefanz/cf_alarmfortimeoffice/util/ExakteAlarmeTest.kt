package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.app.AlarmManager
import android.app.PendingIntent
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** #131 (G11-13): die eine Stelle fuer "exakt, sonst ungenau" der Nebenketten. */
class ExakteAlarmeTest {

    @Test
    fun `unter API 31 wird ohne Berechtigungsfrage exakt gestellt`() {
        // Auf der JVM ist Build.VERSION.SDK_INT 0 - der Zweig "keine Berechtigung noetig".
        val am = mock<AlarmManager>()
        val pi = mock<PendingIntent>()

        val exakt = ExakteAlarme.stelle(am, 1234L, pi)

        assertTrue(exakt)
        verify(am).setExactAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), eq(1234L), eq(pi))
        verify(am, never()).setAndAllowWhileIdle(any(), any(), anyOrNull())
    }
}
