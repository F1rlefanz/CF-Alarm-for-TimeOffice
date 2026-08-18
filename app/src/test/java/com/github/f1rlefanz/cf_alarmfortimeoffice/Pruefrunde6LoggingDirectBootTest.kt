package com.github.f1rlefanz.cf_alarmfortimeoffice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prueft die Nachruestung des Datei-Logs nach einem Direct-Boot-Start.
 *
 * DER REGRESSIONSFALL: Startet der Prozess durch `LOCKED_BOOT_COMPLETED` vor der ersten
 * Entsperrung - genau der Ablauf, in dem der `BootReceiver` die Alarme aus dem
 * Direct-Boot-Spiegel wiederherstellt -, liefert `getExternalFilesDir(null)` `null`, weil
 * External Storage noch nicht gemountet ist. Frueher wurde der Timber-Baum ausschliesslich in
 * `onCreate()` gepflanzt; ein Fehlversuch dort blieb fuer die GESAMTE Lebensdauer des Prozesses
 * bestehen. Im Release ist der SimpleFileTree die einzige Senke, und Timber verwirft ohne
 * gepflanzten Baum still ALLES - auch `Logger.e`/`Logger.w`. Da dieser Prozess die Entsperrung
 * ueberlebt und danach die App bedient, fehlte ausgerechnet nach einem Neustart jede Diagnose.
 *
 * Geprueft wird beides: dass ein fehlendes Verzeichnis NICHT als erledigt gilt
 * ([FileLogTreeInstaller]) und dass nach dem Entsperren wirklich erneut angeklopft wird
 * ([handleUnlockOpportunity]).
 */
class Pruefrunde6LoggingDirectBootTest {

    private val logDir = File("nicht-angefasst-nur-durchgereicht")

    // ---------------------------------------------------------------------------------------
    // FileLogTreeInstaller
    // ---------------------------------------------------------------------------------------

    /**
     * DER KERNFALL. Erster Anlass (Direct Boot): kein Verzeichnis, also kein Baum - aber auch
     * kein "erledigt". Zweiter Anlass (nach dem Entsperren): Verzeichnis da, Baum steht.
     *
     * Setzt man den Merker wie frueher schon beim ERSTEN Versuch (also vor dem Aufloesen des
     * Verzeichnisses), faellt dieser Test um: der zweite Anlass pflanzt dann nicht mehr.
     */
    @Test
    fun `fehlendes Log-Verzeichnis gilt nicht als erledigt - nach dem Entsperren wird gepflanzt`() {
        val installer = FileLogTreeInstaller()
        var gepflanzt = 0
        var verzeichnisDa = false

        val ersterVersuch = installer.install(
            resolveLogDir = { if (verzeichnisDa) logDir else null },
            plant = { gepflanzt++ }
        )

        assertFalse("Ohne Verzeichnis kann nicht gepflanzt werden", ersterVersuch)
        assertEquals("Im gesperrten Zustand darf kein Baum entstehen", 0, gepflanzt)
        assertFalse(
            "Ein Fehlversuch darf NICHT als erledigt gelten - sonst bleibt der Prozess stumm",
            installer.isPlanted
        )

        // Der Nutzer entsperrt, External Storage ist gemountet.
        verzeichnisDa = true
        val zweiterVersuch = installer.install(
            resolveLogDir = { if (verzeichnisDa) logDir else null },
            plant = { gepflanzt++ }
        )

        assertTrue("Nach dem Entsperren MUSS nachgeruestet werden", zweiterVersuch)
        assertEquals("Genau ein Baum", 1, gepflanzt)
        assertTrue(installer.isPlanted)
    }

    /** Mehrere Entsperr-Anlaesse (Receiver + zwei Activity-Callbacks) duerfen nicht stapeln. */
    @Test
    fun `ein stehender Baum wird bei weiteren Anlaessen nicht erneut gepflanzt`() {
        val installer = FileLogTreeInstaller()
        var gepflanzt = 0

        repeat(4) {
            installer.install(resolveLogDir = { logDir }, plant = { gepflanzt++ })
        }

        assertEquals("Der Datei-Baum darf je Prozess nur einmal entstehen", 1, gepflanzt)
    }

    /**
     * Wirft das Pflanzen selbst (z. B. eine SecurityException aus `mkdirs()`), ist das ebenfalls
     * kein erledigtes Pflanzen - sonst bliebe der Prozess dauerhaft ohne Senke, obwohl der
     * naechste Anlass Erfolg haette.
     */
    @Test
    fun `wirft das Pflanzen, bleibt der Merker offen und der naechste Anlass gelingt`() {
        val installer = FileLogTreeInstaller()
        var versuche = 0
        var geworfen: IllegalStateException? = null

        try {
            installer.install(
                resolveLogDir = { logDir },
                plant = { versuche++; throw IllegalStateException("mkdirs abgelehnt") }
            )
        } catch (e: IllegalStateException) {
            geworfen = e
        }

        assertTrue("Der Fehlschlag darf nicht verschluckt werden", geworfen != null)
        assertFalse("Ein gescheitertes Pflanzen ist kein erledigtes Pflanzen", installer.isPlanted)

        val zweiterVersuch = installer.install(resolveLogDir = { logDir }, plant = { versuche++ })

        assertTrue("Der naechste Anlass muss es erneut versuchen duerfen", zweiterVersuch)
        assertEquals(2, versuche)
        assertTrue(installer.isPlanted)
    }

    // ---------------------------------------------------------------------------------------
    // handleUnlockOpportunity - die Verdrahtung
    // ---------------------------------------------------------------------------------------

    /**
     * DER ZWEITE KERNFALL: Der geraetelokale Startblock ist an ein Gate gebunden und nach einem
     * Lauf fuer immer erledigt; das Datei-Log ist es NICHT - es haengt am Verzeichnis. Stuende
     * das Nachruesten hinter dem Gate-Check, fiele es in genau der Lage aus, in der ein anderer
     * Anlass den Startblock schon beansprucht hat.
     */
    @Test
    fun `auch bei bereits gelaufenem Startblock wird das Datei-Log nachgeruestet`() {
        var logNachgeruestet = 0
        var abgemeldet = 0
        var laeufe = 0

        handleUnlockOpportunity(
            isUserUnlocked = { true },
            ensureFileLog = { logNachgeruestet++ },
            startupAlreadyRan = { true },
            disarmWatchers = { abgemeldet++ },
            runChecks = { laeufe++ }
        )

        assertEquals(
            "Das Datei-Log haengt am Verzeichnis, nicht am Startblock-Gate",
            1, logNachgeruestet
        )
        assertEquals("Die Netze werden abgemeldet", 1, abgemeldet)
        assertEquals("Der Startblock darf kein zweites Mal laufen", 0, laeufe)
    }

    /** Der Normalfall des Direct-Boot-Prozesses: erstes Entsperren, beides wird nachgeholt. */
    @Test
    fun `beim ersten Entsperren laufen Nachruesten und Startblock`() {
        var logNachgeruestet = 0
        var abgemeldet = 0
        var laeufe = 0

        handleUnlockOpportunity(
            isUserUnlocked = { true },
            ensureFileLog = { logNachgeruestet++ },
            startupAlreadyRan = { false },
            disarmWatchers = { abgemeldet++ },
            runChecks = { laeufe++ }
        )

        assertEquals("Das Datei-Log muss nachgeruestet werden", 1, logNachgeruestet)
        assertEquals("Der geraetelokale Startblock muss laufen", 1, laeufe)
        assertEquals(
            "Abgemeldet wird erst im Startblock selbst - siehe runDeviceLocalStartupChecks()",
            0, abgemeldet
        )
    }

    /**
     * DIE REGRESSION DES ERSTEN FIXES: `ensureFileLog` wurde SYNCHRON verdrahtet. Beide Anlaesse
     * von `onUnlockOpportunity` liegen auf dem Hauptthread - der `ACTION_USER_UNLOCKED`-Empfaenger
     * (10-Sekunden-Budget von `onReceive`) und die Activity-Lifecycle-Rueckrufe -, waehrend
     * `plantFileLogTree` echtes Platten-I/O macht: `getExternalFilesDir(null)` prueft den
     * Mount-Zustand und legt Verzeichnisse an, und der `SimpleFileTree`-Konstruktor raeumt in
     * seinem `init` alte Logdateien weg (`listFiles()` + `delete()`). Und das ausgerechnet im
     * Moment mit der hoechsten I/O-Last des Geraets: der ersten Entsperrung nach einem Neustart.
     *
     * Geprueft an der Quelle, weil die Verdrahtung in `Application` selbst nicht instanziierbar
     * ist. Faellt der Test, steht der Aufruf wieder synchron auf dem Hauptthread.
     */
    @Test
    fun `das Nachruesten des Datei-Logs laeuft nicht auf dem Hauptthread`() {
        val quelle = quelldatei("CFAlarmApplication.kt").readText()
        val bindung = quelle.lines().firstOrNull { it.contains("ensureFileLog =") }
            ?: error("Verdrahtung 'ensureFileLog =' in CFAlarmApplication.kt nicht gefunden")

        assertTrue(
            "Der Aufruf muss in den applicationScope (Dispatchers.IO) ausgelagert bleiben - " +
                "gefunden: $bindung",
            bindung.contains("applicationScope.launch")
        )
    }

    /** Findet eine Produktivquelle unabhaengig davon, ob Gradle im Modul- oder Repo-Ordner startet. */
    private fun quelldatei(relativZumPaket: String): File {
        val paket = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket"
        return listOf(File(paket), File("app/$paket")).firstOrNull { it.exists() }
            ?: error("Quelldatei nicht gefunden: $paket (Arbeitsverzeichnis ${File(".").absolutePath})")
    }

    /**
     * Meldet der `UserManager` weiterhin "gesperrt", passiert nichts: External Storage ist dann
     * ohnehin nicht da, und ein Lauf des Startblocks wuerde den `settings`-Store still leer lesen
     * und den DataStore-Cache fuer die restliche Prozesslaufzeit vergiften.
     */
    @Test
    fun `im gesperrten Zustand wird weder gepflanzt noch der Startblock gestartet`() {
        var logNachgeruestet = 0
        var laeufe = 0

        handleUnlockOpportunity(
            isUserUnlocked = { false },
            ensureFileLog = { logNachgeruestet++ },
            startupAlreadyRan = { false },
            disarmWatchers = { },
            runChecks = { laeufe++ }
        )

        assertEquals("Vor der Entsperrung gibt es kein Log-Verzeichnis", 0, logNachgeruestet)
        assertEquals("Im gesperrten Zustand darf der CE-Block NICHT laufen", 0, laeufe)
    }
}
