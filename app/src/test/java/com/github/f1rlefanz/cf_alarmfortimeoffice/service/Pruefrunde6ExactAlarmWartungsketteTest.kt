package com.github.f1rlefanz.cf_alarmfortimeoffice.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Befund 11 (Pruefrunde 6): Entzieht der Nutzer auf API 31/32 die Berechtigung
 * "Alarme & Erinnerungen", loescht Android ALLE exakt gestellten Alarme - auch den EINEN, an dem
 * die 6h-Wartungskette haengt. Danach lief nichts mehr an, bis das Geraet neu startete oder der
 * Nutzer die App oeffnete.
 *
 * Der Fix ist ein INEXAKT gestellter Wiederanlauf-Wachhund hinter dem regulaeren Lauf
 * (`setAndAllowWhileIdle` steht nicht auf der Loeschliste des Entzugs).
 *
 * UND SEINE REGRESSION: In seiner ersten Fassung tat der Wachhund beim Feuern genau eine Sache -
 * `AlarmMaintenanceService.start()`, also `startForegroundService()`. Das ist ausgerechnet in
 * seinem Zielzustand verboten: die Ausnahme von den Android-12-Beschraenkungen gilt nur fuer das
 * Feuern eines EXAKTEN Alarms, und exakt kann der Wachhund gerade nicht sein. Der Start wurde
 * abgewiesen, der Rueckfallpfad stellte einen inexakten Nachholversuch, der ebenfalls abgewiesen
 * wurde und sich selbst nachstellte - eine endlose RTC_WAKEUP-Schleife ohne Abbruchzaehler,
 * waehrend die Kette weiterhin tot war (denn `scheduleNext()` steht im `finally` des Dienstlaufs,
 * der nie zustande kam).
 *
 * Deshalb pruefen die Tests hier drei Dinge, die den Wachhund ueberhaupt erst tragfaehig machen:
 * seine Arithmetik, den Deckel auf dem Nachholversuch, und - am Quelltext, weil AlarmManager und
 * PendingIntent ohne Geraet nicht pruefbar sind - dass er inexakt gestellt wird, dass die
 * Master-Pause ihn mitkappt und dass sein Empfaenger die Kette selbst wieder aufzieht.
 */
class Pruefrunde6ExactAlarmWartungsketteTest {

    // ------------------------------------------------------------------
    // Arithmetik der Kette
    // ------------------------------------------------------------------

    @Test
    fun `der regulaere Lauf bleibt bei sechs Stunden`() {
        val jetzt = 1_700_000_000_000L
        val zeitpunkte = WartungsKettenPlanung.zeitpunkte(jetzt)

        assertEquals(jetzt + TimeUnit.HOURS.toMillis(6), zeitpunkte.regulaer)
    }

    @Test
    fun `der Wiederanlauf-Wachhund liegt strikt HINTER dem regulaeren Lauf`() {
        val zeitpunkte = WartungsKettenPlanung.zeitpunkte(1_700_000_000_000L)

        assertTrue(
            "Wachhund muss nach dem regulaeren Lauf liegen, sonst laufen zwei Zyklen parallel " +
                "(regulaer=${zeitpunkte.regulaer}, wiederanlauf=${zeitpunkte.wiederanlauf})",
            zeitpunkte.wiederanlauf > zeitpunkte.regulaer
        )
    }

    @Test
    fun `die Karenz betraegt genau die dokumentierten 30 Minuten`() {
        val zeitpunkte = WartungsKettenPlanung.zeitpunkte(0L)
        val karenz = zeitpunkte.wiederanlauf - zeitpunkte.regulaer

        // Bewusst auf den Wert festgenagelt statt "mindestens 15 Minuten": ein voller
        // Wartungslauf (Token-Refresh -> Kalender -> Alarme, inklusive seiner Wiederholungen)
        // muss fertig werden und in seinem `finally` neu geplant haben, bevor der Wachhund
        // drankaeme - sonst startet er den Dienst ein zweites Mal. Eine Halbierung der Karenz
        // waere mit einer lockeren Untergrenze unbemerkt durchgegangen.
        assertEquals(
            TimeUnit.MINUTES.toMillis(WartungsKettenPlanung.WACHHUND_KARENZ_MINUTEN),
            karenz
        )
        assertEquals(30L, WartungsKettenPlanung.WACHHUND_KARENZ_MINUTEN)
    }

    @Test
    fun `die Zeitpunkte haengen linear an der Startzeit`() {
        val a = WartungsKettenPlanung.zeitpunkte(0L)
        val b = WartungsKettenPlanung.zeitpunkte(1_000L)

        assertEquals(1_000L, b.regulaer - a.regulaer)
        assertEquals(1_000L, b.wiederanlauf - a.wiederanlauf)
    }

    @Test
    fun `regulaerer Lauf, Nachholversuch und Wachhund liegen auf drei verschiedenen Slots`() {
        val regulaer = WartungsKettenPlanung.REGULAER_REQUEST_CODE.toLong()
        val nachhol = WartungsKettenPlanung.NACHHOL_REQUEST_CODE.toLong()
        val wachhund = WartungsKettenPlanung.WACHHUND_REQUEST_CODE.toLong()

        assertNotEquals(
            "Der Wachhund wuerde den regulaeren Wartungs-Alarm ueberschreiben statt ihn abzusichern",
            regulaer,
            wachhund
        )
        assertNotEquals(nachhol, wachhund)
        assertNotEquals(regulaer, nachhol)
    }

    // ------------------------------------------------------------------
    // Der Deckel auf dem Nachholversuch - die eigentliche Regression
    // ------------------------------------------------------------------

    /**
     * DER KERNTEST GEGEN DIE WECKSCHLEIFE. Ohne Exact-Alarm-Berechtigung traegt der Nachholversuch
     * beim Feuern keine Vordergrunddienst-Startfreigabe: er wuerde abgewiesen und stellte sich
     * selbst erneut - alle 10 Sekunden bzw. im Doze-Takt, endlos, ohne je Erfolg zu haben.
     */
    @Test
    fun `ohne Exact-Alarm-Berechtigung wird gar nicht erst nachgeholt`() {
        assertFalse(
            "Ein inexakt gestellter Nachholversuch wird beim Feuern wieder abgewiesen - das ist " +
                "eine Weckschleife, kein Rueckfallpfad",
            WartungsKettenPlanung.darfNachholen(bisherigeVersuche = 0, kannExakteAlarme = false)
        )
    }

    @Test
    fun `mit Berechtigung wird nachgeholt, aber nur bis zur Obergrenze`() {
        assertTrue(WartungsKettenPlanung.darfNachholen(0, kannExakteAlarme = true))
        assertTrue(
            WartungsKettenPlanung.darfNachholen(
                WartungsKettenPlanung.MAX_NACHHOLVERSUCHE - 1,
                kannExakteAlarme = true
            )
        )
        assertFalse(
            "Ohne Obergrenze haelt sich auch ein aus anderem Grund dauerhaft abgelehnter Start " +
                "selbst am Leben",
            WartungsKettenPlanung.darfNachholen(
                WartungsKettenPlanung.MAX_NACHHOLVERSUCHE,
                kannExakteAlarme = true
            )
        )
    }

    // ------------------------------------------------------------------
    // Die drei Eigenschaften, die nur am Quelltext pruefbar sind
    // ------------------------------------------------------------------

    /**
     * Der Wachhund steht drei Zeilen unter einem `setExactAndAllowWhileIdle` - die Angleichung
     * ist die naheliegende "Aufraeum"-Aenderung, und sie waere toedlich: ein exakt gestellter
     * Wachhund wird vom Berechtigungsentzug mitgeloescht und ist damit genau im Zielfall weg.
     */
    @Test
    fun `der Wachhund wird IMMER inexakt gestellt`() {
        val rumpf = funktionsRumpf("service/AlarmMaintenanceService.kt", "private fun armiereWiederanlauf")

        assertTrue(
            "armiereWiederanlauf() muss setAndAllowWhileIdle benutzen: $rumpf",
            rumpf.contains("setAndAllowWhileIdle(")
        )
        assertFalse(
            "Exakt gestellt wuerde der Wachhund beim Entzug von SCHEDULE_EXACT_ALARM mitgeloescht - " +
                "also genau dann, wenn er gebraucht wird",
            rumpf.contains("setExactAndAllowWhileIdle(")
        )
    }

    /**
     * "Vergisst man ihn, kaeme die pausierte Wartung 30 Minuten spaeter von selbst zurueck" -
     * so steht es im Code, und genau das prueft hier ein Test statt nur ein Kommentar.
     */
    @Test
    fun `die Master-Pause kappt BEIDE Slots`() {
        val rumpf = funktionsRumpf("service/AlarmMaintenanceService.kt", "fun cancelNext(")

        assertTrue(
            "cancelNext() muss den regulaeren Slot kappen: $rumpf",
            rumpf.contains("REGULAER_REQUEST_CODE")
        )
        assertTrue(
            "cancelNext() muss den Wachhund mitkappen - er zieht die Kette sonst aus sich heraus " +
                "wieder auf: $rumpf",
            rumpf.contains("WACHHUND_REQUEST_CODE")
        )
        assertEquals(
            "Erwartet werden genau zwei cancel()-Aufrufe (regulaer + Wachhund)",
            2,
            Regex("alarmManager\\.cancel\\(").findAll(rumpf).count()
        )
    }

    /**
     * DIE REGRESSION SELBST: Der Wachhund muss die Kette im Empfaenger wieder aufziehen, BEVOR er
     * einen Dienststart versucht - `scheduleNext()` ist reine AlarmManager-Arbeit und aus dem
     * Hintergrund immer erlaubt, `startForegroundService()` in seinem Zielzustand nie. Fehlt das,
     * ist der Wachhund-Slot nach einmaligem Feuern verbraucht und die Kette bleibt tot.
     */
    @Test
    fun `der Wachhund-Empfaenger zieht die Kette selbst wieder auf`() {
        val quelle = quelldatei("service/AlarmMaintenanceBroadcastReceiver.kt").readText()

        assertTrue(
            "Der Empfaenger muss den Wachhund-Broadcast erkennen",
            quelle.contains("EXTRA_WACHHUND")
        )
        assertTrue(
            "Der Empfaenger muss beim Wachhund scheduleNext() rufen - sonst haengt das " +
                "Neuaufziehen am Dienstlauf, der in genau diesem Zustand nicht starten darf",
            quelle.contains("AlarmMaintenanceService.scheduleNext(context)")
        )
        assertTrue(
            "Vor dem Neuaufziehen muss die Master-Pause geprueft werden",
            quelle.contains("isPausedNow()")
        )
    }

    // ------------------------------------------------------------------
    // Helfer
    // ------------------------------------------------------------------

    /**
     * Liefert den Rumpf einer Funktion des Companions (Einrueckung 8) - von ihrer Deklaration bis
     * zur ersten schliessenden Klammer auf derselben Ebene.
     */
    private fun funktionsRumpf(datei: String, deklaration: String): String {
        val zeilen = quelldatei(datei).readLines()
        val start = zeilen.indexOfFirst { it.trimStart().startsWith(deklaration) }
        require(start >= 0) { "Deklaration '$deklaration' in $datei nicht gefunden" }
        val ende = zeilen.drop(start + 1).indexOfFirst { it == "        }" }
        require(ende >= 0) { "Ende von '$deklaration' in $datei nicht gefunden" }
        return zeilen.subList(start, start + 1 + ende + 1).joinToString("\n")
    }

    /** Findet eine Produktivquelle unabhaengig davon, ob Gradle im Modul- oder Repo-Ordner startet. */
    private fun quelldatei(relativZumPaket: String): File {
        val paket = "src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket"
        return listOf(File(paket), File("app/$paket")).firstOrNull { it.isFile }
            ?: error("Quelldatei nicht gefunden: $paket (Arbeitsverzeichnis ${File(".").absolutePath})")
    }
}
