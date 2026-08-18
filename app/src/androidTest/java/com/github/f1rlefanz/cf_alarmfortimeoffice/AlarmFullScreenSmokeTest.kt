package com.github.f1rlefanz.cf_alarmfortimeoffice

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Der Weck-Bildschirm gegen die echte Activity, das echte Theme und den echten Service-Zustand.
 *
 * WARUM DIESER TEST EXISTIERT: [AlarmFullScreenActivity] ist die einzige Oberflaeche, auf der ein
 * laufender Wecker gestoppt oder geschlummert werden kann — und sie ist die einzige Stelle der App,
 * die an `androidx.appcompat` haengt (sie erbt von [AppCompatActivity], und BEIDE App-Themes leiten
 * von `Theme.AppCompat.Light.NoActionBar` ab, siehe `res/values/themes.xml`). Ein
 * appcompat-/Theme-/Compose-Bump konnte diesen Bildschirm bisher unbemerkt beschaedigen: die
 * Unit-Tests decken nur die Handoff-Logik ab, und der Bildschirm ist am Geraet nicht ohne
 * Anmeldung erreichbar (er braucht einen echten Alarm aus dem Kalender).
 *
 * ABSICHTLICH UEBER DEN SERVICE-ZUSTAND, nicht ueber eine nackte Activity: [AlarmFullScreenActivity]
 * beobachtet [AlarmSoundService.alarmActive] und schliesst sich SOFORT wieder, solange dort `false`
 * steht (`observeAlarmState()`, das ist Absicht — sonst bliebe nach dem Stoppen ueber die
 * Notification ein totes Vollbild stehen). Ohne laufenden Service pruefte der Test also nur, wie
 * schnell sich die Activity beendet.
 *
 * TON: Der Service spielt echten Weckton. Vor dem Lauf `adb shell media volume --stream 4 --set 0`
 * setzen, sonst weckt der Test die halbe Wohnung. Der Test selbst regelt die Lautstaerke bewusst
 * NICHT — er soll den Weckvorgang nicht veraendern, den er prueft.
 *
 * Ausfuehren: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class AlarmFullScreenSmokeTest {

    /**
     * `createEmptyComposeRule`, weil die Activity mit EIGENEM Intent (Schichtname, Schichtbeginn,
     * Schlummer-Dauer) starten muss — eine Regel, die selbst startet, koennte die Extras nicht
     * mitgeben, und genau die sind der Inhalt des Bildschirms.
     */
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun vollbildZeigtSchichtUndBeideKnoepfe() {
        starteWeckton()
        try {
            ActivityScenario.launch<AlarmFullScreenActivity>(vollbildIntent()).use { scenario ->
                assertEquals(
                    "Vollbild-Wecker hat RESUMED nicht erreicht",
                    Lifecycle.State.RESUMED,
                    scenario.state
                )

                compose.onNodeWithText(SHIFT_NAME).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.alarm_shift_start, SHIFT_START))
                    .assertIsDisplayed()

                // Beide Knoepfe MUESSEN da und bedienbar sein. Fehlt einer, ist der Wecker aus
                // Nutzersicht nicht abstellbar - der schlimmste Zustand, den diese App haben kann.
                compose.onNodeWithText(context.getString(R.string.alarm_dismiss_button))
                    .assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithText(context.getString(R.string.alarm_snooze_button))
                    .assertIsDisplayed().assertHasClickAction()
            }
        } finally {
            stoppeWeckton()
        }
    }

    /**
     * Die WIEDERZUSTELLUNG an eine lebende Instanz — der Fall, den die beiden Tests darueber NICHT
     * abdecken, weil sie nur den onCreate-Weg gehen.
     *
     * Bei `launchMode="singleTask"` kommt ein zweiter Full-Screen-Intent als `onNewIntent` an
     * derselben Activity an; real passiert das, wenn der Schlummer-Wecker erneut feuert, waehrend
     * das Vollbild noch (gestoppt, aber nicht zerstoert) im Task liegt. Bis v1.25.3 wurden
     * Schichtname und -beginn in onCreate als lokale `val` in den setContent-Block eingeschlossen:
     * es gab keinen State, also keine Rekomposition, und der Bildschirm behauptete weiter die
     * VORHERIGE Schicht, waehrend Ton und Knoepfe schon zum neuen Wecker gehoerten. Wer im
     * Halbschlaf die falsche Uhrzeit liest, legt sich wieder hin.
     *
     * Der Test haelt genau das fest: nach der zweiten Zustellung muss die NEUE Schicht stehen und
     * die alte verschwunden sein.
     */
    @Test
    fun wiederzustellungZiehtSchichtUndBeginnNach() {
        starteWeckton()
        try {
            ActivityScenario.launch<AlarmFullScreenActivity>(vollbildIntent()).use { scenario ->
                compose.onNodeWithText(SHIFT_NAME).assertIsDisplayed()

                // Zweite Zustellung mit ANDEREN Extras. Ueber die Activity selbst gestartet, damit
                // singleTask sie an genau diese Instanz weiterreicht (onNewIntent) statt eine neue
                // anzulegen.
                scenario.onActivity { activity ->
                    activity.startActivity(
                        vollbildIntent().apply {
                            putExtra(AlarmSoundService.EXTRA_SHIFT_NAME, ZWEITE_SHIFT_NAME)
                            putExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME, ZWEITE_SHIFT_START)
                        }
                    )
                }

                compose.waitUntil(WAIT_TIMEOUT_MS) {
                    compose.onAllNodesWithText(ZWEITE_SHIFT_NAME).fetchSemanticsNodes().isNotEmpty()
                }

                compose.onNodeWithText(ZWEITE_SHIFT_NAME).assertIsDisplayed()
                compose.onNodeWithText(context.getString(R.string.alarm_shift_start, ZWEITE_SHIFT_START))
                    .assertIsDisplayed()
                assertEquals(
                    "Die vorherige Schicht darf nach der Wiederzustellung nicht mehr stehen",
                    0,
                    compose.onAllNodesWithText(SHIFT_NAME).fetchSemanticsNodes().size
                )
            }
        } finally {
            stoppeWeckton()
        }
    }

    /**
     * Die appcompat-Bindung selbst: Basisklasse UND das Fenster-Theme. Der rote
     * `windowBackground` ist das, was der Nutzer im Moment des Aufwachens sieht, bevor Compose
     * gezeichnet hat — faellt die Theme-Vererbung aus, ist er unangekuendigt weiss.
     */
    @Test
    fun activityHaengtAnAppCompatUndTraegtDasAlarmTheme() {
        starteWeckton()
        try {
            ActivityScenario.launch<AlarmFullScreenActivity>(vollbildIntent()).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(
                        "AlarmFullScreenActivity ist keine AppCompatActivity mehr - Theme.AppCompat.* " +
                            "in themes.xml passt dann nicht mehr zur Basisklasse",
                        activity is AppCompatActivity
                    )
                    val background = activity.window.decorView.background
                    assertTrue(
                        "windowBackground ist kein ColorDrawable (Theme nicht angewendet?)",
                        background is ColorDrawable
                    )
                    assertEquals(
                        "windowBackground ist nicht das Markenrot aus Theme.CFAlarmForTimeOffice.AlarmFullScreen",
                        ContextCompat.getColor(activity, R.color.brand_red),
                        (background as ColorDrawable).color
                    )
                }
            }
        } finally {
            stoppeWeckton()
        }
    }

    private fun vollbildIntent(): Intent =
        Intent(context, AlarmFullScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmSoundService.EXTRA_SHIFT_NAME, SHIFT_NAME)
            putExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME, SHIFT_START)
            putExtra(AlarmSoundService.EXTRA_ALARM_ID, ALARM_ID)
            putExtra(AlarmSoundService.EXTRA_SNOOZE_MINUTES, 5)
        }

    private fun starteWeckton() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, AlarmSoundService::class.java).apply {
                action = AlarmSoundService.ACTION_START_ALARM
                putExtra(AlarmSoundService.EXTRA_SHIFT_NAME, SHIFT_NAME)
                putExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME, SHIFT_START)
                putExtra(AlarmSoundService.EXTRA_ALARM_ID, ALARM_ID)
                putExtra(AlarmSoundService.EXTRA_SNOOZE_MINUTES, 5)
            }
        )
        warteBis("Wecker wurde nicht aktiv") { AlarmSoundService.alarmActive.value }
    }

    private fun stoppeWeckton() {
        context.startService(
            Intent(context, AlarmSoundService::class.java).apply {
                action = AlarmSoundService.ACTION_STOP_ALARM
            }
        )
        warteBis("Wecker liess sich nicht stoppen") { !AlarmSoundService.alarmActive.value }
    }

    private fun warteBis(fehler: String, bedingung: () -> Boolean) {
        val deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (bedingung()) return
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("$fehler (nach ${WAIT_TIMEOUT_MS} ms)")
    }

    private companion object {
        const val SHIFT_NAME = "Fruehdienst"
        const val SHIFT_START = "06:00"
        const val ZWEITE_SHIFT_NAME = "Spaetdienst"
        const val ZWEITE_SHIFT_START = "14:00"
        const val ALARM_ID = 987654
        const val WAIT_TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
    }
}
