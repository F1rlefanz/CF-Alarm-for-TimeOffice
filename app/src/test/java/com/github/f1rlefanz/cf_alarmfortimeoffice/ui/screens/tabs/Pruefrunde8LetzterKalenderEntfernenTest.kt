package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.calendar.PendingDeselectionCleanupStore
import com.github.f1rlefanz.cf_alarmfortimeoffice.backup.ConfigBackupFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRUEFRUNDE 8, WELLE 5 - BEFUND B: "Nicht abrufbare Kalender entfernen" konnte die Auswahl
 * LEEREN, ohne das anzukuendigen.
 *
 * DER ABLAUF: Der Knopf entfernt jeden nicht abrufbaren Kalender aus der Auswahl. Bleibt danach
 * keiner uebrig, ist das keine Bereinigung mehr, sondern eine Abwahl - und die raeumt seit
 * v1.29.3 alle Wecker der naechsten zwei Wochen samt der Dienstzeit-Fenster fuer Dimmer und
 * "Nicht stoeren". Weder die Beschriftung noch die Karte sagten davon etwas, und ausgeloest wird
 * der Zustand haeufig durch eine voruebergehende Server- oder Freigabestoerung - also durch etwas,
 * das von allein vergeht.
 *
 * Deshalb: in genau diesem Fall wird vorher gefragt, der Text nennt die Folge, und der zuerst
 * angebotene Weg ist der harmlose.
 */
class Pruefrunde8LetzterKalenderEntfernenTest {

    @Test
    fun `entfernt der Knopf den letzten Kalender, wird das erkannt`() {
        assertTrue(
            entfernenWuerdeAuswahlLeeren(
                ausgewaehlt = setOf("cal-a"),
                nichtAbrufbar = setOf("cal-a")
            )
        )
    }

    /** Der Regelfall des Knopfes: einer von mehreren faellt weg - da bleibt der Dienstplan gedeckt. */
    @Test
    fun `bleibt ein Kalender uebrig, wird nicht gefragt`() {
        assertFalse(
            entfernenWuerdeAuswahlLeeren(
                ausgewaehlt = setOf("cal-a", "cal-b"),
                nichtAbrufbar = setOf("cal-b")
            )
        )
    }

    /**
     * Die Liste der nicht abrufbaren Kalender stammt aus dem LETZTEN Ladevorgang, die Auswahl kann
     * sich seither geaendert haben. Ein Eintrag, der gar nicht mehr ausgewaehlt ist, darf die
     * Rueckfrage weder ausloesen noch verhindern.
     */
    @Test
    fun `veraltete Eintraege ausserhalb der Auswahl aendern nichts`() {
        assertTrue(
            "cal-x ist gar nicht ausgewaehlt - cal-a ist trotzdem der letzte",
            entfernenWuerdeAuswahlLeeren(
                ausgewaehlt = setOf("cal-a"),
                nichtAbrufbar = setOf("cal-a", "cal-x")
            )
        )
        assertFalse(
            "Ohne betroffenen Kalender gibt es nichts zu entfernen",
            entfernenWuerdeAuswahlLeeren(
                ausgewaehlt = setOf("cal-a"),
                nichtAbrufbar = setOf("cal-x")
            )
        )
    }

    @Test
    fun `ohne Auswahl gibt es nichts zu leeren`() {
        assertFalse(entfernenWuerdeAuswahlLeeren(ausgewaehlt = emptySet(), nichtAbrufbar = setOf("cal-a")))
    }

    /**
     * Der Text IST die Zusicherung: Ohne die Folge klingt der Knopf nach Aufraeumen, dabei kostet
     * er alle Wecker. Und ohne den Hinweis auf die haeufigste Ursache (voruebergehende Stoerung)
     * fehlt dem Nutzer das, was die Entscheidung ueberhaupt erst zu einer macht.
     */
    @Test
    fun `die Rueckfrage nennt Folge, Ausnahme und den harmlosen Weg`() {
        val text = ENTFERNEN_LEERT_AUSWAHL_TEXT
        assertTrue("Die Folge fuer die Wecker fehlt", text.contains("Wecker der nächsten zwei Wochen"))
        assertTrue("Dimmer/DND fehlen", text.contains("Dimmer") && text.contains("Nicht stören"))
        assertTrue("Manuelle Wecker fehlen", text.contains("Selbst gestellte Wecker bleiben"))
        assertTrue("Die haeufigste Ursache fehlt", text.contains("vorübergehende"))
        assertTrue("Der Rat abzuwarten fehlt", text.contains("Abwarten"))
        listOf("Sync", "DataStore", "Repository", "ID", "null").forEach {
            assertFalse("Systemname im Nutzertext: $it", text.contains(it))
        }
    }

    /**
     * Der Titel muss die Lage benennen, nicht die Aktion - der Nutzer hat gerade "entfernen"
     * getippt und muss erfahren, was das hier bedeutet.
     */
    @Test
    fun `der Titel benennt den Zustand danach`() {
        assertTrue(ENTFERNEN_LEERT_AUSWAHL_TITEL.contains("kein Kalender ausgewählt"))
    }

    /**
     * Die Knopfbeschriftungen: der harmlose Weg zuerst, das Entfernen ausdruecklich als
     * "trotzdem" - ein Dialog, dessen Knoepfe beide gleich klingen, ist keine Rueckfrage.
     */
    @Test
    fun `die Knoepfe unterscheiden den harmlosen vom folgenreichen Weg`() {
        assertTrue(ENTFERNEN_LEERT_AUSWAHL_ALTERNATIVE.contains("wählen"))
        assertTrue(ENTFERNEN_LEERT_AUSWAHL_BESTAETIGEN.startsWith("Trotzdem"))
        assertTrue(ENTFERNEN_LEERT_AUSWAHL_ABBRECHEN.isNotBlank())
    }

    /**
     * BEFUND A, Randstueck: Der neue Merker fuer einen offenen Raeumauftrag ist Laufzeitzustand
     * DIESES Geraets. Importiert wuerde er auf einem anderen Geraet einen Raeumlauf anstossen, den
     * dort nie jemand ausgeloest hat.
     *
     * Der Schluessel wird ueber eine Konstante deklariert, den Quelltext-Scan der Inventur
     * (`Pruefrunde6BackupSchluesselInventurTest`) sieht also nur ein Literal-Muster und nicht
     * diesen Fall - deshalb steht die Entscheidung hier ausdruecklich.
     */
    @Test
    fun `der offene Raeumauftrag wird nie exportiert`() {
        assertFalse(
            ConfigBackupFilter.isExportable(PendingDeselectionCleanupStore.KEY_PENDING_SINCE_NAME)
        )
    }
}
