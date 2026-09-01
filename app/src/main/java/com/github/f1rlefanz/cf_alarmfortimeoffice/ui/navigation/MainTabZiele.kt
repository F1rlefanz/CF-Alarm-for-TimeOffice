package com.github.f1rlefanz.cf_alarmfortimeoffice.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.github.f1rlefanz.cf_alarmfortimeoffice.navigation.MainTab

/**
 * Wie die sechs Hauptbereiche heissen und aussehen - an EINER Stelle.
 *
 * WARUM ES DIESE LISTE GIBT: Bis v1.37.3 lagen Reihenfolge, Symbol und Beschriftung in der
 * unteren Navigationsleiste (`MainContentScreen`), waehrend jeder Tab-Inhalt seine eigene
 * Ueberschrift nochmal selbst setzte - und beide wichen voneinander ab. Die Leiste zeigte
 * "Home / Wecker / Dimmen / Hue / Status / Einstellungen", die Ueberschriften sagten
 * "Uebersicht / Wecker / Schicht-Dimmer / Philips Hue Integration / System-Status /
 * Einstellungen". Dazu kam eine dritte Reihenfolge: das Enum [MainTab] steht
 * HOME, WECKER, STATUS, SETTINGS, HUE, DIMMER. Drei Quellen, von Hand parallel gehalten,
 * ohne dass irgendetwas das gehalten haette.
 *
 * Seit v1.38.0 traegt ein Eintrag beides: den Namen in der Navigationsschublade UND den Titel
 * in der Kopfzeile. Zwei Namen fuer denselben Bereich koennen damit nicht mehr auseinanderlaufen.
 *
 * WARUM UNTER `ui/` UND NICHT IM PAKET `navigation/`: Dort liegt heute kein einziger
 * Compose-Typ. `ImageVector` gehoert nicht in ein Paket, das auch von Hintergrundkomponenten
 * gelesen werden koennte - siehe die Regel "kein Hintergrund-Paket importiert `ui.`" in
 * CLAUDE.md, die auch in der Gegenrichtung sauber bleiben soll. [MainTab] selbst bleibt
 * deshalb, wo es ist: es ist das Rueckweg-Gedaechtnis von 13 `NavigationState`-Zustaenden
 * (`returnToTab`) und hat mit der Darstellung nichts zu tun.
 */
data class MainTabZiel(
    val tab: MainTab,
    /** Ein Text fuer beides: Eintrag in der Schublade UND Titel in der Kopfzeile. */
    val titel: String,
    val icon: ImageVector
)

/**
 * Die Anzeigereihenfolge der Schublade.
 *
 * Sie ist bewusst NICHT die Enum-Reihenfolge: fachlich gehoeren Wecker, Dimmer und Hue
 * zusammen (das taegliche Geschehen), Status und Einstellungen ans Ende (Nachschlagen und
 * Einrichten). Das Enum umzusortieren waere die schlechtere Loesung - es wird an 13 Stellen
 * als `returnToTab` gehalten und geht die Darstellung nichts an.
 */
val MAIN_TAB_ZIELE: List<MainTabZiel> = listOf(
    MainTabZiel(MainTab.HOME, "Übersicht", Icons.Filled.Home),
    MainTabZiel(MainTab.WECKER, "Wecker", Icons.Filled.AccessAlarm),
    MainTabZiel(MainTab.DIMMER, "Schicht-Dimmer", Icons.Filled.DarkMode),
    MainTabZiel(MainTab.HUE, "Philips Hue", Icons.Filled.Lightbulb),
    MainTabZiel(MainTab.STATUS, "System-Status", Icons.Filled.Info),
    MainTabZiel(MainTab.SETTINGS, "Einstellungen", Icons.Filled.Settings)
)

/**
 * Der Eintrag zu einem Bereich.
 *
 * `first` und nicht `firstOrNull`: fehlte ein Bereich in der Liste, waere das ein Fehler im
 * Programm und kein Zustand, den die Oberflaeche sinnvoll ueberspielen koennte - eine Kopfzeile
 * ohne Titel waere die schlechtere Auskunft als ein Absturz im Debug-Build. `MainTabZieleTest`
 * haelt fest, dass jeder Enum-Wert genau einmal vorkommt, damit dieser Fall gar nicht entsteht.
 */
fun mainTabZiel(tab: MainTab): MainTabZiel = MAIN_TAB_ZIELE.first { it.tab == tab }
