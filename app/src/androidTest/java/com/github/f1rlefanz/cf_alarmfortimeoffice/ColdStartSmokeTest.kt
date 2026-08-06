package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Kaltstart gegen den ECHTEN Hilt-Graphen, den echten DataStore und die echte Navigation.
 *
 * WARUM DIESER TEST EXISTIERT — die Luecke, durch die ein Absturz gefallen ist:
 * Am 05.08.2026 crashte die App auf dem Fairphone beim allerersten Start. Ursache war eine
 * Property in [com.github.f1rlefanz.cf_alarmfortimeoffice.viewmodel.CalendarViewModel], die
 * textuell NACH dem `init{}`-Block stand: Kotlin initialisiert in Textreihenfolge, und ein
 * `StateFlow`-Collect in `init{}` feuert auf `Dispatchers.Main.immediate` noch WAEHREND der
 * Objekt-Konstruktion — die Property war da noch `null`.
 *
 * 329 gruene Unit-Tests und ein gruener Build haben das NICHT gefangen, weil kein Unit-Test das
 * echte Hilt-Konstruktionstiming nachbildet. Gefunden hat es erst die Installation auf dem Geraet.
 * Genau diese Klasse von Fehlern — "der Graph laesst sich gar nicht bauen" — faengt dieser Test.
 *
 * ABSICHTLICH KEINE Hilt-Test-Infrastruktur (`hilt-android-testing`, eigener Runner): die soll man
 * nur einziehen, wenn man Bindings ERSETZEN will. Hier ist der echte, unveraenderte Graph genau das
 * Pruefobjekt. Deshalb braucht dieser Test auch keine neue Gradle-Abhaengigkeit.
 *
 * ABSICHTLICH OHNE Annahmen ueber den Anmeldezustand: der Test laeuft auf jedem Geraet, angemeldet
 * oder nicht. Er behauptet nur, was in JEDEM Fall gelten muss — die Activity kommt hoch und
 * erreicht RESUMED, statt beim Aufbau des Objektgraphen zu sterben.
 *
 * Ausfuehren: ./gradlew --offline connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ColdStartSmokeTest {

    /**
     * Die Application selbst muss sich aufbauen lassen. Schlaegt schon das fehl, ist der
     * Hilt-Graph oder `CFAlarmApplication.initializeApp()` kaputt — dann startet die App auf
     * keinem Geraet.
     */
    @Test
    fun applicationKommtHoch() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull("Application-Kontext ist null", app)
        assertEquals(
            "Falsche Application-Klasse — laeuft der Test gegen die echte App?",
            CFAlarmApplication::class.java.name,
            app.javaClass.name
        )
    }

    /**
     * Der eigentliche Regressionstest: MainActivity starten und RESUMED erreichen. Das baut den
     * kompletten ViewModel-Satz ueber Hilt (Auth/Calendar/Shift/Alarm/Hue/Main/Navigation/Dimmer),
     * setzt Compose auf und laesst alle `init{}`-Bloecke laufen — inklusive der StateFlow-Collects,
     * die den Absturz vom 05.08.2026 ausgeloest haben.
     */
    @Test
    fun mainActivityErreichtResumedOhneAbsturz() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertEquals(
                "MainActivity hat RESUMED nicht erreicht — Absturz beim Aufbau des Objektgraphen?",
                Lifecycle.State.RESUMED,
                scenario.state
            )
        }
    }

    /**
     * Zweiter Start in derselben Sitzung. Deckt den Fall ab, dass ein Singleton-Zustand beim ersten
     * Start etwas hinterlaesst, das den zweiten Aufbau kippt — z.B. ein einmal gecancelter Scope
     * eines prozesslebenslangen Singletons (dafuer gibt es in dieser App real eine Falle:
     * `HueBridgeConnectionManager.cleanup()`), oder eine nicht-idempotente `initialize()`.
     */
    @Test
    fun mainActivityUeberlebtZweitenStart() {
        ActivityScenario.launch(MainActivity::class.java).use { first ->
            assertEquals(Lifecycle.State.RESUMED, first.state)
        }
        ActivityScenario.launch(MainActivity::class.java).use { second ->
            assertEquals(
                "Zweiter Start scheitert — nicht-idempotente Initialisierung oder gecancelter Singleton-Scope?",
                Lifecycle.State.RESUMED,
                second.state
            )
        }
    }
}
