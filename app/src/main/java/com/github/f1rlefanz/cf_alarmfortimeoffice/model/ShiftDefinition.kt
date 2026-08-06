package com.github.f1rlefanz.cf_alarmfortimeoffice.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.util.Locale

/**
 * IMMUTABLE Shift Definition Model
 * 
 * PERFORMANCE OPTIMIZATIONS:
 * ✅ @Immutable annotation für Compose-Performance
 * ✅ Unveränderliche Keywords-Liste für bessere Cache-Performance
 */
@Immutable
@Serializable
data class ShiftDefinition(
    val id: String,
    val name: String,
    val keywords: List<String>,
    @Serializable(with = LocalTimeSerializer::class)
    val alarmTime: LocalTime,
    val isEnabled: Boolean = true,
    // "Stille Schicht" (z.B. Rufbereitschaft AD1): alarmTime bleibt Pflicht-Anker fuer
    // DND/Dimmer/Hue, aber Ton/Vibration/Vollbild-Wecker werden beim Feuern unterdrueckt.
    // Bewusst KEIN Ersatz fuer eine optionale alarmTime - siehe CLAUDE.md "Stille Schicht".
    val isSilent: Boolean = false
) {
    /**
     * Get alarm time as formatted string for display
     */
    fun getAlarmTimeFormatted(): String {
        return String.format(Locale.US, "%02d:%02d", alarmTime.hour, alarmTime.minute)
    }
    
    /**
     * Get alarm local time for scheduling
     */
    fun getAlarmLocalTime(): LocalTime = alarmTime
    
    /**
     * Trifft dieser Schichttyp auf den Titel eines Kalendertermins zu?
     *
     * Gematcht wird auf WORTGRENZEN ([matchesAsWholeWord]) - "F" trifft "Meeting F", aber nicht
     * "Fortbildung" oder "12F". Das ist Absicht und getestet; nicht auf `contains` umbauen.
     *
     * ZWEI DINGE, DIE HIER LEICHT ZURUECKGEDREHT WERDEN:
     *
     * 1. Muster werden GETRIMMT, und leere Muster matchen NIE. Der [ShiftEditDialog] trimmte
     *    beim Speichern nur den Namen, nicht die Muster - ein per Autovervollstaendigung
     *    angehaengtes Leerzeichen wurde als " IMCF" gespeichert und ergab den Regex
     *    `\b\Q imcf\E\b`: das fuehrende `\b` verlangt ein Wortzeichen VOR dem Leerzeichen, ein
     *    Titel "IMCF" traf also nicht mehr. Die UI zeigte dabei genau das Muster, das der Nutzer
     *    erwartete. Der Trim hier repariert zusaetzlich schon gespeicherte Altwerte.
     *    Die Blank-Pruefung ist kein Luxus: `Regex.escape("")` ergibt `\b\Q\E\b`, und das trifft
     *    JEDEN Titel mit einem Wortzeichen - ein leeres Muster wuerde also alles matchen.
     *
     * 2. Der [name] zaehlt als zusaetzliches Muster - aber nur ab [ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH]
     *    Zeichen. Vorher matchte AUSSCHLIESSLICH [keywords]: ein Termin, der wortwoertlich
     *    "Zwischendienst" hiess, wurde von der Definition "Zwischendienst" NICHT erkannt, solange
     *    deren Muster nur das Stationskuerzel enthielt. Fuer einen Nutzer ist das nicht
     *    erklaerbar - er sieht den Namen und haelt ihn fuer das Erkennungsmerkmal. Die
     *    Laengengrenze ist Pflicht: ein Definitionsname "F" wuerde sonst genau die einbuchstabige
     *    Falle zurueckholen, gegen die [ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH] existiert.
     */
    fun matchesKeywords(eventTitle: String): Boolean {
        val title = eventTitle.lowercase()
        if (title.isBlank()) return false

        if (keywords.any { matchesAsWholeWord(title, it) }) return true

        val trimmedName = name.trim()
        return trimmedName.length >= ShiftConfig.MIN_FUZZY_KEYWORD_LENGTH &&
            matchesAsWholeWord(title, trimmedName)
    }

    private fun matchesAsWholeWord(lowercaseTitle: String, pattern: String): Boolean {
        val needle = pattern.trim().lowercase()
        if (needle.isEmpty()) return false
        return "\\b${Regex.escape(needle)}\\b".toRegex().containsMatchIn(lowercaseTitle)
    }
}


