package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit

/**
 * Die Zeitrechnung der beiden Abgleiche nach "TimeOffice öffnen".
 *
 * DER VORFALL, gegen den sie gebaut sind (12.10.2026): TimeOffice geoeffnet 15:50:28, Android
 * laedt 15:51:06 hoch, bei Google 15:52 - CF-Alarm sah den neuen Dienst trotzdem erst Stunden
 * spaeter. Der erste Abgleich muss also NACH diesen gut zwei Minuten liegen, der zweite faengt ein
 * langsameres Hochladen ab.
 */
class TimeOfficeNachfassAbgleichTest {

    private val min = TimeUnit.MINUTES.toMillis(1)

    // 12.10.2026 15:50:28 (MESZ) - die Rechnung ist rein relativ.
    private val druck = 1_791_813_028_000L

    @Test
    fun `nach dem Druck kommen genau zwei Abgleiche - nach 3 und nach 10 Minuten`() {
        val termine = TimeOfficeNachfassAbgleich.termine(druck)

        assertEquals(listOf(druck + 3 * min, druck + 10 * min), termine.map { it.zeitpunkt })
    }

    @Test
    fun `der erste Abgleich liegt hinter dem gemessenen Hochladen, der zweite danach`() {
        // Am Befundtag lag der Dienst 1 min 51 s nach dem Oeffnen bei Google. Ein Abgleich davor
        // saehe ihn nicht - und waere vergeudet.
        val beiGoogle = druck + TimeUnit.SECONDS.toMillis(111)
        val (erster, zweiter) = TimeOfficeNachfassAbgleich.termine(druck)

        assertTrue(erster.zeitpunkt > beiGoogle)
        assertTrue(zweiter.zeitpunkt > erster.zeitpunkt)
    }

    @Test
    fun `jeder Abgleich hat seinen festen Slot - ein zweiter Druck ersetzt, statt zu stapeln`() {
        // FLAG_UPDATE_CURRENT auf einem festen Request-Code: AlarmManager ersetzt den Alarm mit
        // gleichem PendingIntent. Wechselte der Code mit dem Zeitpunkt, kaemen nach drei Druecken
        // sechs Wartungslaeufe.
        val ersterDruck = TimeOfficeNachfassAbgleich.termine(druck)
        val zweiterDruck = TimeOfficeNachfassAbgleich.termine(druck + 2 * min)

        assertEquals(ersterDruck.map { it.requestCode }, zweiterDruck.map { it.requestCode })
        assertNotEquals(ersterDruck[0].requestCode, ersterDruck[1].requestCode)
    }

    @Test
    fun `die Slots kollidieren mit keiner anderen Kette - kein zweiter Planer der 6h-Wartung`() {
        // Auf Code 0 ersetzte ein Nachfass-Abgleich den naechsten regulaeren Wartungslauf, auf
        // 8801/8802 den Nachhol-Alarm bzw. den Wachhund, auf 8803 die Rufbereitschafts-Abfrage.
        val belegt = setOf(
            WartungsKettenPlanung.REGULAER_REQUEST_CODE,
            WartungsKettenPlanung.NACHHOL_REQUEST_CODE,
            WartungsKettenPlanung.WACHHUND_REQUEST_CODE,
            RufbereitschaftAbfrage.REQUEST_CODE
        )
        val eigene = TimeOfficeNachfassAbgleich.termine(druck).map { it.requestCode }.toSet()

        assertEquals(setOf(8804, 8805), eigene)
        assertTrue("Slot doppelt belegt: ${eigene intersect belegt}", (eigene intersect belegt).isEmpty())
    }

    // ---- Abraeumen fuer die Master-Pause (Zwilling von G11-14) ----
    //
    // Bis dahin kannte pause() die beiden Alarme nicht: pausierte der Nutzer innerhalb von zehn
    // Minuten nach "TimeOffice öffnen", startete jeder noch den Wartungs-Vordergrunddienst samt
    // Benachrichtigung - mitten in der Pause. abbrechen() hilft nur, wenn es GENAU die PendingIntents
    // trifft, die plane() gestellt hat; ein anders gebauter liefe ins Leere, und der Alarm bliebe.

    @Test
    fun `abbrechen trifft genau die beiden Slots, die plane gestellt hat`() {
        val alarmManager = mock<AlarmManager>()
        val context = mock<Context>()
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        // Ein PendingIntent je (Request-Code, Flags) - so wie Android ihn wiederfindet. Im
        // Unit-Test-Modus liefert getBroadcast() sonst null, und gestellt/abgeraeumt waeren nicht
        // unterscheidbar.
        val gebaut = mutableMapOf<Pair<Int, Int>, PendingIntent>()

        Mockito.mockStatic(PendingIntent::class.java).use { statisch ->
            statisch.`when`<PendingIntent> {
                PendingIntent.getBroadcast(anyOrNull(), anyInt(), anyOrNull(), anyInt())
            }.thenAnswer { aufruf ->
                gebaut.getOrPut(aufruf.getArgument<Int>(1) to aufruf.getArgument<Int>(3)) { mock() }
            }

            TimeOfficeNachfassAbgleich.plane(context)
            val gestellt = argumentCaptor<PendingIntent>()
            verify(alarmManager, times(2)).setExactAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), any(), gestellt.capture())

            TimeOfficeNachfassAbgleich.abbrechen(context)
            val abgeraeumt = argumentCaptor<PendingIntent>()
            verify(alarmManager, times(2)).cancel(abgeraeumt.capture())

            assertEquals(2, gestellt.allValues.toSet().size)
            assertEquals(gestellt.allValues.toSet(), abgeraeumt.allValues.toSet())
            // Nur die eigenen Slots - kein Cancel auf 0/8801/8802/8803 (6h-Kette, Rufbereitschaft).
            assertEquals(setOf(8804, 8805), gebaut.keys.map { it.first }.toSet())
        }
    }

    @Test
    fun `abbrechen wirft nie - die Pause darf an diesem Schritt nicht zerreissen`() {
        val context = mock<Context>()
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenThrow(RuntimeException("boom"))

        TimeOfficeNachfassAbgleich.abbrechen(context)

        verify(context, times(1)).getSystemService(Context.ALARM_SERVICE)
    }

    @Test
    fun `abbrechen ist ungegatet - kein Blick auf den Pausen-Spiegel`() {
        // Wie disable() bei Dimmer und DND: ein Abraeumen fragt nicht, ob es darf. Haengte es am
        // Pausen-Spiegel, liefe es genau in der Pause - seinem einzigen Anlass - ins Leere.
        val alarmManager = mock<AlarmManager>()
        val context = mock<Context>()
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)

        TimeOfficeNachfassAbgleich.abbrechen(context)

        verify(context, never()).applicationContext
        verify(alarmManager, times(2)).cancel(anyOrNull<PendingIntent>())
    }
}
