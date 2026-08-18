package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Befund 11 (Pruefrunde 6), Sichtbarkeits-Haelfte: Die Exact-Alarm-Berechtigung war die einzige
 * weckerkritische, entziehbare Berechtigung ohne Status-Karte und ohne Onboarding-Gate. Wurde sie
 * entzogen, loeschte Android alle gestellten Wecker - und die App zeigte weiter ihre Alarmliste
 * aus dem Repository, also einen vollstaendig gesunden Eindruck.
 *
 * Diese Tests halten die Anzeige-Entscheidung fest. Der gefaehrlichste denkbare Rueckbau ist
 * "ab API 33 kann die Berechtigung nicht fehlen, also blenden wir dort immer aus" - genau das
 * galt fuer USE_FULL_SCREEN_INTENT auch, bis der Play Store sie ab Android 14 nachtraeglich
 * entzog. Test 4 verhindert ihn.
 */
class Pruefrunde6ExactAlarmKarteTest {

    private val android12 = Build.VERSION_CODES.S          // 31 - hier ist sie entziehbar
    private val android14 = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    private val android8 = Build.VERSION_CODES.O           // 26 = minSdk, Berechtigung existiert nicht

    @Test
    fun `entzogen auf Android 12 - die Karte warnt`() {
        assertEquals(
            ExaktAlarmKartenZustand.ENTZOGEN,
            exaktAlarmKartenZustand(android12, darfExakteAlarme = false)
        )
    }

    @Test
    fun `erteilt auf Android 12 - dauerhaft ablesbar, weil der Zustand dort kippen kann`() {
        assertEquals(
            ExaktAlarmKartenZustand.ERTEILT,
            exaktAlarmKartenZustand(android12, darfExakteAlarme = true)
        )
    }

    @Test
    fun `erteilt ab Android 13 - kein Rauschen, dort ist USE_EXACT_ALARM nicht entziehbar`() {
        assertEquals(
            ExaktAlarmKartenZustand.AUSGEBLENDET,
            exaktAlarmKartenZustand(android14, darfExakteAlarme = true)
        )
        assertEquals(
            ExaktAlarmKartenZustand.AUSGEBLENDET,
            exaktAlarmKartenZustand(android8, darfExakteAlarme = true)
        )
    }

    @Test
    fun `fehlt sie trotzdem ab Android 13, wird sie NICHT weggeblendet`() {
        assertEquals(
            "Eine fehlende weckerkritische Berechtigung darf nie unsichtbar sein - dieselbe " +
                "Annahme kostete bei USE_FULL_SCREEN_INTENT schon einmal den Weck-Bildschirm",
            ExaktAlarmKartenZustand.ENTZOGEN,
            exaktAlarmKartenZustand(android14, darfExakteAlarme = false)
        )
    }

    @Test
    fun `nur der Uebergang entzogen zu erteilt stoesst die Wartungskette an`() {
        assertTrue(
            brauchtWiederanlaufNachErteilung(
                ExaktAlarmKartenZustand.ENTZOGEN,
                ExaktAlarmKartenZustand.ERTEILT
            )
        )
        assertTrue(
            brauchtWiederanlaufNachErteilung(
                ExaktAlarmKartenZustand.ENTZOGEN,
                ExaktAlarmKartenZustand.AUSGEBLENDET
            )
        )
    }

    /**
     * DIE REGRESSION DES ERSTEN FIXES: Der Uebergang loeste `AlarmMaintenanceService.start(context)`
     * aus - mit dem Default `forceSync = false`. Der Entzug loescht aber nur die
     * AlarmManager-Eintraege, NICHT den Repository-Bestand: das Lade-Gate
     * (`MaintenanceLoadDecision.shouldLoadEvents`) sah also Zukunftsalarme, frische Daten und
     * ausreichenden Puffer, der Lauf endete mit "buffer sufficient" - und `syncAlarms()`, der
     * einzige Ort, der Bestandsalarme wieder armiert, wurde nie erreicht. Der schwebende
     * Schlummer kam ueberhaupt nur durch einen Geraeteneustart zurueck (einziger Aufrufer von
     * `restorePendingSnoozes()` war der BootReceiver). Die Karte sprang trotzdem auf gruen und
     * sagte zu, die Wecker seien neu gestellt: ein stummer Wecker MIT Anzeige.
     *
     * Geprueft an der Quelle, weil die Ausloesung in einem `DisposableEffect` einer Composable
     * steckt.
     */
    @Test
    fun `der Wiederanlauf erzwingt den Lauf und holt den Schlummer zurueck`() {
        val karte = kartenQuelltext()

        assertTrue(
            "Ohne forceSync=true ueberspringt das Lade-Gate den Lauf, und syncAlarms() - der " +
                "einzige Re-Armierer - wird nie erreicht",
            karte.contains("AlarmMaintenanceService.start(context, forceSync = true)")
        )
        assertTrue(
            "Ein schwebender Schlummer kaeme sonst erst nach einem Geraeteneustart zurueck",
            karte.contains("AlarmManagerService.restorePendingSnoozes(context)")
        )
    }

    /**
     * Kein Text darf einen Ablauf behaupten, den es nicht gibt: ein MANUELLER Wecker wird nirgends
     * nachgestellt (`syncAlarms()` schont ihn nur, `keepManualAlarms`), er muss also im Kartentext
     * als Aufgabe des Nutzers stehen.
     */
    @Test
    fun `der Kartentext verspricht keine Wiederherstellung des manuellen Weckers`() {
        val karte = kartenQuelltext()

        assertTrue(
            "Der ENTZOGEN-Text muss den manuell angelegten Wecker ausdruecklich ausnehmen",
            karte.contains("manuell angelegten Wecker musst du selbst neu stellen")
        )
        assertFalse(
            "\"dann werden die Wecker sofort neu gestellt\" war die Zusage, die der Ausloeser " +
                "nicht einloesen konnte",
            karte.contains("dann werden die Wecker sofort neu gestellt")
        )
    }

    /**
     * Der Quelltext der Karte von ihrer Deklaration bis zur naechsten Top-Level-Funktion - so
     * treffen die Zusicherungen oben wirklich diese Karte und nicht irgendeine Nachbarkarte.
     */
    private fun kartenQuelltext(): String {
        val pfad = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/ui/screens/tabs/" +
            "StatusPermissionCards.kt"
        val datei = listOf(File(pfad), File("app/$pfad")).firstOrNull { it.isFile }
            ?: error("StatusPermissionCards.kt nicht gefunden (Arbeitsverzeichnis ${File(".").absolutePath})")
        val zeilen = datei.readLines()
        val start = zeilen.indexOfFirst { it.startsWith("internal fun ExactAlarmPermissionCard(") }
        require(start >= 0) { "ExactAlarmPermissionCard() nicht gefunden - umbenannt?" }
        val ende = zeilen.drop(start + 1).indexOfFirst { it == "}" }
        require(ende >= 0) { "Ende von ExactAlarmPermissionCard() nicht gefunden" }
        return zeilen.subList(start, start + 1 + ende + 1).joinToString("\n")
    }

    @Test
    fun `ohne Uebergang bleibt die Wartungskette unangetastet`() {
        // Sonst startete jedes ON_RESUME einen Wartungslauf - ein Dauer-Anstoss ohne Anlass.
        assertFalse(
            brauchtWiederanlaufNachErteilung(
                ExaktAlarmKartenZustand.ERTEILT,
                ExaktAlarmKartenZustand.ERTEILT
            )
        )
        assertFalse(
            brauchtWiederanlaufNachErteilung(
                ExaktAlarmKartenZustand.AUSGEBLENDET,
                ExaktAlarmKartenZustand.AUSGEBLENDET
            )
        )
        assertFalse(
            "Der Entzug selbst darf nichts anstossen - planen kann die App in dem Moment ohnehin nichts",
            brauchtWiederanlaufNachErteilung(
                ExaktAlarmKartenZustand.ERTEILT,
                ExaktAlarmKartenZustand.ENTZOGEN
            )
        )
    }
}
