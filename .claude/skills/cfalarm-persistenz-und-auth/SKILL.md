---
name: cfalarm-persistenz-und-auth
description: "Zusicherungen fuer DataStore-Persistenz, OAuth2-Token-Rotation, Fehlerklassifizierung sowie Geraetewechsel und Konfigurations-Export/Import der CFAlarm-Wecker-App. Kernregel: eine stille Degradierung auf 'leer' oder 'Default' darf nie zur Schreibwahrheit werden, und die Richtung der Degradation ist je Store bewusst gewaehlt. Zu verwenden bei Arbeit an AlarmRepository, ShiftConfigRepository, DimOverlayPrefs, MasterPausePrefs, DataStoreTokenRepository, EncryptedDataStoreFactory, OAuth2TokenManager, AuthUseCase, SafeExecutor, DeviceLocalFlagsGuard oder ConfigBackupFilter — und immer dann, wenn Daten nach einem Lesefehler verschwinden, ein Re-Login erzwungen wird, ein Import fremde Werte einschleust oder ein DataStore nicht mehr beschreibbar ist."
---

# Persistenz, Auth und Konfigurations-Datei

Unten stehen die **Kurzregeln** dieses Bereichs — was gilt, und was bei Bruch passiert.
Die wecker-kritische Teilmenge davon steht zusätzlich in `CLAUDE.md` (dort immer geladen, als
Sicherheitsnetz für den Fall, dass dieser Skill nicht anspringt); **alles Übrige steht
ausschließlich hier.** **Reicht die Kurzregel nicht, oder willst du eine davon ändern oder
umgehen: lies vorher die Hergang-Datei.** Dort steht, welcher Bug die Regel erzwungen hat — ohne
das baut man dieselbe Falle in neuer Form nach.

## Hergang und Belege

- `reference/persistenz.md` — DataStore-Degradation, Direct-Boot-Spiegel, Reihenfolge von catch und map
- `reference/auth-und-token.md` — Token-Rotation, Verlust-Signal, Abmelden
- `reference/fehlerbehandlung.md` — SafeExecutor, CancellationException, Fehlerklassifizierung
- `reference/geraetewechsel-und-export.md` — DeviceLocalFlagsGuard, Export/Import-Filter

---

## Kurzregeln

- **Die REIHENFOLGE von `.catch` und `.map` in einem Preferences-Flow ist tragend.** `.catch` gehört
  **hinter** das `.map` und muss zusätzlich den Cache invalidieren — sonst wird „Store nicht lesbar"
  von „noch nie konfiguriert" ununterscheidbar und die Standardkonfiguration gilt als Erfolg.
- **Stille Degradierung darf nie zur Schreibwahrheit werden.** DataStore liest vor jedem Write erneut.
  - **`AlarmRepository`**: unlesbar/undekodierbar → Persistenz für diesen Prozess sperren, Roh-JSON
    unter `active_alarms_broken` sichern. Bereit-Signal (`CompletableDeferred`) + gemeinsamer Mutex
    für alle Ganzlisten-Schreibpfade. `clearInternalAlarms` liest über `getAllAlarms()`.
    `deleteAllAlarms()` räumt bewusst trotzdem (force).
- **`isPersistenceBlocked()` bedeutet NUR „der Alarm-Bestand ist in diesem Prozess nicht lesbar“.**
  Ein gescheiterter SCHREIBvorgang hat sein eigenes Signal (`istLetzterSchreibvorgangGescheitert()`),
  und die beiden dürfen nie verodert werden: `clearInternalAlarms()` liest das erste als „unlesbar“
  und überspringt dann die `cancelSystemAlarm()`-Schleife — nach einem Schreibfehler wäre das
  „Räumen ohne Cancellen“. Anzeigen fragt beide, Räumen fragt ausschließlich `isPersistenceBlocked()`.
  Allgemein: **ein bestehendes Signal um eine zweite Bedeutung erweitern heißt, JEDE Leserstelle
  einzeln daraufhin anzusehen** — im Zweifel ein eigenes Signal.
- **`saveAlarm()` darf einen nur im Arbeitsspeicher liegenden Wecker nicht als Erfolg verschweigen.**
  `Result.failure` ist bewusst NICHT die Antwort (siehe Hergang); stattdessen WARN im Release-Log,
  der getrennte Schreibfehler-Merker, und wer Dauerhaftigkeit braucht, fragt NACH dem Speichern nach.
  - **`EncryptedPreferencesSerializer.readFrom()`** wirft weiter, statt `defaultValue` zu liefern —
    und deutet den Fehler **nicht** als `CorruptionException` um. `writeTo()` schreibt einen leeren
    Zustand als 0-Byte-Datei.
  - **`DimRuleRepository`**: `coerceInputValues` gilt für die ANZEIGE, `editRules()` liest **strikt**;
    `upsert`/`delete` als Read-Modify-Write INNERHALB einer `dataStore.edit{}`-Transaktion.
- **Ein CE-DataStore-Read VOR der ersten Entsperrung wirft NICHT — er liefert still leere
  Preferences.** Deshalb fragt `AlarmRepository` VOR dem Read den `UserManager`, akzeptiert bei
  gesperrtem Nutzer KEIN Ergebnis und lädt beim ersten Zugriff nach dem Entsperren nach —
  aufgehängt an `awaitInitialLoad()` **und** `onStart` am `activeAlarms`-Flow.
- **Der Direct-Boot-Spiegel wird bei JEDEM erfolgreichen Load abgeglichen** (`saveAll` ist idempotent).
- **Die Reads der Onboarding-/Gate-Kette gehen über `readOrEmpty()`** und degradieren auf „NICHT
  abgelehnt" — im Zweifel wird der Hinweis GEZEIGT.
- **Bei der Master-Pause ist die RICHTUNG der Degradation die Entscheidung**: auf `false` = NICHT
  pausiert. Ein fälschlich klingelnder Wecker ist abstellbar, ein stummer fällt beim Verschlafen auf.
  Der Fehler wird geloggt.
- **`DimOverlayPrefs` schützt alle Lese-Flows über EINEN gemeinsamen `safeData`-Quell-Flow**;
  Degradation heißt `overlayOn = false` — **im Zweifel NICHT verdunkeln**.
- **`TinkEncryptionException` wird als `CorruptionException` übersetzt** (nur die fängt DataStores
  Selbstheilung) — eine Neuanmeldung ist besser als ein dauerhaft schreib-toter Token-Store.

## Geraetewechsel und Konfigurations-Datei — Kurzregeln

- **`DeviceLocalFlagsGuard` setzt beim erkannten Gerätewechsel gerätelokale Flags zurück**
  (`Build.FINGERPRINT`). Ein selektiver Backup-Ausschluss ist unmöglich — ein Preferences-Store ist
  EINE Datei. Fehlt der Marker, wird NICHT zurückgesetzt. Die beiden Backup-Regel-Dateien müssen
  inhaltlich identisch bleiben.
- **Eine mitgesicherte Master-Pause wird über `resume()` aufgehoben, NICHT durch Löschen des
  Schlüssels** — `master_pause_enabled` steht deshalb bewusst NICHT in `DEVICE_LOCAL_KEY_PATTERNS`.
- **Der Konfigurations-Export entscheidet durch AUSSCHLUSS, nicht durch Aufzählen**
  (`ConfigBackupFilter`, `exclusionReason()` ist der EINE Ort). Drei Ausschlussgründe:
  Laufzeitzustand, Gerätebezug/Zugangsdaten, gerätelokale Onboarding-Markierungen. **Der Filter gilt
  in BEIDE Richtungen**, abgelehnte Schlüssel werden BENANNT. Die Liste stammt aus einer Inventur
  ALLER `*PreferencesKey("…")` im Baum.
- **Der Import lehnt eine LEERE Definitionsliste ab** (kotlinx.serialization füllt still `emptyList()`).
- **Der erwartete TYP kommt vom SCHLÜSSEL, nicht aus der Datei** — ein falsch typisierter Wert liegt
  reboot-fest und wirft bei jedem Lesen, bevor ein Default greifen kann.
- **Der Schlüssel-Filter sagt nichts über den WERT**: `snooze_minutes` und `dnd_oncall_cutoff_min`
  sind zusätzlich bereichsgeprüft — **und zusätzlich im LESEPFAD geklemmt** (Android-Backup ist ein
  zweiter Weg, den der Import nie sieht).
- **Unlesbare Regelwerke werden beim Import BENANNT abgelehnt**, statt still auf „leere Liste" zu fallen.

## Auth — Kurzregeln

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.**
- **GMS-Token-Cache liegt außerhalb des App-Speichers** — nur `GoogleAuthUtil.clearToken()` räumt ihn ab.
- **`auth_prefs` braucht `corruptionHandler` UND `.catch{}`**; Degradation auf „nicht angemeldet".
- **`onResult` gehört `OAuth2TokenManager.authorize()`** — es feuert auf jedem Weg genau einmal.
- **`observeTokenLoss()` nimmt nur das NEGATIVE Signal**; `drop(1)` ist Pflicht.
- **`signOutInProgress` nicht wegoptimieren** — `isSignedIn` allein reicht nicht.
- **Abmelden heißt: nichts bleibt zurück** (Auth-Daten UND Token inkl. GMS-Cache) — und
  „nichts“ schließt die gestellten Wecker ein: `AuthViewModel.signOut()` räumt sie in BEIDEN
  Zweigen weg (`stopScheduledWorkForSignOut()`).
- **Reihenfolge: erst abmelden, dann aufräumen** — umgekehrt entsteht „angemeldet, aber alle Wecker
  weg“, und der Rückbau dagegen ist dreimal gescheitert. Nicht wieder umdrehen.
- **Der ganze Block ab dem Verwerfen des Tokens liegt in EINEM `withContext(NonCancellable)`** —
  `GoogleAuthUtil.clearToken()` ist ein Netzaufruf und hängt ohne Netz bis zum Timeout.
- **Ein Failure aus `signOut()` heißt „Token weg, Auth-Daten noch da“**, nicht „nichts passiert“ —
  der Aufrufer behandelt den Fehlerzweig wie den Erfolgszweig.
- **Prozesstod im Abmelde-Fenster ist eine BEWUSST offene Lücke** (dauerhafter Merker nach Messung
  verworfen) — nicht als neuen Bug melden.
- **Eine frische Neu-Autorisierung ist KEIN Kettenbruch** (`isLegitimateSuccessorOf`: identisch,
  direkt rotiert, oder per `authorize()` geholt). Der Diebstahls-Zweig bleibt für ältere Tokens.
- **`refresh()` prüft den NEUEN Token gegen die ID des ALTEN**
  (`storedToken.validateRotation(currentToken.rotationId)`) — die vertauschte Variante schlägt bei
  jeder legitimen gleichzeitigen Rotation fehl und erzwingt einen Re-Login.
- **`DataStoreTokenRepository.observe()` nutzt `retryWhen`, und der Fehlerfall emittiert NICHTS** —
  kein Signal statt falschem Signal.

## Fehlerbehandlung — Kurzregeln

- **`SafeExecutor.safeExecute()` wirft `CancellationException` WEITER**, statt sie in einen `AppError`
  zu verpacken — sonst laufen nachgelagerte `catch (e: CancellationException)`-Zweige ins Leere.
- **„Kein Token vorhanden" landet als `UnknownError` im Log — und das umzubiegen wäre ein Fehler.**
  Eine Abbildung auf `AuthenticationError` würde `invalidateTokenIfRejectedByGoogle()` (nur für 401
  gedacht) ohne Anlass den GMS-Cache leeren lassen. Das Verhalten selbst ist korrekt.
