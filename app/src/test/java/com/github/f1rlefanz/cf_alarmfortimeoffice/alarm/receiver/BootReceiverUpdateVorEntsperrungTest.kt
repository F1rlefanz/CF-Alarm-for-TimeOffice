package com.github.f1rlefanz.cf_alarmfortimeoffice.alarm.receiver

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ein App-Update VOR der ersten Entsperrung darf den CE-Speicher nicht anfassen.
 *
 * DER BEFUND (Emulator, 13.10.2026): Update per `install -r`, waehrend der Nutzer seit dem Neustart
 * noch nicht entsperrt hatte. MY_PACKAGE_REPLACED startete die volle Recovery; deren
 * `armiereNebenketten` rief Dimmer-`enable()`, und das las den settings-DataStore im gesperrten
 * Zustand - still "leer", fuer den ganzen Prozess. Nach dem Entsperren lief im selben Prozess die
 * Dimmer-Migration auf diesem Leer und schrieb "Dimmer aus, 0 Regeln" zurueck: Einstellungen weg.
 *
 * Geprueft wird die Verdrahtung, weil der Fehler nur auf einem echten System entsteht (gesperrter
 * CE-Speicher); der Geraetetest dazu steht im Skill cfalarm-wecker-und-boot.
 */
class BootReceiverUpdateVorEntsperrungTest {

    private val quelle: String = listOf(
        File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/alarm/receiver/BootReceiver.kt"),
        File("app/src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/alarm/receiver/BootReceiver.kt")
    ).first { it.exists() }.readText()

    @Test
    fun `im Update-Zweig steht die Entsperrungs-Pruefung VOR der vollen Recovery`() {
        val zweig = quelle.indexOf("ACTION_MY_PACKAGE_REPLACED ->")
        val ende = quelle.indexOf("else ->", zweig)
        val block = quelle.substring(zweig, ende)

        val spiegel = block.indexOf("restoreAlarmsFromDirectBootStore(")
        val pruefung = block.indexOf("NutzerEntsperrung.istEntsperrt(")
        val anker = block.indexOf("startMaintenanceAnchor(")
        val recovery = block.indexOf("performCompleteSystemRecovery(")

        assertTrue("Spiegel-Restore laeuft immer zuerst", spiegel in 0 until pruefung)
        assertTrue("Pruefung vor Wartungs-Anker", pruefung < anker)
        assertTrue("Pruefung vor der vollen Recovery", pruefung < recovery)
        assertTrue("gesperrt: der Zweig endet vor der Recovery", block.substring(pruefung, recovery).contains("return"))
    }
}
