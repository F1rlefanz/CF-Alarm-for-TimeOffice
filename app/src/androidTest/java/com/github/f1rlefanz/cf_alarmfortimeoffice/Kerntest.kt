package com.github.f1rlefanz.cf_alarmfortimeoffice

/**
 * JUnit-4-Kategorie fuer die Kerntests: Tests, deren Bruch Daten oder den Wecker kostet.
 *
 * Markiert wird mit `@Category(Kerntest::class)` direkt ueber `@Test`. Die Liste aller Kerntests
 * steht in `tools/kerntests/kerntests.txt`; `tools/kerntests/pruefe_kerntests.py` haelt Liste und
 * Markierungen deckungsgleich und schlaegt an, wenn ein Kerntest verschwindet, sein `@Test`
 * verliert oder ein `@Ignore` bekommt.
 *
 * Kerntests sind beim Aufraeumen tabu: nicht loeschen, nicht zusammenlegen, Erwartungswerte nur
 * bei bewusst geaendertem Verhalten anpassen - und dann mit Grund.
 *
 * Diese Datei gibt es zweimal (test und androidTest), weil beide Quellsets getrennt kompilieren.
 */
interface Kerntest
