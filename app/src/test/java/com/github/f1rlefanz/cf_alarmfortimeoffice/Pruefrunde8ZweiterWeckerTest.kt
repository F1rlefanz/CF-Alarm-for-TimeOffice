package com.github.f1rlefanz.cf_alarmfortimeoffice

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zwei Regressionen, die die adversariale Review ueber den Fixes der Pruefrunde 8 gefunden hat -
 * beide entstanden erst DURCH diese Fixes.
 *
 * REGRESSION A (Weck-Bildschirm): Bis zur Pruefrunde 8 endete `snoozeAlarm()` immer in `finish()`;
 * die Activity ueberlebte einen Schlummerversuch nicht, und ihr Instanzzustand war damit
 * automatisch einmalig. Seit dem Fix bleibt sie im Fehlerfall bewusst offen - mit gesetztem
 * `schlummerHinweis` und bereits beanspruchter Einweg-Sperre. `onNewIntent()` zog aber nur
 * Schichtname, Schichtbeginn und Schlummer-Dauer nach. Ein ZWEITER Wecker, per singleTask an
 * dieselbe Instanz zugestellt, zeigte deshalb den Fehlertext des alten ("Kein Schlummer-Wecker
 * gestellt"), blendete den Schlummer-Knopf aus (dessen Anzeige haengt an `schlummerHinweis`) und
 * prallte in `snoozeAlarm()` an der verbrauchten Sperre ab: fuer diesen Wecker gab es kein
 * Schlummern mehr.
 *
 * NACHTRAG (Nachreview ueber genau diesem Fix): der erste Anlauf setzte den Zustand bei JEDER
 * Zustellung zurueck und drehte den Fehler damit um - eine Wiederzustellung DESSELBEN Weckers
 * (Tipp auf die Wecker-Benachrichtigung, die den Vollbild-PendingIntent auch als contentIntent
 * traegt) loeschte den Hinweis auf den gescheiterten Schlummer. Seitdem entscheidet der Vergleich
 * der Alarm-Kennung; die widerlegte Fassung steht samt Begruendung im KDoc von
 * `erarbeiteter Zustand wird nur bei einem anderen Weckvorgang verworfen`.
 *
 * REGRESSION B (Merker): `rememberPendingSnooze()` fing seinen Schreibfehler selbst ab und meldete
 * ihn nicht weiter - der Schlummer galt dann als GEPLANT, obwohl seine einzige Spur fehlte.
 *
 * REGRESSION C (Rueckbau im falschen Anlass): Der aus Regression B folgende Rueckbau war fest
 * `cancelSnooze()` - gebaut fuer das frische Schlummern, wo der Merker-Eintrag gerade nicht
 * entstanden ist. `restorePendingSnoozes()` ruft dieselbe Funktion aber fuer Eintraege, die schon
 * IM Merker stehen; dort loeschte der Rueckbau den vorhandenen Eintrag mit und machte aus einem
 * Schreibfehler einen endgueltig verlorenen Weckruf.
 *
 * WARUM QUELLTEXT STATT VERHALTEN: Beides haengt am Activity-Lebenszyklus bzw. an SharedPreferences
 * im CE-Storage, die im JVM-Unit-Test nicht existieren. Dieselbe Bauart wie
 * [AlarmFullScreenVertragTest] - die ENTSCHEIDUNGEN daneben sind in [Pruefrunde8SchlummerTest]
 * echt ausgefuehrt.
 */
class Pruefrunde8ZweiterWeckerTest {

    @Test
    fun `onNewIntent stellt den vollstaendigen alarmbezogenen Zustand her`() {
        val koerper = koerperVon(activity(), "override fun onNewIntent(intent: Intent)")

        assertTrue(
            "onNewIntent uebernimmt den neuen Intent nicht - Snooze und Dismiss handelten dann " +
                "weiter am vorherigen Wecker",
            koerper.contains("setIntent(intent)")
        )
        assertTrue(
            "onNewIntent uebernimmt den alarmbezogenen Zustand nicht aus einer Hand - jedes Feld, " +
                "das dabei vergessen wird, schleppt die singleTask-Instanz in den naechsten Wecker",
            koerper.contains("uebernimmAlarmAusIntent()")
        )
    }

    @Test
    fun `die Anzeigewerte kommen bei JEDER Zustellung frisch aus dem Intent`() {
        val koerper = koerperVon(activity(), "private fun uebernimmAlarmAusIntent()")

        listOf(
            "shiftName = intent.getStringExtra" to
                "Der Schichtname bleibt sonst der des vorherigen Weckers",
            "shiftStartTime = intent.getStringExtra" to
                "Der Schichtbeginn bleibt sonst der des vorherigen Weckers",
            "snoozeMinutes = intent.getIntExtra" to
                "Die Schlummer-Dauer bleibt sonst die des vorherigen Weckers"
        ).forEach { (fragment, warum) ->
            assertTrue(warum, koerper.contains(fragment))
            assertTrue(
                "$warum - und sie darf nicht an der Weckvorgangs-Pruefung haengen: der Intent ist " +
                    "fuer diese Werte immer die Wahrheit, ein erneutes Lesen desselben Intents " +
                    "schadet nie",
                koerper.indexOf(fragment) < koerper.indexOf("if (andererWeckvorgang)")
            )
        }
    }

    /**
     * ALTE BEGRUENDUNG (Fix der zweiten Welle, hier bewusst stehen gelassen): dieser Test forderte
     * `schlummerHinweis = null` und `alarmHandoff = OneShotAlarmHandoff()` BEDINGUNGSLOS im Rumpf
     * von `uebernimmAlarmAusIntent()` - "ein anderer Wecker ist ein anderer Vorgang".
     *
     * WIDERLEGT im Nachreview ueber derselben Welle: nicht jede Zustellung ist ein anderer Wecker.
     * Der [com.github.f1rlefanz.cf_alarmfortimeoffice.service.AlarmSoundService] haengt denselben
     * Vollbild-PendingIntent zusaetzlich als `setContentIntent()` an die laufende
     * Wecker-Benachrichtigung. Nach einem gescheiterten Schlummern bleibt genau diese
     * Benachrichtigung stehen - ein Tipp darauf liefert DENSELBEN Wecker erneut an die
     * singleTask-Instanz. Bedingungslos zurueckgesetzt verschwand damit der Hinweis "Es ist KEIN
     * weiterer Weckruf geplant", der Schlummer-Knopf kam zurueck (seine Anzeige haengt an
     * `schlummerHinweis == null`) und die Sperre war frei: der Bildschirm behauptete wieder, alles
     * sei in Ordnung, und der Nutzer legt sich ohne gestellten Wecker hin.
     *
     * Deshalb fordert der Test jetzt das Gegenteil: erarbeiteter Zustand wird NUR bei einem
     * nachgewiesen ANDEREN Weckvorgang verworfen.
     */
    @Test
    fun `erarbeiteter Zustand wird nur bei einem anderen Weckvorgang verworfen`() {
        val koerper = koerperVon(activity(), "private fun uebernimmAlarmAusIntent()")

        assertTrue(
            "Die Kennung des Weckvorgangs wird gar nicht gelesen - dann gibt es kein " +
                "Unterscheidungsmerkmal zwischen neuem Wecker und Wiederzustellung desselben",
            koerper.contains("EXTRA_ALARM_ID")
        )
        assertTrue(
            "Es gibt keine Fallunterscheidung mehr - siehe Widerlegung im KDoc dieses Tests",
            koerper.contains("if (andererWeckvorgang)")
        )

        listOf(
            "schlummerHinweis = null" to
                "Der Hinweis 'kein weiterer Weckruf geplant' verschwindet dann bei einem Tipp " +
                    "auf die eigene Wecker-Benachrichtigung",
            "alarmHandoff = OneShotAlarmHandoff()" to
                "Die Einweg-Sperre desselben Weckvorgangs wird dann durch einen Tipp auf die " +
                    "Benachrichtigung wieder freigegeben"
        ).forEach { (fragment, warum) ->
            assertTrue(
                "$fragment fehlt - ein WIRKLICH anderer Wecker erbt dann den Zustand des alten",
                koerper.contains(fragment)
            )
            assertTrue(
                warum,
                koerper.indexOf("if (andererWeckvorgang)") < koerper.indexOf(fragment)
            )
        }
    }

    @Test
    fun `bei unbekannter Kennung gilt derselbe Weckvorgang`() {
        // Die Richtung des Zweifels ist die eigentliche Entscheidung: nur der Irrtum "derselbe
        // Wecker faelschlich fuer einen neuen gehalten" loescht eine Warnung und kostet einen
        // Wecker. Der umgekehrte Irrtum laesst eine Warnung ueber einem laut klingelnden Wecker
        // stehen - unschoen, aber niemand verschlaeft dadurch.
        assertFalse(
            "Fehlende Kennung im neuen Intent gilt als neuer Wecker - der Hinweis auf den " +
                "gescheiterten Schlummer wuerde geloescht",
            Weckvorgang.istAnderer(bisher = 42, neu = Weckvorgang.ID_UNBEKANNT)
        )
        assertFalse(
            "Ohne bekannte Vorgeschichte laesst sich nichts vergleichen - dann nicht raten",
            Weckvorgang.istAnderer(bisher = Weckvorgang.ID_UNBEKANNT, neu = 42)
        )
        assertFalse(
            "Beide unbekannt ist kein Nachweis eines anderen Weckers",
            Weckvorgang.istAnderer(bisher = Weckvorgang.ID_UNBEKANNT, neu = Weckvorgang.ID_UNBEKANNT)
        )
    }

    @Test
    fun `dieselbe Kennung ist derselbe Weckvorgang, eine andere ist ein anderer`() {
        assertFalse(
            "Die Wiederzustellung desselben Weckers (Tipp auf die Wecker-Benachrichtigung) darf " +
                "den erarbeiteten Zustand nicht verwerfen",
            Weckvorgang.istAnderer(bisher = 42, neu = 42)
        )
        assertTrue(
            "Ein anderer Wecker MUSS den Zustand des alten verwerfen - sonst erbt er dessen " +
                "Fehlertext und dessen verbrauchte Sperre",
            Weckvorgang.istAnderer(bisher = 42, neu = 43)
        )
    }

    @Test
    fun `die Wecker-Benachrichtigung fuehrt wirklich zurueck in diese Activity`() {
        // Die Praemisse der Fallunterscheidung, in Code nachgeschlagen statt behauptet: derselbe
        // PendingIntent haengt an setFullScreenIntent UND an setContentIntent. Faellt das
        // setContentIntent je weg, ist die Begruendung im KDoc von onNewIntent zu KORRIGIEREN -
        // die Fallunterscheidung selbst bleibt trotzdem richtig (Snooze-Refire an eine noch
        // lebende Instanz liefert ebenfalls denselben Wecker erneut).
        val dienst = quelldatei("service/AlarmSoundService.kt").readText()
        assertTrue(
            "setContentIntent(fullScreenIntent) ist weg - dann stimmt die Begruendung im KDoc " +
                "von AlarmFullScreenActivity.onNewIntent nicht mehr",
            dienst.contains("setContentIntent(fullScreenIntent)")
        )
        assertTrue(dienst.contains("setFullScreenIntent(fullScreenIntent"))
    }

    @Test
    fun `die Einweg-Sperre gehoert dem Wecker, nicht der Activity-Instanz`() {
        // Ein `val` laesst sich nicht erneuern - dann ist der Fix von oben nicht baubar, und der
        // naechste Wecker erbt die verbrauchte Sperre.
        assertFalse(
            "alarmHandoff ist wieder ein val - eine einmal beanspruchte Sperre gilt dann fuer " +
                "alle weiteren Wecker derselben singleTask-Instanz",
            activity().contains("private val alarmHandoff = OneShotAlarmHandoff()")
        )
        assertTrue(activity().contains("private var alarmHandoff = OneShotAlarmHandoff()"))
    }

    @Test
    fun `ein gescheiterter Merker wird nicht verschluckt`() {
        // Er ist die einzige Spur des schwebenden Snooze. Wer den Fehler begraebt, meldet GEPLANT
        // fuer einen Wecker, den die App danach weder abbrechen noch nach einem Neustart
        // wiederherstellen kann - und der Rueckbau in SchlummerEntscheidung.armiere() liefe nie an.
        val koerper = koerperVon(
            quelldatei("service/AlarmManagerService.kt").readText(),
            "private fun rememberPendingSnooze(",
            // Die Funktion liegt im companion object, ihr Rumpf schliesst also eine Ebene tiefer.
            endeMarke = "\n        }"
        )
        assertTrue(
            "rememberPendingSnooze schluckt seinen Schreibfehler wieder - dann ist jeder " +
                "Rueckbau-Pfad toter Code und der Fehlschlag geht als Erfolg durch",
            koerper.contains("throw e")
        )
    }

    @Test
    fun `der Wiederherstellungslauf reicht einen Rueckbau herein, der nichts abbricht`() {
        // REGRESSION C (Nachreview ueber der zweiten Fixwelle): Der Rueckbau war fest
        // `cancelSnooze(context, alarmId)` - richtig fuer `scheduleSnooze()`, falsch fuer
        // `restorePendingSnoozes()`. Dort steht der Merker-Eintrag BEREITS, und `cancelSnooze()`
        // loescht ihn ueber `forgetPendingSnooze()` mit. Ein schwebender Schlummer, der nach einem
        // Neustart wiederhergestellt werden sollte, waere damit endgueltig weg: Alarm gecancelt,
        // einzige Spur geloescht, auch nach jedem weiteren Neustart nicht mehr auffindbar.
        //
        // WARUM QUELLTEXT: Welche Lambda WELCHER Aufrufer hereinreicht, sieht der reine
        // Entscheidungstest nicht - er bekommt sie ja gestellt. Genau die Zuordnung war der Fehler.
        val ruf = aufrufAb(
            quelldatei("service/AlarmManagerService.kt").readText(),
            "logContext = \"Schwebender Snooze nach Neustart wiederhergestellt\""
        )
        assertTrue(
            "Der Wiederherstellungslauf reicht keinen BEWUSST_BEHALTEN-Rueckbau herein - der " +
                "bestehende Merkereintrag ist damit in Gefahr",
            ruf.contains("RueckbauErgebnis.BEWUSST_BEHALTEN")
        )
        assertFalse(
            "Ein Cancel im Wiederherstellungslauf nimmt dem Schlummer den Alarm UND ueber " +
                "forgetPendingSnooze() seine einzige Spur - im Zweifel bleibt er lieber armiert " +
                "und klingelt",
            ruf.contains("cancelSnooze(")
        )
    }

    @Test
    fun `das frische Schlummern raeumt den bereits armierten Alarm dagegen ab`() {
        // Die Gegenprobe: hier ist der Merker-Eintrag gerade NICHT entstanden. Ein Alarm ohne
        // Merker ist durch nichts mehr abzuraeumen - weder Master-Pause noch deleteAllAlarms noch
        // das Abmelden finden ihn.
        val ruf = aufrufAb(
            quelldatei("service/AlarmManagerService.kt").readText(),
            "logContext = \"Snooze-Alarm gesetzt:"
        )
        assertTrue(
            "Ohne Cancel bleibt ein unabbrechbarer Wecker stehen, waehrend der Nutzertext " +
                "'Es ist KEIN weiterer Weckruf geplant' sagt",
            ruf.contains("cancelSnooze(context, alarmId)")
        )
    }

    /**
     * Der Rest des `armSnooze(...)`-Aufrufs ab einer eindeutigen Zeile bis zu seiner schliessenden
     * Klammer - die erste Zeile, die nur aus Einrueckung und `)` besteht. Bewusst
     * einrueckungsunabhaengig: die beiden Aufrufe liegen verschieden tief.
     */
    private fun aufrufAb(quelle: String, marke: String): String {
        val ab = quelle.substringAfter(marke, "")
        require(ab.isNotEmpty()) { "Marke nicht gefunden: $marke" }
        val ende = Regex("\\n\\s*\\)").find(ab)
            ?: error("Ende des Aufrufs nicht gefunden nach: $marke")
        return ab.substring(0, ende.range.first)
    }

    private fun activity(): String = quelldatei("AlarmFullScreenActivity.kt").readText()

    /** Ab der Signatur bis zur naechsten Deklaration auf derselben Einrueckung. */
    private fun koerperVon(
        quelle: String,
        signatur: String,
        endeMarke: String = "\n    }"
    ): String {
        val ab = quelle.substringAfter(signatur, "")
        require(ab.isNotEmpty()) { "Signatur nicht gefunden: $signatur" }
        return ab.substringBefore(endeMarke)
    }

    private fun quelldatei(relativZumPaket: String): File =
        listOf(
            File("src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket"),
            File("app/src/main/java/com/github/f1rlefanz/cf_alarmfortimeoffice/$relativZumPaket")
        ).firstOrNull { it.exists() }
            ?: error("Datei nicht gefunden: $relativZumPaket (Arbeitsverzeichnis ${File(".").absolutePath})")
}
