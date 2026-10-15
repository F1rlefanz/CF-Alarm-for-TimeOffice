package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hyphens.Auto trennt nach der Sprache aus TextStyle.localeList; fehlt sie, nach der
 * Systemsprache. Jeder Stil mit Silbentrennung muss deshalb Deutsch tragen - sonst werden
 * deutsche Titel auf einem englisch eingestellten Geraet nach englischen Mustern getrennt.
 */
class TypographySilbentrennungTest {

    private val alleStile: Map<String, TextStyle> = with(Typography) {
        mapOf(
            "displayLarge" to displayLarge, "displayMedium" to displayMedium, "displaySmall" to displaySmall,
            "headlineLarge" to headlineLarge, "headlineMedium" to headlineMedium, "headlineSmall" to headlineSmall,
            "titleLarge" to titleLarge, "titleMedium" to titleMedium, "titleSmall" to titleSmall,
            "bodyLarge" to bodyLarge, "bodyMedium" to bodyMedium, "bodySmall" to bodySmall,
            "labelLarge" to labelLarge, "labelMedium" to labelMedium, "labelSmall" to labelSmall,
        )
    }

    @Test
    fun `jeder Stil mit Silbentrennung trennt nach deutschen Regeln`() {
        val getrennt = alleStile.filterValues { it.hyphens == Hyphens.Auto }
        assertTrue("Erwartet: Titel-/Ueberschriften-Stile mit Hyphens.Auto", getrennt.size >= 7)
        getrennt.forEach { (name, stil) ->
            assertEquals("$name: Trennsprache", LocaleList("de"), stil.localeList)
        }
    }
}
