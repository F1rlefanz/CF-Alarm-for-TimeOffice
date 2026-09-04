package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream

/**
 * Das Vorwecken am echten System - fuer den Fall, den es bis 1.39.4 gar nicht geben konnte.
 *
 * WAS HIER GEPRUEFT WIRD: Auf einem Geraet, das **noch nie** eine Verdraengung gemeldet hat,
 * weckt ein startender Wecker den dunklen, gesperrten Bildschirm von selbst. Bis 1.39.4 tat er
 * das nicht - dort entschied ein gespeicherter Merker (`je_verdraengt`) darueber, und ohne ihn
 * lief der Wecker unveraendert. Genau deshalb war der erste Wecker nach einem naechtlichen
 * Neustart ungeschuetzt: der Merker lag im CE-Storage und ist vor der ersten Entsperrung nicht
 * lesbar. Der Test stellt diesen Zustand her, indem er die Preferences vorher LEERT.
 *
 * WARUM AM GERAET UND NICHT ALS UNIT-TEST: [VorweckEntscheidungTest] deckt die Entscheidung ab,
 * aber nicht die Verdrahtung - dass [AlarmSoundService] die beiden Systemwerte wirklich liest,
 * den Wake-Lock wirklich nimmt und der Bildschirm davon wirklich angeht. Das haengt an
 * `ACQUIRE_CAUSES_WAKEUP` ohne die Berechtigung `TURN_SCREEN_ON`, also an einem Kompat-Schalter
 * (`REQUIRE_TURN_SCREEN_ON_PERMISSION`) - der Tag, an dem Google ihn scharf schaltet, ist genau
 * der Tag, an dem dieser Test rot wird. Hergang: Skill cfalarm-wecker-und-boot,
 * `reference/vorwecken.md`.
 *
 * NUR AM EMULATOR: Der Test schaltet den Bildschirm aus und laesst einen Wecker klingeln. Auf dem
 * produktiv genutzten Geraet des Eigentuemers hat er nichts verloren - `connectedDebugAndroidTest`
 * trifft bei zwei angesteckten Geraeten sonst auch das Fairphone. Er ueberspringt sich dort per
 * [assumeTrue] selbst.
 *
 * VORBEDINGUNG: eine Bildschirmsperre (`adb shell locksettings set-pin 1234`). Ohne PIN meldet
 * `isKeyguardLocked` dauerhaft `false`, die Bedingung waere nicht herstellbar - der Test
 * ueberspringt sich dann ebenfalls, statt gruen zu luegen.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VorweckenAmGeraetTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val power: PowerManager get() = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguard: KeyguardManager
        get() = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager

    @After
    fun raeumeAuf() {
        if (AlarmSoundService.alarmActive.value) {
            context.startService(
                Intent(context, AlarmSoundService::class.java).apply {
                    action = AlarmSoundService.ACTION_STOP_ALARM
                }
            )
            warteBis(5_000L) { !AlarmSoundService.alarmActive.value }
        }
        // Bildschirm wieder an lassen, damit ein Folgetest nicht im Dunkeln startet.
        shell("input keyevent KEYCODE_WAKEUP")
    }

    @Test
    fun ohneJedenMerkerWirdDerDunkleGesperrteBildschirmGeweckt() {
        assumeTrue("Nur am Emulator - auf einem echten Geraet klingelt sonst ein Wecker", istEmulator())

        // DER AUSGANGSZUSTAND, AUF DEN ES ANKOMMT: keinerlei lesbares Gedaechtnis einer
        // Verdraengung. Bis 1.39.4 hiess das "dieses Geraet ist nicht betroffen" und schaltete das
        // Vorwecken ab.
        //
        // DAS try/catch IST KEIN SCHUTZ VOR EINEM FEHLER, SONDERN DER ZWEITE PRUEFFALL: Laeuft der
        // Test vor der ersten Entsperrung (Direct Boot), wirft schon der Zugriff auf die
        // Preferences - "SharedPreferences in credential encrypted storage are not available until
        // after user (id 0) is unlocked". Genau das war die Luecke, die 1.39.5 geschlossen hat: der
        // alte Merker war in dieser Lage grundsaetzlich nicht lesbar, das Vorwecken also aus.
        // Beide Wege muessen hier zum selben Ergebnis fuehren.
        val ceLesbar = try {
            context.getSharedPreferences("weckbildschirm_verdraengung", Context.MODE_PRIVATE)
                .edit().clear().commit()
            true
        } catch (e: IllegalStateException) {
            false
        }
        Log.i(
            "VorweckenAmGeraetTest",
            if (ceLesbar) "CE-Storage lesbar - Merker geleert (Fall: frisches, entsperrtes Geraet)"
            else "CE-Storage GESPERRT (Direct Boot) - der alte Merker waere hier unlesbar gewesen"
        )

        shell("input keyevent KEYCODE_SLEEP")
        val dunkel = warteBis(5_000L) { !power.isInteractive }
        assumeTrue("Bildschirm liess sich nicht ausschalten", dunkel)
        // Mit Wartezeit, nicht sofort: der Keyguard kommt nach dem Ausschalten des Bildschirms
        // je nach Einstellung mit kurzer Verzoegerung. Ohne das Warten wuerde der Test auf einem
        // sonst tauglichen Emulator gelegentlich uebersprungen statt zu pruefen.
        assumeTrue(
            "Kein Sperrbildschirm aktiv - ohne PIN ist die Bedingung nicht herstellbar " +
                "(adb shell locksettings set-pin 1234)",
            warteBis(5_000L) { keyguard.isKeyguardLocked }
        )

        ContextCompat.startForegroundService(
            context,
            Intent(context, AlarmSoundService::class.java).apply {
                action = AlarmSoundService.ACTION_START_ALARM
                putExtra(AlarmSoundService.EXTRA_SHIFT_NAME, "Fruehdienst")
                putExtra(AlarmSoundService.EXTRA_SHIFT_START_TIME, "06:00")
                putExtra(AlarmSoundService.EXTRA_ALARM_ID, 987655)
            }
        )

        // Der Wake-Lock wird VOR dem Vorlauf genommen; der Bildschirm muss also deutlich frueher
        // an sein als die Notification kommt. Grosszuegig gemessen, damit ein langsamer Emulator
        // den Test nicht rot macht - es geht um "ueberhaupt", nicht um Millisekunden.
        val geweckt = warteBis(VorweckEntscheidung.VORLAUF_MS + 4_000L) { power.isInteractive }

        assertTrue(
            "Der Bildschirm blieb aus, obwohl er dunkel und gesperrt war. Entweder liest " +
                "AlarmSoundService die Vorweck-Bedingung nicht mehr, oder ACQUIRE_CAUSES_WAKEUP " +
                "weckt ohne TURN_SCREEN_ON nicht mehr (Kompat-Schalter " +
                "REQUIRE_TURN_SCREEN_ON_PERMISSION scharf geschaltet). Beides macht die Abhilfe " +
                "gegen die Weckbildschirm-Verdraengung wirkungslos.",
            geweckt
        )
        assertTrue("Der Wecker lief gar nicht an", AlarmSoundService.alarmActive.value)
    }

    private fun istEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.PRODUCT.startsWith("sdk") ||
            Build.PRODUCT.contains("emulator", ignoreCase = true)

    private fun warteBis(timeoutMs: Long, bedingung: () -> Boolean): Boolean {
        val ende = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < ende) {
            if (bedingung()) return true
            Thread.sleep(100L)
        }
        return bedingung()
    }

    /** Shell-Befehl unter der Shell-UID - der Testprozess selbst darf den Bildschirm nicht schalten. */
    private fun shell(befehl: String) {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(befehl)
        FileInputStream(pfd.fileDescriptor).use { it.readBytes() }
    }
}
