# Persistenz (DataStore) — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- Die REIHENFOLGE von `.catch` und `.map` in einem Preferences-Flow ist tragend
- Die Preferences-Reads der Onboarding-/Gate-Kette gehen über `readOrEmpty()`
- Bei der Master-Pause ist die RICHTUNG der Degradation die eigentliche Entscheidung
- `DimOverlayPrefs` schützt seine 13 Lese-Flows über EINEN gemeinsamen `safeData`-Quell-Flow
- Stille Degradierung darf nie zur Schreibwahrheit werden
- Ein CE-DataStore-Read VOR der ersten Entsperrung wirft NICHT — er liefert still leere
- Der Direct-Boot-Spiegel wird bei JEDEM erfolgreichen Load abgeglichen
- `TinkEncryptionException` wird in `EncryptedDataStoreFactory` als `CorruptionException`

---

- **Die REIHENFOLGE von `.catch` und `.map` in einem Preferences-Flow ist tragend** (v1.24.0).
  `ShiftConfigRepository.shiftConfig` hatte `.catch { emit(emptyPreferences()) }` **vor** dem `.map`.
  Damit konnte das `map` „Store nicht lesbar" nicht mehr von „noch nie konfiguriert" unterscheiden:
  `decodeShiftConfig(json, null)` liefert `NotConfigured`, und genau dieser Zweig schreibt
  `cachedConfig = getDefaultConfig()` samt frischem Zeitstempel — während der `Broken`-Zweig
  daneben bewusst NICHT cacht. Folge: `getCurrentShiftConfig()` prüft den Cache als allererste
  Anweisung und lieferte 30 s lang (`CACHE_VALIDITY_MS`) die **Standardkonfiguration als
  `Result.success`**, ohne den frischen Read zu erreichen, der ehrlich gescheitert wäre. Die vier
  Konsumenten, die sich ausdrücklich auf dieses Scheitern verlassen
  (`ShiftViewModel.observeExternalConfigChanges()`, `AlarmMaintenanceService`, `CFAlarmApplication`,
  `CalendarViewModel`), hätten mit Standard-Weckzeiten synchronisiert und die Alarme nicht mehr
  erkannter Schichten im Delta-Sync gelöscht. Auslöser ist eine **IOException** auf `shift_prefs` —
  der `ReplaceFileCorruptionHandler` fängt nur `CorruptionException`. Das `.catch` steht deshalb
  jetzt **hinter** dem `.map` (dann sieht das `map` den degradierten Zustand nie und kann nichts
  cachen) und **invalidiert zusätzlich den Cache**. Die Anzeige darf degradieren, die
  SCHREIBWAHRHEIT nicht. Wer das `.catch` wieder nach oben zieht, baut den Bug zurück.
- **Die Preferences-Reads der Onboarding-/Gate-Kette gehen über `readOrEmpty()`**
  (`util/SafePreferencesRead.kt`, gemeinsamer Pfad für `BatteryOptimizationHelper`,
  `UnusedAppRestrictionsHelper`, `TimeOfficeHealthHelper`). Vorher waren es blanke `data.first()`:
  der `ReplaceFileCorruptionHandler` fängt nur `CorruptionException`, eine IOException auf
  `settings.preferences_pb` reicht DataStore durch — und drei dieser Reads stehen direkt im
  `LaunchedEffect` von `MainScreen`, die Exception hätte die App beim Erreichen des Hauptbereichs
  beendet, reboot-fest solange der Lesefehler besteht. Degradiert wird auf leere Preferences, also
  auf `false` = NICHT abgelehnt: **der Hinweis wird im Zweifel GEZEIGT.** Dieselbe Abwägung wie beim
  `DeviceLocalFlagsGuard` — ein überzähliger Hinweis ist harmlos, ein unterdrückter kostet die
  Akku-Ausnahme bzw. die Ausnahme von „App bei Nichtnutzung pausieren". Für SCHREIBWAHRHEITEN ist
  der Helfer ausdrücklich nicht gedacht (siehe den Punkt darunter).
- **Bei der Master-Pause ist die RICHTUNG der Degradation die eigentliche Entscheidung.**
  `MasterPausePrefs.paused` hatte kein `.catch` (v1.24.0 ergänzt, Vorbild `auth_prefs`) — betroffen
  waren der zentrale Backstop in `AlarmUseCase.syncAlarms()`, die Gates von
  `DimScheduleUseCase`/`DndScheduleUseCase` und der `BootReceiver`. Degradiert wird auf **`false` =
  NICHT pausiert**: ein fälschlich wiederhergestellter Wecker klingelt hörbar und ist abstellbar,
  ein fälschlich unterdrückter ist STILL und fällt erst beim Verschlafen auf. Dieselbe Abwägung wie
  beim `DeviceLocalFlagsGuard`. Der Fehler wird geloggt — sonst ist er im Log von normalem,
  nicht pausiertem Betrieb nicht zu unterscheiden.
- **`DimOverlayPrefs` schützt seine 13 Lese-Flows über EINEN gemeinsamen `safeData`-Quell-Flow**
  (v1.24.0), nicht über 13 einzelne `.catch`-Blöcke — damit ist ein später ergänzter Flow nicht
  wieder ungeschützt. Degradiert wird auf leere Preferences, also auf den Default jedes Flows; für
  `renderState` heißt das `overlayOn = false`. Diese Richtung ist Absicht: **im Zweifel NICHT
  verdunkeln.** Bei voller Verdunkelung kann der Nutzer sein Gerät nicht mehr bedienen und den
  Dimmer nicht mehr abschalten — ein unerwartet heller Bildschirm ist das kleinere Übel.
- **Stille Degradierung darf nie zur Schreibwahrheit werden.** DataStore liest vor jedem Write
  erneut; wer einen Lesefehler auf „leer"/„Default" degradiert, speist genau diese Notlage-Leere in
  den nächsten Read-Modify-Write und überschreibt echte Nutzerdaten. Konkret festgelegt:
  - **`AlarmRepository`**: ein nicht dekodierbarer `active_alarms`-Wert (oder ein Lesefehler) sperrt
    die Persistenz für diesen Prozess und sichert das Roh-JSON unter `active_alarms_broken`. Vorher
    setzte der Init-Load `emptyList()`, erfüllte das Bereit-Signal — und der Delta-Sync hielt jede
    Schicht für neu und schrieb über Rohdaten UND Direct-Boot-Spiegel. Verloren gingen genau die
    Alarme, die sich nicht aus dem Kalender rekonstruieren lassen (manuelle), plus der einzige Weg
    zurück vor der ersten Entsperrung nach einem Reboot. `deleteAllAlarms()` räumt bewusst trotzdem
    (force): Master-Pause muss den Spiegel wirklich leeren, sonst re-armt der Direct-Boot-Restore
    pausierte Alarme. Dazu ein Bereit-Signal (`CompletableDeferred`) plus gemeinsamer Mutex für alle
    Ganzlisten-Schreibpfade — vorher lieferte `getAllAlarms()` im Prozess-Startfenster fälschlich
    eine leere Liste, und der nachträglich zurückkehrende Init-Load überschrieb Cache, DataStore und
    Spiegel mit seinem alten Snapshot. Deshalb liest auch `clearInternalAlarms` über
    `getAllAlarms()`, nicht über `activeAlarms.first()`: sonst wurde KEIN System-Alarm gecancelt,
    während Repository und Spiegel geleert wurden — der verwaiste Alarm feuerte trotz Master-Pause.
  - **`EncryptedPreferencesSerializer.readFrom()`** wirft Fehler unverändert weiter, statt still
    `defaultValue` zu liefern (für DataStore der gültige Ist-Zustand, den der nächste Write über den
    intakten Ciphertext schreibt). Bewusst **nicht** als `CorruptionException` umgedeutet: ein
    IO-Fehler oder eine Cancellation dürfen den `corruptionHandler` nicht auslösen, der würde die
    intakte Datei ersetzen; ein defektes Protobuf meldet der `delegateSerializer` ohnehin selbst.
    `writeTo()` schreibt einen LEEREN Zustand als 0-Byte-Datei (`readFrom` liest das als „noch
    nichts gespeichert") — bei unbrauchbarem Keyset scheiterte sonst auch der Ersatz-Write an
    derselben `aead`-Instanz und der Store blieb lese- UND schreib-tot. Keyset-Neuaufbau bleibt offen.
  - **`DimRuleRepository`**: `coerceInputValues` gilt für die ANZEIGE, `editRules()` liest **strikt**.
    Sonst schrieb das nächste `upsert()`/`delete()` — auch an einer völlig anderen Regel — einen auf
    den Feld-Default gefallenen Anker dauerhaft fest. `upsert`/`delete` laufen als Read-Modify-Write
    INNERHALB einer einzigen `dataStore.edit{}`-Transaktion (Vorbild `HueConfigRepository`), damit ein
    Doppel-Tap keine Änderung verliert und ein defektes JSON das Speichern abbricht statt den ganzen
    Regelbestand zu leeren (inklusive der bedeutungstragenden leeren Fensterliste).
- **Ein CE-DataStore-Read VOR der ersten Entsperrung wirft NICHT — er liefert still leere
  Preferences.** Am Emulator mit PIN im Zustand `RUNNING_LOCKED` nachgemessen: die Datei ist nicht
  öffenbar, `exists()` ist false, DataStore fällt auf `serializer.defaultValue` zurück und meldet
  ERFOLG. Im Log stand „📭 No saved alarms found in DataStore", `persistenceBlocked` blieb false, und
  der Cache galt als Wahrheit. **Und dieser Prozess stirbt beim Entsperren nicht** — er ist derselbe,
  in dem der Nutzer die App danach bedient (pid im Test unverändert), während der Init-Load nur EINMAL
  lief. Folge: die App hielt dauerhaft „keine Alarme" für wahr, obwohl `active_alarms` sie noch
  enthielt; der nächste Sync hielt jede Schicht für neu und schrieb Bestand UND Direct-Boot-Spiegel
  neu — der manuelle Wecker war weg, und im Log sah das wie ein normaler Erstsync aus. Beide anderen
  Wachen liefen dabei ins Leere (`keepManualAlarms` kann nichts schonen, was nicht in der Liste steht;
  `isPersistenceBlocked()` meldet nichts ohne gesetzte Sperre). Deshalb: `AlarmRepository` fragt VOR
  dem Read den `UserManager`, akzeptiert bei gesperrtem Nutzer KEIN Ergebnis (Sperre an, damit kein
  Write die Notlage-Leere festschreibt) und **lädt beim ersten Zugriff nach dem Entsperren nach** —
  aufgehängt in `awaitInitialLoad()`, weil da jeder Lese- und Ganzlisten-Schreibpfad durchgeht, PLUS
  `onStart` am `activeAlarms`-Flow (ein Bildschirm, der nur beobachtet, ruft keine Methode; der Haken
  fehlte im ersten Wurf und fiel erst am Gerät auf). `CalendarSelectionRepository` hat für dieselbe
  Prozess-Lage `retryWhen`.
- **Der Direct-Boot-Spiegel wird bei JEDEM erfolgreichen Load abgeglichen.** `persistToDataStore()`
  schreibt zuerst den DataStore, dann den Spiegel; fällt der zweite Schritt aus, divergieren beide —
  und die Divergenz war PERMANENT, weil nachgespiegelt nur wurde, wenn der Load selbst abgelaufene
  Alarme entfernt hatte. Der häufigste Sync-Zweig („unverändert – nur re-armen") schreibt das
  Repository gar nicht, der Spiegel konnte also wochenlang falsch bleiben und nach einem Reboot die
  falschen (oder keine) Alarme wiederherstellen. `saveAll` ist idempotent.
- **`TinkEncryptionException` wird in `EncryptedDataStoreFactory` als `CorruptionException`
  übersetzt** (nur die fängt DataStores Selbstheilung), plus `ReplaceFileCorruptionHandler`. Abwägung:
  ein nicht entschlüsselbarer Token ist ohnehin wertlos — EINE Neuanmeldung ist das kleinere Übel
  gegenüber einer App, die nie wieder einen Token speichern kann (Endlos-Re-Auth, keine Alarme).
