package com.github.f1rlefanz.cf_alarmfortimeoffice.util

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first

/**
 * Einmaliger Preferences-Read, der einen Lesefehler NICHT nach oben durchreicht.
 *
 * WARUM: Der `ReplaceFileCorruptionHandler` der Stores faengt ausschliesslich eine
 * `CorruptionException`. Eine IOException auf der `preferences_pb` (Speicherdruck, volle Platte,
 * haengendes Storage) reicht DataStore unveraendert durch. Landet so ein Read ungeschuetzt in
 * einem Compose-`LaunchedEffect` - wie in der Onboarding-/Gate-Kette von `MainScreen` -, beendet
 * die Exception die App beim Erreichen des Hauptbereichs, und zwar reboot-fest, solange der
 * Lesefehler besteht.
 *
 * WOHIN degradiert wird, ist die eigentliche Entscheidung: auf LEERE Preferences, also auf den
 * Default jedes einzelnen Schluessels. Fuer die "schon abgelehnt"-Markierungen des Onboardings
 * heisst das `false` = NICHT abgelehnt, der Hinweis wird also im Zweifel GEZEIGT. Diese Richtung
 * ist Absicht und dieselbe Abwaegung wie beim `DeviceLocalFlagsGuard`: ein ueberzaehliger Hinweis
 * ist harmlos und verschwindet, sobald die Einstellung wirklich gesetzt ist - ein unterdrueckter
 * Hinweis kostet die Akku-Ausnahme bzw. die Ausnahme von "App bei Nichtnutzung pausieren", und
 * genau die haben in diesem Projekt schon einmal alle Wecker verschluckt.
 *
 * Fuer SCHREIBWAHRHEITEN ist dieser Helfer NICHT gedacht - dort waere ein stiller Default genau
 * die Notlage-Leere, die der naechste Read-Modify-Write ueber echte Nutzerdaten schreibt (siehe
 * `AlarmRepository`, `ShiftConfigRepository`).
 */
internal suspend fun DataStore<Preferences>.readOrEmpty(logTag: String, what: String): Preferences =
    data
        .catch { e ->
            Logger.w(
                logTag,
                "$what nicht lesbar - degradiert auf Standardwerte (Hinweis wird im Zweifel gezeigt)",
                e
            )
            emit(emptyPreferences())
        }
        .first()
