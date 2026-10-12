package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.TimeUnit

/**
 * Der Weg durch den ECHTEN [AlarmMaintenanceService.scheduleNext]/[AlarmMaintenanceService.cancelNext]
 * mit dem Termin-Merker [WartungsKettenTermin] - die Regel selbst pruefen
 * [WartungsKettenZielterminTest].
 *
 * Gestellt wird gegen einen gemockten AlarmManager; der PendingIntent ist in den JVM-Tests null
 * (Android-Stubs liefern Defaultwerte), deshalb `anyOrNull()`. Unter den Stubs ist
 * `Build.VERSION.SDK_INT` 0, also gelten exakte Alarme als erlaubt.
 */
class WartungsKettenTerminTest {

    private val sechsStunden = TimeUnit.HOURS.toMillis(WartungsKettenPlanung.INTERVALL_STUNDEN)
    private val karenz = TimeUnit.MINUTES.toMillis(WartungsKettenPlanung.WACHHUND_KARENZ_MINUTEN)

    private class Aufbau(val context: Context, val alarmManager: AlarmManager, val prefs: FakePrefs)

    private fun aufbau(): Aufbau {
        val prefs = FakePrefs()
        val deContext = mock<Context>()
        whenever(deContext.getSharedPreferences(eq(WartungsKettenTermin.DATEI), any())).thenReturn(prefs)
        val alarmManager = mock<AlarmManager>()
        val context = mock<Context>()
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)
        whenever(context.createDeviceProtectedStorageContext()).thenReturn(deContext)
        return Aufbau(context, alarmManager, prefs)
    }

    private fun gestellterRegulaererLauf(a: Aufbau): Long {
        val zeit = argumentCaptor<Long>()
        verify(a.alarmManager).setExactAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), zeit.capture(), anyOrNull<PendingIntent>())
        return zeit.firstValue
    }

    private fun gestellterWachhund(a: Aufbau): Long {
        val zeit = argumentCaptor<Long>()
        verify(a.alarmManager).setAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), zeit.capture(), anyOrNull<PendingIntent>())
        return zeit.firstValue
    }

    @Test
    fun `Eintritt in die App - ein gemerkter Termin wird auf denselben Zeitpunkt neu gestellt`() {
        val a = aufbau()
        val gemerkt = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)
        a.prefs.werte["naechster_regulaerer_lauf"] = gemerkt

        AlarmMaintenanceService.scheduleNext(a.context, frueherenTerminBehalten = true)

        assertEquals("Der regulaere Lauf wurde verschoben", gemerkt, gestellterRegulaererLauf(a))
        assertEquals("Der Wachhund folgt nicht dem behaltenen Termin", gemerkt + karenz, gestellterWachhund(a))
        assertEquals(gemerkt, WartungsKettenTermin.lies(a.context))
    }

    @Test
    fun `Default-Aufrufer planen ab jetzt und merken den neuen Termin`() {
        val a = aufbau()
        a.prefs.werte["naechster_regulaerer_lauf"] = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)

        val vorher = System.currentTimeMillis()
        AlarmMaintenanceService.scheduleNext(a.context)
        val nachher = System.currentTimeMillis()

        val gestellt = gestellterRegulaererLauf(a)
        assertTrue(
            "erwartet jetzt + 6 h, gestellt $gestellt",
            gestellt in (vorher + sechsStunden)..(nachher + sechsStunden)
        )
        assertEquals("Gemerkt werden muss der GESTELLTE Termin", gestellt, WartungsKettenTermin.lies(a.context))
    }

    @Test
    fun `ohne gemerkten Termin stellt auch der Eintritt ab jetzt`() {
        val a = aufbau()

        val vorher = System.currentTimeMillis()
        AlarmMaintenanceService.scheduleNext(a.context, frueherenTerminBehalten = true)
        val nachher = System.currentTimeMillis()

        val gestellt = gestellterRegulaererLauf(a)
        assertTrue(gestellt in (vorher + sechsStunden)..(nachher + sechsStunden))
        assertEquals(gestellt, WartungsKettenTermin.lies(a.context))
    }

    @Test
    fun `die Master-Pause kappt die Kette und vergisst den Termin`() {
        val a = aufbau()
        a.prefs.werte["naechster_regulaerer_lauf"] = System.currentTimeMillis() + TimeUnit.HOURS.toMillis(2)

        AlarmMaintenanceService.cancelNext(a.context)

        verify(a.alarmManager, times(2)).cancel(anyOrNull<PendingIntent>())
        assertNull(
            "Sonst kuendigte der Status-Tab waehrend der Pause einen Abgleich an, der nie kommt",
            WartungsKettenTermin.lies(a.context)
        )
    }

    /**
     * Der Merker ist Hilfsinformation. Ist der Device-Protected Storage nicht erreichbar (hier:
     * der gemockte Context liefert null), muss die Kette trotzdem stehen - sonst kostete ein
     * Anzeige-Detail die gesamte Wartung.
     */
    @Test
    fun `ein unerreichbarer Merker verhindert weder Stellen noch Kappen`() {
        val alarmManager = mock<AlarmManager>()
        val context = mock<Context>()
        whenever(context.getSystemService(Context.ALARM_SERVICE)).thenReturn(alarmManager)

        AlarmMaintenanceService.scheduleNext(context, frueherenTerminBehalten = true)
        AlarmMaintenanceService.cancelNext(context)

        verify(alarmManager).setExactAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), any(), anyOrNull<PendingIntent>())
        verify(alarmManager).setAndAllowWhileIdle(eq(AlarmManager.RTC_WAKEUP), any(), anyOrNull<PendingIntent>())
        verify(alarmManager, times(2)).cancel(anyOrNull<PendingIntent>())
        assertNull(WartungsKettenTermin.lies(context))
    }

    // ------------------------------------------------------------------
    // Helfer
    // ------------------------------------------------------------------

    /** Minimale SharedPreferences im Speicher - nur Long-Werte, mehr braucht der Merker nicht. */
    private class FakePrefs : SharedPreferences {
        val werte = mutableMapOf<String, Long>()

        override fun getLong(key: String?, defValue: Long): Long = werte[key] ?: defValue
        override fun contains(key: String?): Boolean = werte.containsKey(key)
        override fun getAll(): MutableMap<String, *> = werte.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = defValue
        override fun getFloat(key: String?, defValue: Float): Float = defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val gesetzt = mutableMapOf<String, Long>()
            private val entfernt = mutableSetOf<String>()

            override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                gesetzt[key] = value
                return this
            }
            override fun remove(key: String): SharedPreferences.Editor {
                entfernt += key
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                entfernt += werte.keys
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                entfernt.forEach { werte.remove(it) }
                werte.putAll(gesetzt)
            }
            override fun putString(key: String?, value: String?) = this
            override fun putStringSet(key: String?, values: MutableSet<String>?) = this
            override fun putInt(key: String?, value: Int) = this
            override fun putFloat(key: String?, value: Float) = this
            override fun putBoolean(key: String?, value: Boolean) = this
        }
    }
}
