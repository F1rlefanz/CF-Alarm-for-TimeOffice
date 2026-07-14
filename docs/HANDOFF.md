# Handoff — CF-Alarm for TimeOffice

**Lebendes Dokument.** Erledigtes wird gestrichen, nicht abgehakt — was hier steht, ist offen.
Die Historie steht im Git-Log, nicht hier.

**Stand:** 14.07.2026 · `main` = **v1.10.4 / versionCode 47** · alles gemerged und gepusht.

> **Ungeprüft in v1.10.3/1.10.4:** der doppelte Auth-Callback (braucht ein Abmelden/Anmelden
> im Log: „Calendar authorization successful" muss **einmal** statt zweimal stehen, keine
> `JobCancellationException`), die einmalige Bridge-Init (beim Start nur **ein**
> „BRIDGE-MANAGER: Initializing", keine Job-ID-Kaskade) und die Regel-Validierung.

---

## Offen

### 1. Der Weckvorgang selbst ist ungetestet ← wichtigster Punkt

Alles andere ist Beiwerk, solange das nicht bewiesen ist.

**Test: Donnerstag, 16.07., Frühdienst, Alarm 05:30** (im Log bestätigt gesetzt:
`System alarm set successfully: Frühschicht at 16.07.2026 05:30`). Auto-Off geplant für 06:00
(+30min, 2 Gruppen).

Zu prüfen:
1. Klingelt es **einmal** statt zweimal? Eine Notification statt zwei?
2. Steht die Uhrzeit vollständig drin („Deine Schicht beginnt um 06:00")?
3. Kommt der Vollbild-Screen **von selbst** hoch? (Nur bei **gesperrtem** Gerät — bei entsperrtem
   Handy zeigt Android absichtlich nur ein Banner. Kein Bug.)
4. Reicht **ein** Stopp?
5. Pausiert ein laufender Podcast und läuft danach weiter?
6. Gehen die Lampen um 06:00 wieder aus?

### 2. Bridge-seitige Zeitpläne

**Idee:** Beim Alarm ist die Bridge nachweislich erreichbar (sonst ginge das Licht nicht an).
**Im selben Atemzug** einen Zeitplan *auf der Bridge* anlegen (`POST /api/<user>/schedules`,
`autodelete: true`) → die Bridge schaltet selbst aus, egal wo das Handy ist. Damit verschwindet
die ganze Fehlerklasse „Handy nicht im Heim-WLAN" für den Auto-Off.

- Die App spricht bereits **V1 lokal** (`/api/<user>/groups/<id>/action`) — keine neue Auth, keine
  Cloud, kein Hue-Entwicklerkonto nötig.
- Signify sagt, V1 werde „langfristig" entfernt (kein Datum). Die App steckt ohnehin komplett auf
  V1 → keine *neue* Schuld.
- Der WorkManager-Retry wird damit vom Haupt- zum Fallback-Mechanismus.
- **Einschränkung:** Das JSON-Format muss gegen die echte Bridge verifiziert werden.
- Zum Testen steht ein **Emulator als Zweitgerät** bereit.

Nicht bewusst hinter den Donnerstag-Test zurückgestellt — kann davor angegangen werden, wenn
sonst nichts drängt.

---

## Bekannt und so gewollt

- **`Logger.business()` loggt auf INFO** → PII (E-Mail, Kalendertitel) landet in Debug-Builds im
  Datei-Log (`Logger.kt:116`). Bewusst: Release-Logs enthalten nur WARN+.
- **Regel speichern navigiert sofort weg** (`HueRuleConfigScreen`: `createRule()` ist
  fire-and-forget, `onSaveComplete()` folgt unmittelbar). Ein Fehler landet dadurch erst auf dem
  `HueSettingsScreen` statt im Formular. Seit v1.10.4 kann die Validierung tatsächlich ablehnen —
  bisher nur theoretisch, weil die UI-Validierung dieselben Bedingungen vorher abfängt. Wird das
  je unangenehm: auf das Result warten, bevor navigiert wird.

---

## Invarianten — nicht versehentlich zurückdrehen

Siehe auch Memory `project_alarm_ux_rebuild.md`.

### Wecker

- **Eine Instanz besitzt den Wecker**: `AlarmSoundService` hält Ton, Vibration, Audio-Fokus und
  die einzige Notification (ID 2002). Channel **stumm**, aber `IMPORTANCE_HIGH` (Pflicht für
  Full-Screen-Intent). Der `AlarmReceiver` darf **keine** eigene Notification posten.
- **Kein `startActivity()` aus dem AlarmReceiver**: AlarmManager-Broadcasts stehen nicht auf der
  Exemption-Liste für Background-Activity-Starts. Einziger Weg: `setFullScreenIntent()`.
- **`_alarmActive = true` VOR `startForeground()`** — sonst schließt sich das Vollbild sofort.
- **Snooze braucht `snoozeAlarmAction(id)`**, nicht `enhancedAlarmAction(id)` — sonst bricht der
  Maintenance-Sync den Snooze ab.
- **`scheduleAutoOffs()` darf kein `cancelAllWorkByTag`** — es plant nur für Zukunft, ein gerade
  gefeuerter Alarm fiele raus. Work-Namen aus der Alarm-**ID**.
- **`AutoOffWorker` darf Fehler nicht als `Result.success()` melden.**
- **`AlarmMaintenanceService`: `stopSelf(startId)`, niemals blankes `stopSelf()`.** Zwei
  überlappende Starts teilen sich `serviceScope`; der Erste, der fertig wird, löst sonst
  `onDestroy()` → `scope.cancel()` aus und reißt den anderen mitten in der Arbeit ab.

### Hue

- **`HueBridgeConnectionManager.initialize()` muss idempotent bleiben.** Zwei Aufrufer ohne
  feststehende Reihenfolge: `CFAlarmApplication.initializeApp()` (asynchron im applicationScope)
  und `HueBridgeRepository.init` (Hauptthread, sobald Hilt das HueViewModel baut). Ohne Wächter
  lief alles doppelt und der SmartScheduler verwarf seine gerade angelegten WorkManager-Jobs.
- **`Result<RuleValidationResult>` hat zwei Ebenen.** `isFailure` heißt „die Prüfung ist
  gescheitert", **nicht** „die Regel ist ungültig" — das steht innen in `isValid`. Beides
  verwechselt hieß: ungültige Regeln wurden gespeichert. Siehe `requireValidRule`.
- **`MIN_RULE_NAME_LENGTH = 1`, nicht mehr.** Die UI verlangt nur `isNotBlank()`; eine reale Regel
  heißt „FS". Eine höhere Schwelle macht bestehende Regeln unbearbeitbar (`updateRule` validiert
  ebenfalls).

### Auth

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.** Für eine Wecker-App ist
  „leer" die gefährlichste Lüge — nicht von „du hast frei" zu unterscheiden.
- **GMS-Token-Cache liegt außerhalb des App-Speichers** und überlebt die Deinstallation. Nur
  `GoogleAuthUtil.clearToken()` räumt ihn ab.
- **`onResult` gehört `OAuth2TokenManager.authorize()`** — es feuert auf jedem Weg genau einmal
  (Sofort-Erfolg, Fehler, Dialog via `handlePermissionResult`). Niemand sonst ruft ihn. Ein
  zweiter Aufruf im `AuthUseCase` startete die Wartung doppelt.
- **`observeTokenLoss()` nimmt nur das NEGATIVE Signal.** `hasValidToken` heißt „`getValidToken()`
  klappt gerade" inkl. Refresh; „Token liegt im Store" ist schwächer und würde das Gate bei einem
  toten, noch nicht verworfenen Token fälschlich aufmachen. `drop(1)` ist Pflicht: die erste
  Emission ist der Ist-Zustand, kein Verlust.
- **`signOutInProgress` nicht wegoptimieren.** Beim Abmelden verwirft die App das Token selbst;
  ohne das Flag stieße `observeTokenLoss()` direkt danach einen Zustimmungsdialog an. `isSignedIn`
  allein reicht **nicht** — die DataStore-Emission trifft asynchron ein, `observeAuthState` ist
  zusätzlich 200ms entprellt.
- **Abmelden heißt: nichts bleibt zurück.** `AuthUseCase.signOut()` verwirft Auth-Daten UND Token
  (inkl. GMS-Cache). `CredentialAuthManager.signOutLocally()` ist nur eine Log-Zeile — sich darauf
  zu verlassen war der Fehler.

### Kalender-Datenfluss

- **`CalendarStateHolder` ist eine Einbahnstraße**: `CalendarViewModel` schreibt hinein, liest nie
  daraus; einziger Leser ist `ShiftViewModel`. Wer Events lädt und nur dorthin schreibt,
  aktualisiert die `CalendarUiState` nicht — und die rendert Home.
- **Laden gehört ausschließlich dem `CalendarViewModel`** (`refreshData(forceRefresh = true)`
  aktualisiert beides und trägt Fehler in den State). Keinen zweiten Ladepfad einbauen — genau der
  hat den stummen Retry erzeugt.
- **Endlosschleifen-Bremse in `MainScreen`** (~Zeile 101): automatisches Nachladen nur bei
  `error == null`. Sonst: Laden scheitert → `isLoading` false → Effect erneut → Liste leer →
  laden … im Sekundentakt gegen die Google-API (real passiert bei 401). Nicht entfernen.

### Compose-Layout

- **`Row(SpaceBetween) { Column { … }; Switch }` braucht `weight(1f)` am Column.** Ohne das nimmt
  der Beschreibungstext die volle Breite und der Schalter landet außerhalb der Karte. Eine feste
  `.width(…dp)` als Pflaster bricht bei schmalem Display oder großer Schrift.
- **`ButtonDefaults.ContentPadding` = 24dp pro Seite.** In schmalen, geteilten Buttons bleibt zu
  wenig für die Schrift, und Compose bricht mitten im Wort. Dafür gibt es
  `ui/components/CompactActionButton.kt` — **nur** für schmale, geteilte Buttons, nicht für
  ganzbreite, wo ein Zweizeiler gewollt ist.
- **Chip-Reihen als `FlowRow`**, nicht `Row` mit `chunked(n)`. `FlowRow` ist in Compose 1.11.4
  stabil (nur die deprecated Überladung mit `overflow` ist `@ExperimentalLayoutApi`) → kein
  `@OptIn` nötig.

---

## Umgebung / Arbeitsweise

- **Gradle läuft in Claudes Umgebung nicht** (Loopback blockiert) → Verifikation durch Inspektion,
  der Nutzer baut lokal. Der Quelltext der Bibliotheken ist trotzdem lesbar:
  `~/.gradle/caches/modules-2/files-2.1/<group>/…-sources.jar` — nachsehen statt raten.
- **„Warnungen plötzlich weg" ist kein Fortschritt.** `org.gradle.configuration-cache=true`
  (`gradle.properties:23`): Die Deprecation-Warnungen entstehen in der Konfigurationsphase. Wird
  der Konfigurations-Cache wiederverwendet, erscheinen sie schlicht nicht neu. Nach jeder Änderung
  an `build.gradle.kts`/`gradle.properties` sind sie wieder da.
- **Die Warnung lügt:** Ihr Vorschlag, `android.builtInKotlin`/`android.newDsl` zu entfernen und
  auf built-in Kotlin zu migrieren, zerlegt das Dreieck aus KSP 2.x, KGP 2.x und AGP 9.x. Beide
  Flags bleiben auf `false`.
- **Debug-Build** schreibt VERBOSE ins Datei-Log (`CFAlarmApplication.kt:72`). Release-Logs
  enthalten **nur WARN+** → erfolgreiche Operationen sind dort unsichtbar. Für Diagnose immer
  einen Debug-Build verlangen.
- Debug-SHA-1 ist in der Google Cloud Console eingetragen (verifiziert 14.07.).
- Getestet wird auf einem echten Gerät **und einem Emulator als Zweitgerät**; Logcat-Auszüge
  kommen vom Nutzer.
