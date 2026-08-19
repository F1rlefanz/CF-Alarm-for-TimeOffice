package com.github.f1rlefanz.cf_alarmfortimeoffice

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zwei Zusicherungen des Weck-Bildschirms, die sich ohne Geraet nur an der Quelle festhalten
 * lassen - und die beide schon einmal gebrochen waren.
 *
 * WARUM QUELLTEXT STATT VERHALTEN: Beides haengt an Android-Bausteinen, die im JVM-Unit-Test nicht
 * existieren (Ressourcen-Formatierung, Activity-Lebenszyklus). Der Weck-Bildschirm hat dafuer einen
 * Instrumentierungstest ([AlarmFullScreenSmokeTest] im androidTest-Quellsatz), der aber ein
 * angestecktes, waches Geraet braucht und deshalb nicht bei jedem Lauf mitkommt. Dieselbe Bauart
 * sichert in `NotificationDeliverabilityTest` bereits die Kanal-ID ab.
 */
class AlarmFullScreenVertragTest {

    @Test
    fun `beide Schlummer-Knoepfe tragen einen Platzhalter statt einer festen Zahl`() {
        // DER BEFUND (Pruefrunde 7): Die Schlummer-Dauer ist einstellbar (3/5/10/15) und wird bis
        // in die Planung durchgereicht - beide Knoepfe trugen aber den festen Text "5 Min spaeter".
        // Bei eingestellten 15 Minuten stand also "5 MIN SPAETER" auf dem Knopf, der 15 Minuten
        // schlummert, an dem einen Bildschirm, den ein halb wacher Mensch bedient.
        val strings = ressource("values/strings.xml").readText()

        listOf("alarm_snooze_button", "alarm_notification_snooze").forEach { name ->
            val zeile = strings.lines().firstOrNull { it.contains("name=\"$name\"") }
                ?: error("String $name nicht gefunden")
            assertTrue(
                "$name traegt keinen Platzhalter mehr - der Knopf behauptet dann wieder eine " +
                    "Dauer, die er nicht schlummert",
                zeile.contains("%1\$d")
            )
        }
    }

    @Test
    fun `Beschriftung und Wirkung des Schlummerns kommen aus einer Quelle`() {
        val activity = quelldatei("AlarmFullScreenActivity.kt").readText()

        assertTrue(
            "Der Vollbild-Knopf bekommt die Dauer nicht mehr uebergeben",
            activity.contains("stringResource(R.string.alarm_snooze_button, snoozeMinutes)")
        )
        // Der Wert darf nur EINMAL aus dem Intent kommen. Ein zweiter Read in snoozeAlarm() waere
        // genau die zweite Quelle, an der Beschriftung und Wirkung auseinanderlaufen koennen.
        assertFalse(
            "snoozeAlarm() liest die Dauer wieder selbst aus dem Intent - dann koennen Knopf und " +
                "Planung erneut verschiedene Werte tragen",
            activity.contains("val snoozeMinutes = intent.getIntExtra")
        )

        val service = quelldatei("service/AlarmSoundService.kt").readText()
        assertTrue(
            "Der Notification-Knopf bekommt die Dauer nicht mehr uebergeben",
            service.contains("getString(R.string.alarm_notification_snooze, snoozeMinutes)")
        )
    }

    @Test
    fun `der Wake-Lock wird symmetrisch zu onStart und onStop gehalten`() {
        // DER BEFUND: releaseWakeLock() stand in onStop, acquireWakeLock() nur in onCreate und
        // onNewIntent. Nach Bildschirm-aus/-an lief das Vollbild ohne jeden Wake-Lock weiter -
        // genau der Zustand, gegen den der Lock ueberhaupt existiert.
        val activity = quelldatei("AlarmFullScreenActivity.kt").readText()

        assertTrue(
            "onStart erwirbt den Wake-Lock nicht - nach Bildschirm-aus/-an haelt ihn niemand mehr",
            koerperVon(activity, "override fun onStart()").contains("acquireWakeLock()")
        )
        assertTrue(
            "onStop gibt den Wake-Lock nicht mehr frei",
            koerperVon(activity, "override fun onStop()").contains("releaseWakeLock()")
        )
        // Der Schutz gegen doppelten Erwerb sitzt in acquireWakeLock selbst - sonst haette jeder
        // Aufrufer ein neues WakeLock-Objekt angelegt und das alte ungefreigegeben verloren.
        assertTrue(
            "acquireWakeLock() gibt einen vorhandenen Lock nicht mehr zuerst frei",
            koerperVon(activity, "private fun acquireWakeLock()")
                .substringBefore("newWakeLock")
                .contains("releaseWakeLock()")
        )
    }

    /**
     * Grober, aber ausreichender Funktionskoerper: ab der Signatur bis zur naechsten Deklaration
     * auf derselben Einrueckung. Reicht fuer die drei kurzen Funktionen oben.
     */
    private fun koerperVon(quelle: String, signatur: String): String {
        val ab = quelle.substringAfter(signatur, "")
        require(ab.isNotEmpty()) { "Signatur nicht gefunden: $signatur" }
        return ab.substringBefore("\n    }")
    }

    private fun quelldatei(relativZumPaket: String): File =
        vorhanden("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket")

    private fun ressource(relativZuRes: String): File = vorhanden("src/main/res/$relativZuRes")

    /** Findet eine Datei unabhaengig davon, ob Gradle im Modul- oder Repo-Ordner startet. */
    private fun vorhanden(pfad: String): File =
        listOf(File(pfad), File("app/$pfad")).firstOrNull { it.exists() }
            ?: error("Datei nicht gefunden: $pfad (Arbeitsverzeichnis ${File(".").absolutePath})")
}
