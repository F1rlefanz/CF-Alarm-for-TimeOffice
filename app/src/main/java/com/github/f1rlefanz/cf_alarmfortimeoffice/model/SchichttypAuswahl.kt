package com.github.f1rlefanz.cf_alarmfortimeoffice.model

/**
 * Darf eine AUSWAHLLISTE diesen Schichttyp anbieten? (Umbau Etappe 1, #171)
 *
 * Die EINE Regel hinter dem manuellen Wecker, den Zielen einer Dimmer-Regel, einer DND-Ausnahme
 * und einer Hue-Regel und der Zaehlung in der Uebersicht - vorher an sechs Stellen als
 * `isEnabled && !isSickDay` kopiert. Eine Auswahl, die einen Typ anbietet, der nie greift, ist
 * "angezeigt, wirkt nicht":
 *
 * - **deaktiviert**: die Erkennung ueberspringt ihn, er spannt keine Schicht auf.
 * - **Krank**: gibt den Tag frei, hat keine Weckzeit (#142).
 * - **Ignorieren**: seine Treffer werden uebergangen (#167), auch ihn spannt nichts auf.
 *
 * Dienst, Rufbereitschaft, Frei, Urlaub und Fortbildung bleiben waehlbar - die Kategorie sortiert
 * nur, sie sperrt nichts (SPEC E2). Das ist genau die Menge, aus der `ShiftRecognitionEngine`
 * Schichttreffer macht.
 *
 * Gefragt wird die [ShiftDefinition.kategorie], nicht die Flags: `kategorie == KRANK` gilt genau
 * dann, wenn `isSickDay` gesetzt ist (auch beim Altbestand mit beiden Flags), fuer Krank aendert
 * sich also nichts gegenueber der alten Bedingung.
 *
 * Gilt NICHT fuer die Statuszeile der Typ-Liste (`ShiftViewModel.schichtFolgen`): die braucht auch
 * den Ignorieren-Typ, um zu sagen, dass er uebergangen wird.
 */
fun ShiftDefinition.istWaehlbareSchicht(): Boolean =
    isEnabled && kategorie != ShiftKategorie.KRANK && kategorie != ShiftKategorie.IGNORIEREN

/** Die waehlbaren Typen in der gespeicherten Reihenfolge - siehe [istWaehlbareSchicht]. */
fun List<ShiftDefinition>.waehlbareSchichttypen(): List<ShiftDefinition> =
    filter { it.istWaehlbareSchicht() }

/** Die Namen der waehlbaren Typen (Dimmer-Regel, DND-Ausnahme und Hue-Regel binden ueber den Namen). */
fun List<ShiftDefinition>.waehlbareSchichtNamen(): List<String> =
    waehlbareSchichttypen().map { it.name }

/**
 * Ist [titel] ein Termin, den ein aktiver Ignorieren-Typ uebergeht? (#167)
 *
 * Gegenstueck zu [istKrankmeldung], fuer die Titel-Kostprobe der Karte "Keine Schicht erkannt": ohne
 * diese Frage stuende ein ignorierter Code dort als "im Kalender steht: TB" und legte nahe, ihn als
 * Muster einzutragen. Einfache Pruefung ohne die Reihenfolge der Engine (erst Schicht, dann
 * Ignorieren): die Kostprobe steht nur, wenn nichts erkannt wurde.
 */
fun List<ShiftDefinition>.istIgnorierterTermin(titel: String): Boolean =
    any { it.isEnabled && it.kategorie == ShiftKategorie.IGNORIEREN && it.matchesKeywords(titel) }
