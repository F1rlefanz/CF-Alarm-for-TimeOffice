package com.github.f1rlefanz.cf_alarmfortimeoffice.util.text

/**
 * Die wenigen Nutzertexte, die NICHT am Verwendungsort stehen.
 *
 * **Neue Nutzertexte gehoeren an ihren Verwendungsort**, nicht hierher. Diese Sammlung waechst
 * nicht wieder; sie existiert nur noch, weil der Anmeldebildschirm vor dem Compose-Baum
 * gebraucht wird (Icon-Beschreibung) und ein Test gegen `ADD_GOOGLE_ACCOUNT` prueft.
 */
object UIText {
    const val APP_TITLE = "CF-Alarm for TimeOffice"
    const val APP_SUBTITLE = "Automatische Alarmverwaltung für Ihre Schichten"

    const val PERMISSION_EXPLANATION = "Diese App benötigt Zugriff auf Ihren Google Kalender, " +
            "um Schichten zu erkennen und Alarme zu setzen."

    /** Sprung in die Android-Kontoverwaltung, neben der Anmelde-Fehlermeldung. */
    const val ADD_GOOGLE_ACCOUNT = "Google-Konto in den Einstellungen hinzufügen"
}
