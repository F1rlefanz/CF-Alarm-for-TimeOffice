package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.screens

import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftConfig
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftDefinition
import com.github.f1rlefanz.cf_alarmfortimeoffice.model.ShiftKategorie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min

/**
 * Haelt die zwei Zusicherungen des Schicht-Konfigurationsscreens fest, die man nur am Text bzw. an
 * einer einzigen Zeile im Bestaetigungsdialog sieht - und die beide schon einmal falsch waren:
 *
 *  1. "Auf Standardwerte zuruecksetzen" ersetzt die Definitionen, ruehrt aber den Automatik-Schalter
 *     nicht an. Vorher wurde die komplette [ShiftConfig.getDefaultConfig] gespeichert; die enthaelt
 *     `autoAlarmEnabled = true` und hob damit eine bewusst eingeschaltete Alarm-Pause auf, ohne dass
 *     der Dialog das erwaehnte.
 *  2. Der Hinweistext ueber der Liste beschreibt die Erkennung so, wie sie wirklich funktioniert
 *     (Muster ODER Schichtname), und nennt nur Beispiele, die in der Standardkonfiguration
 *     tatsaechlich vorkommen.
 */
class ShiftConfigScreenTextTest {

    private fun eigeneConfig(autoAlarmEnabled: Boolean) = ShiftConfig(
        autoAlarmEnabled = autoAlarmEnabled,
        definitions = listOf(
            ShiftDefinition(
                id = "eigene",
                name = "Bereitschaft",
                keywords = listOf("BD1"),
                alarmTime = LocalTime.of(21, 45),
                isEnabled = true,
                isSilent = true
            )
        )
    )

    @Test
    fun `Zuruecksetzen behaelt eine ausgeschaltete Alarm-Automatik`() {
        val result = resetToDefaultsPreservingAutoAlarm(eigeneConfig(autoAlarmEnabled = false))

        assertFalse(
            "Die bewusst ausgeschaltete Automatik darf das Zuruecksetzen der Definitionen nicht " +
                "wieder einschalten - sonst legt updateShiftConfig() sofort wieder Wecker an",
            result.autoAlarmEnabled
        )
        assertEquals(
            "Die Definitionen muessen exakt die Standardwerte sein",
            ShiftConfig.getDefaultConfig().definitions,
            result.definitions
        )
    }

    @Test
    fun `Zuruecksetzen behaelt eine eingeschaltete Alarm-Automatik`() {
        val result = resetToDefaultsPreservingAutoAlarm(eigeneConfig(autoAlarmEnabled = true))

        assertTrue(result.autoAlarmEnabled)
        assertEquals(ShiftConfig.getDefaultConfig().definitions, result.definitions)
    }

    @Test
    fun `ohne geladene Konfiguration gelten die vollen Standardwerte`() {
        // Kein Nutzerwunsch vorhanden, der erhalten werden koennte.
        assertEquals(ShiftConfig.getDefaultConfig(), resetToDefaultsPreservingAutoAlarm(null))
    }

    @Test
    fun `jedes im Hinweistext genannte Muster kommt in der Standardkonfiguration vor`() {
        val defaults = ShiftConfig.getDefaultConfig()
        val vorhandeneMuster = defaults.definitions
            .flatMap { it.keywords + it.name }
            .map { it.trim().lowercase() }
            .toSet()

        val genannt = SHIFT_HINT_GENERIC_EXAMPLES +
            SHIFT_HINT_SHORT_CODE_EXAMPLES

        genannt.forEach { beispiel ->
            assertTrue(
                "Der Hinweistext nennt \"$beispiel\" als Vorgabe, die Standardkonfiguration " +
                    "enthaelt es aber nicht (mehr) - Text und Verhalten sind auseinandergelaufen",
                vorhandeneMuster.contains(beispiel.lowercase())
            )
            assertTrue(
                "Beispiel \"$beispiel\" ist deklariert, steht aber nicht im Text",
                SHIFT_RECOGNITION_HINT.contains(beispiel)
            )
        }
    }

    @Test
    fun `Hinweistext schliesst den Schichtnamen nicht mehr als Erkennungsweg aus`() {
        assertFalse(
            "Der Text behauptete, es zaehle nicht der Schichtname - matchesKeywords() zaehlt ihn " +
                "ab zwei Zeichen aber ausdruecklich mit: $SHIFT_RECOGNITION_HINT",
            SHIFT_RECOGNITION_HINT.contains("nicht über den Schichtnamen")
        )
        assertTrue(
            "Der Text muss den Schichtnamen als zweiten Erkennungsweg nennen",
            SHIFT_RECOGNITION_HINT.contains("Schichtnamen")
        )
    }

    @Test
    fun `Hinweistext verspricht die Muster nicht in der Listenzeile, sondern beim Antippen`() {
        // Seit der gruppierten Liste (#170) zeigt jede Zeile nur Code, Name und Kurzform - die Muster
        // stehen im Bearbeiten-Dialog. Der Text sagte "steht in ihrer Karte in der Liste darunter".
        assertFalse(
            "Die Zeile zeigt die Muster nicht mehr: $SHIFT_RECOGNITION_HINT",
            SHIFT_RECOGNITION_HINT.contains("in ihrer Karte")
        )
        assertTrue(
            "Der Text muss sagen, wo man die Muster eines Typs sieht",
            SHIFT_RECOGNITION_HINT.contains("antippst")
        )
    }

    @Test
    fun `die Zusicherung des Hinweistexts gilt im Code - der Name erkennt einen Termin allein`() {
        // Belegt, dass der Text nicht wieder zu viel verspricht: der Name trifft ab zwei Zeichen
        // als eigenes Wort, obwohl er in keinem keyword steht.
        val zwischendienst = ShiftConfig.getDefaultConfig().definitions
            .first { it.name == "Zwischendienst" }

        assertFalse(
            "Testvoraussetzung: der Name darf nicht zusaetzlich als Muster hinterlegt sein",
            zwischendienst.keywords.any { it.equals("Zwischendienst", ignoreCase = true) }
        )
        assertTrue(zwischendienst.matchesKeywords("Zwischendienst"))
    }

    @Test
    fun `jede Code-Marke der Liste erreicht AA in hell und dunkel`() {
        // Die Marken tragen kleinen Text auf einer GEFUELLTEN Flaeche (wie die Etiketten in
        // ThemeKontrastTest): WCAG AA verlangt 4,5:1. Das Dunkel-Paar sieht man im Screenshot nicht
        // verlaesslich - dieser Test ist die Absicherung, auch fuer eine spaeter ergaenzte Kategorie.
        fun kontrast(a: Color, b: Color): Double {
            val la = a.luminance().toDouble()
            val lb = b.luminance().toDouble()
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }
        ShiftKategorie.entries.forEach { kategorie ->
            listOf(false, true).forEach { dunkel ->
                val farben = kategorieFarben(kategorie, dunkel)
                val k = kontrast(farben.text, farben.flaeche)
                assertTrue("$kategorie dunkel=$dunkel: Kontrast %.2f < 4,5".format(k), k >= 4.5)
            }
        }
    }
}
