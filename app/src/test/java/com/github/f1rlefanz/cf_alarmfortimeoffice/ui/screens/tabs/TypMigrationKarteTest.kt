package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens.tabs

import com.github.f1rlefanz.cf_alarmfortimeoffice.dimmer.TypModellMigration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Die Karte "Uebernahme gescheitert" im System-Status (#216, #248) nennt den Schritt, der
 * gescheitert ist. Bis #248 zeigte sie fest den Text fuer Dimmen und "Nicht stoeren" - bei einem
 * reinen Licht-Fehler (Dimmen und DND sind dann uebernommen) behauptete sie damit etwas Falsches.
 *
 * Der Wortlaut steht hier bewusst als Literal, nicht als Verweis auf die Konstanten: ein Verweis
 * hielte nichts fest. Die Licht-Fassung ist der verbindliche Wortlaut der Betreuer-Entscheidung
 * in #248; die Fassung fuer Dimmen und DND ist mit 1.59.0 ausgeliefert und bleibt wortgleich.
 */
class TypMigrationKarteTest {

    @Test
    fun `Dimmen und DND - der ausgelieferte Wortlaut bleibt`() {
        val k = typMigrationKartentext(TypModellMigration.Schritt.DIMMEN_UND_DND)

        assertEquals("Dimmen und „Nicht stören“ nicht übernommen", k.titel)
        assertEquals(
            "Deine Einstellungen für Dimmen und „Nicht stören“ konnten noch nicht zu den Schichttypen " +
                "übernommen werden. Sie bleiben unverändert gespeichert. Die App versucht es beim " +
                "nächsten Start und bei der regelmäßigen Hintergrund-Wartung von selbst erneut.",
            k.text
        )
    }

    @Test
    fun `Licht - die Karte nennt die Hue-Regeln`() {
        val k = typMigrationKartentext(TypModellMigration.Schritt.LICHT)

        assertEquals("Hue-Regeln nicht übernommen", k.titel)
        assertEquals(
            "Deine Hue-Regeln konnten noch nicht zu den Schichttypen übernommen werden. Sie bleiben " +
                "unverändert gespeichert. Die App versucht es beim nächsten Start und bei der " +
                "regelmäßigen Hintergrund-Wartung von selbst erneut.",
            k.text
        )
    }

    @Test
    fun `Licht - weder Titel noch Text nennen Dimmen oder Nicht stoeren`() {
        val k = typMigrationKartentext(TypModellMigration.Schritt.LICHT)

        for (wort in listOf("Dimmen", "Nicht stören")) {
            assertFalse("Titel nennt \"$wort\": ${k.titel}", k.titel.contains(wort))
            assertFalse("Text nennt \"$wort\": ${k.text}", k.text.contains(wort))
        }
    }
}
