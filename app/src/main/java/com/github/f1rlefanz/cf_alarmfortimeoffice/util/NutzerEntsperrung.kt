package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import android.content.Context
import android.os.UserManager

/**
 * Ist der Nutzer entsperrt, also CREDENTIAL-ENCRYPTED Storage lesbar?
 *
 * Die EINE Stelle fuer diese Frage (#131, G8-21/G11-18). Bis v1.45 stand sie achtmal von Hand
 * ausgeschrieben - und die RICHTUNG der Degradation darf nicht an einer davon still kippen.
 *
 * Im Zweifel `true` (entsperrt), und das ist an jeder Aufrufstelle die richtige Wahl: ein
 * falsch-positives "gesperrt" sperrte Persistenz, Kalenderauswahl und Schicht-Konfiguration
 * dauerhaft als "unlesbar" bzw. liesse einen Wecker den Skip-Status nie pruefen; ein
 * ueberfluessiger Leseversuch landet dagegen im jeweils vorhandenen Fehlerpfad. Wer diese
 * Richtung aendern will, entscheidet das fuer ALLE Aufrufer - mit dem Eigentuemer.
 */
object NutzerEntsperrung {

    fun istEntsperrt(context: Context, logTag: String): Boolean = try {
        context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
    } catch (e: Exception) {
        Logger.w(logTag, "UserManager nicht abfragbar - Nutzer gilt als entsperrt", e)
        true
    }
}
