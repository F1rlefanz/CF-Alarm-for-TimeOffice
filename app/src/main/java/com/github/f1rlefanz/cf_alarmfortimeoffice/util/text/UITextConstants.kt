package com.github.f1rlefanz.cf_alarmfortimeoffice.util.text

/**
 * Die wenigen Nutzertexte, die NICHT am Verwendungsort stehen.
 *
 * Hier standen bis zum 22.08.2026 siebzig Konstanten, von denen genau vier einen Aufrufer
 * hatten - alle vier in `LoginScreen.kt`. Die uebrigen 66 ("Uebersicht", "Abbrechen",
 * "Lauft...", ganze Status- und Fehlerbausteine) sahen wie aktive Oberflaechentexte aus und
 * wurden als Vorlage weitergeschleppt, obwohl sie nie irgendwo erschienen. Der Skill
 * `cfalarm-ui-und-navigation` verlangt genau deshalb, sie zu loeschen statt liegen zu lassen:
 * ein Text, den es in der App nicht gibt, ist eine Behauptung ueber eine Oberflaeche, die es
 * nicht gibt.
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
