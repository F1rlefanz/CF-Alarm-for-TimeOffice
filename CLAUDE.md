# CLAUDE.md

Leitfaden für Claude Code (claude.ai/code) in diesem Repository.

## Wegweiser: die Zusicherungen liegen in Skills

Diese Datei enthält nur die Regeln, deren Bruch **einen stummen oder verwaisten Wecker** erzeugt.
Alles Übrige — die vollständige Regelliste je Bereich **und** der Hergang dazu (welcher Bug sie
erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde) — liegt in
Projekt-Skills unter `.claude/skills/`. Deren Beschreibungen sind immer im Kontext, ihr Inhalt lädt
erst bei Bedarf.

| Skill | Zuständig für |
|---|---|
| `cfalarm-wecker-und-boot` | Weckerauslösung, Vollbild, Snooze, Boot, Direct Boot, 6h-Wartung, Master-Pause |
| `cfalarm-kalender-und-schichten` | Kalender-Datenfluss, Eventlisten-Vollständigkeit, Schichterkennung, TimeOffice |
| `cfalarm-dimmer-und-dnd` | Schicht-Dimmer und „Nicht stören" |
| `cfalarm-hue` | Philips Hue: API-Semantik, Bridge, Regeln, Vorschau |
| `cfalarm-persistenz-und-auth` | DataStore, Token-Rotation, Fehlerbehandlung, Gerätewechsel/Export |
| `cfalarm-ui-und-navigation` | Compose-Layout, Nutzertexte, Zurück-Verhalten |
| `cfalarm-ui-pruefen` | UI-Abnahme am Emulator gegen den Prototyp des Umbaus (`entwurf/umbau-schichttyp/`) |
| `cfalarm-bauen-und-testen` | Gradle-Eigenheiten, Emulator, Gerätetests |
| `cfalarm-release-und-changelog` | Versionsbump, Changelog, Release-Ablauf |
| `cfalarm-altlasten-abtragen` | Toten Code, überholte Kommentare, Duplikate entfernen — und was dabei verworfen ist |
| `cfalarm-arbeit-abschliessen` | Was Fertigsein heißt — und was die Schleuse NICHT prüfen kann |

**Vor einer Änderung in einem dieser Bereiche den zugehörigen Skill lesen.** Die Regel unten sagt
*was*, der Skill sagt *warum* — und ohne das Warum baut man dieselbe Falle in neuer Form nach.

**Pflege:** Neue Erkenntnisse mit Hergang gehören in den Skill, hierher nur die normative Zeile —
und auch die nur, wenn ihr Bruch den Wecker kostet.

**Diese Datei hat ein Budget, und es wird gemessen:** `tools/doku/pruefe_budget.py` warnt ab 22 k,
lässt die CI ab 26 k scheitern und meldet sich per `SessionStart`-Hook (Hergang im Kopf des
Skripts). **Wenn die Meldung kommt, ist Verschieben in einen Skill die Antwort, nicht das Anheben
der Schwelle.** Dort kostet Wissen erst beim Lesen etwas; `reference/*.md` darf deshalb wachsen.

**Ein `: ` in einer `description` MUSS gequotet werden** — sonst endet der YAML-Skalar am ersten
Doppelpunkt, das Frontmatter wird unlesbar, und der Skill existiert zwar, triggert aber praktisch
nicht mehr. Zwei Netze fangen das: `tools/skills/pruefe_skills.py` in der CI und ein
`PostToolUse`-Hook; Hergang und Betriebsarten stehen im Kopf des Skripts. **Ein neu angelegtes
Skill-Verzeichnis lädt erst nach `/reload-skills`** — Änderungen an bestehenden `SKILL.md` dagegen
sofort.

## Git & GitHub Workflow

Es gilt der globale Default aus `~/.claude/CLAUDE.md`. Projekt-spezifisch:

- Branch-Präfixe: `feature/<kebab-case>`, `fix/<kebab-case>`, `chore/<kebab-case>`.
- **Mehrere Claude-Sessions arbeiten parallel an diesem Repo** (lokal **und** Cloud-Sessions auf
  `claude/*`-Branches, die eigenständig nach `origin/main` mergen). Deshalb: **immer `git fetch` +
  Divergenz prüfen, bevor** auf `main` gebumpt/committet/gepusht wird; bei Divergenz mergen statt
  force-push. `versionCode` muss höher als der **höchste je vergebene** sein.
- **Im geteilten Arbeitsbaum gezielt stagen**: `git status` unmittelbar vor dem Commit, nie
  `git add .` / `commit -a`, kein `git stash`.
- **Es gibt keine Handoff-Datei mehr, und es soll keine neue geben.** Der Projekt-STAND wird
  abgeleitet (`tools/sitzungsstart.py`: Branch, Arbeitsbaum, Version, Testzahl **mit Alter**,
  Abstand zu `origin/main`) — von Hand gepflegt veraltete er lautlos (Hergang im Kopf des Skripts).
- **Offene Punkte liegen an zwei Orten; das Kriterium ist, ob es öffentlich stehen darf.** Das Repo
  ist öffentlich und bleibt es (GitHub Pages liefert aus `main` `/docs` die Datenschutz-URL für die
  OAuth-Verifizierung). Deshalb: **belegte, harmlose Aufräumarbeit als GitHub Issue** (so seit
  24.08.2026), **unbelegte Fehlerhypothesen über eine Wecker-App im Play Store
  ins Memory `project_offene_punkte`** — die gehören nicht in ein öffentliches Issue. Die frühere
  Pauschalregel „keine Issues" war zu grob. In eine Datei im Repo gehören sie weiterhin nicht.
  **Vor dem Anlegen BEIDE Orte prüfen** (`gh issue list`): der Sessionstart-Hook zeigt nur die
  Memory-Überschriften und kennt die Issues nicht — wer nur ihm glaubt, legt Dubletten an.
- **Ein Push auf `main`, der den `versionCode` erhöht, LIEFERT AUS.**
  `.github/workflows/veroeffentlichen.yml` lädt ihn ohne weiteres Zutun in den **internen**
  Play-Track, zu echten Testern. **Der Bump ist damit die Veröffentlichungsentscheidung**, nicht
  bloß eine Nummer: ein grüner Build ist in diesem Projekt nachweislich keine auslieferbare
  Version (05.08.2026, Crash-on-Launch nach grünen Tests). Der Produktions-Track bleibt Handarbeit.
- **Reine Wartung bumpt sich selbst** (`sammel-release.yml`, täglich 09:00 UTC): liegen in `main`
  ausschließlich Dependabot-Commits unausgeliefert, startet der Sammel-Release die
  App auf einem Emulator, bumpt den Patch, schreibt den Changelog und liefert aus. **Ein einziger
  inhaltlicher Commit hält das an** — der gehört in einen Changelog-Eintrag in Nutzersprache und
  vor dem Ausliefern angesehen. Die Grenze zieht `tools/release/sammel_release.py`; wer sie
  aufweicht, veröffentlicht ungeprüfte Inhalte an echte Tester. Ablauf im Skill
  `cfalarm-release-und-changelog`.
- **Vor `git merge`/`git push` läuft die Schleuse** (`tools/schleuse/pruefe_schleuse.py`,
  `PreToolUse`-Hook) und prüft mechanisch: Geheimnisse, Skills, Doku-Budget, Changelog-Seite,
  Code-Invarianten, Kerntests, Aufraeum-Reste, Tests (inkl. androidTest-Uebersetzung), Lint — beim
  Push von `main` zusätzlich Changelog-Eintrag, Bump und `versionCode` gegen `origin/main`.
  **Ein grüner Lauf ist die untere Schranke, nicht das Ziel**;
  was sie nicht prüfen kann, steht im Skill `cfalarm-arbeit-abschliessen`. Notausgang für den Fall,
  dass die Schleuse selbst kaputt ist: `CFALARM_SCHLEUSE=aus`.

## Build & Development Commands

```bash
./gradlew assembleDebug
./gradlew assembleRelease          # R8/Minify AN; braucht NETZ
./gradlew test
./gradlew testDebugUnitTest --tests "…AlarmSchedulerTest"   # NICHT `test --tests`
./gradlew lint
./gradlew installDebug
./gradlew connectedDebugAndroidTest   # nicht --offline, nicht bei zwei Geräten
```

- **`assembleRelease` braucht Netz**: die nur mit Minify laufende Task
  `produceReleaseComposeMapping` zieht eine Abhängigkeit, die nicht im Offline-Cache liegt;
  `--offline` scheitert mit dem irreführenden „Configuration cache state could not be cached".
  Signiert wird nur, wenn `keystore.properties` ODER `KEYSTORE_PASSWORD` da ist — sonst entsteht
  `app-release-unsigned.apk` (Absicht, siehe CI im Skill `cfalarm-bauen-und-testen`).
- **`connectedDebugAndroidTest`**: nicht `--offline`, Gerät MUSS wach sein, und die Task
  **deinstalliert die App danach** (ein eingerichteter Emulator-Zustand ist hinterher weg).
- **Bei zwei angesteckten Geräten** läuft `installDebug`/`connectedDebugAndroidTest` auch auf dem
  produktiven Fairphone. Sicherer Weg über `assembleDebug` + `adb -s emulator-5554 …` — Details im
  Skill `cfalarm-bauen-und-testen`.

## Prerequisites

`keystore.properties` im Projekt-Root:

```
googleWebClientId=<client-id>.apps.googleusercontent.com
storeFile=../cf-alarm-release.keystore
storePassword=<password>
keyAlias=cf-alarm-key
keyPassword=<password>
```

Fehlt `googleWebClientId`, wirft der Build eine `GradleException` (kein Fallback, Absicht).

## Architecture

Clean Architecture + MVVM mit Hilt DI.

```
ui/screens/ + ui/components/          ← Jetpack Compose (Material3)
viewmodel/                            ← @HiltViewModel, StateFlow-based UI state
usecase/ + usecase/interfaces/        ← Business logic (interface-segregated)
repository/ + repository/interfaces/  ← Data access contracts
data/ + auth/ + calendar/ + hue/ + shift/ ← Konkrete Implementierungen
service/ + alarm/                     ← Android-Hintergrundkomponenten
di/modules/                           ← Hilt-Module
```

Hilt-Module, Auth, Alarm, Schichterkennung, Hue, Navigation und Shared State im Detail:
`ARCHITECTURE.md`.

## Key Constraints

- `AD_ID` ist bewusst blockiert (`maxSdkVersion="0"`) — nicht reaktivieren.
- `USE_EXACT_ALARM` und `USE_FULL_SCREEN_INTENT` sind Kernberechtigungen.
- Die DataStore-Namensräume bleiben getrennt (settings / hue / tokens) — nie zusammenlegen.
- `TinkEncryptionHelper.getInstance()` muss ein Singleton bleiben und ist der **einzige** Zugriff.
  Kein Hilt-Provider dafür (zöge den Keyset-Read in `directBootAware`-Komponenten).
- **Es gibt bewusst keinen `@TokenDataStore`-Qualifier** — nicht zurückholen, auch wenn der
  Feldname `tokenDataStore` danach aussieht. Hergang: Skill `cfalarm-persistenz-und-auth`.
- **Jeder `preferencesDataStore` braucht einen `corruptionHandler`.** Ohne ihn blockiert eine
  beschädigte `preferences_pb` dauerhaft auch das SCHREIBEN, reboot-fest.
- **R8/Minify ist AN** (seit v1.23.0; APK 19,8 → 10,9 MB). `-dontshrink`/`-dontoptimize` müssen in
  `proguard-rules.pro` auskommentiert bleiben. **Obfuskation ist seit 1.46.0 AN** (Play-Vorgabe ab
  Feb 2027); Rückübersetzen per R8-Retrace, **kein `mappingFile:` am Upload** (Skill Bauen).
- `minSdk = 26`, `compileSdk = 37`, `targetSdk = 37`; Java 17 mit core library desugaring.

---

## Grundregeln

- **Kerntests sind beim Aufräumen tabu.** Die Liste steht in `tools/kerntests/kerntests.txt`,
  markiert mit `@Category(Kerntest::class)`; nicht löschen, zusammenlegen oder still anpassen.
  Hergang und Ausnahme (bewusste Verhaltensänderung): Skill `cfalarm-altlasten-abtragen`.
- **Eine Funktion ohne Bedienoberfläche gibt es für den Nutzer nicht.** Wer eine Fähigkeit einbaut,
  baut die Stelle mit, an der man sie **sieht**, **auslöst** und ihren **Zustand abliest**.
- **Der Sweep über frisch geschriebenem Code beweist wenig** — jeder Fix schafft neue Prüffläche.
  Aussagekräftig ist nur eine Runde über **unverändertem** Code. Abbruchbedingung des
  Härtungsprogramms: zwei aufeinanderfolgende solche Runden ohne neuen bestätigten Befund.
  **Sie ist bis heute NICHT erfüllt, der Zähler steht bei null.** Rohbefund- und
  Bestätigungszahlen jeder Runde gehören ins Protokoll, sonst zählt die Runde nicht mit.
- **Refutation-Voting ist kein Orakel — in beide Richtungen.** Ein „widerlegt" ist ein Hinweis, kein
  Freispruch. Spiegelt ein Befund ein bereits bestätigtes Muster (Zwillinge in Schwesterfunktionen),
  immer selbst am Code nachsehen — genau so wurde schon ein zu Unrecht verworfener Befund gefunden.
- **Am Ende eines Arbeitsdurchgangs aufräumen**: toter Code, nicht mehr aufgerufene Funktionen,
  überholte Kommentare, jetzt redundante Abstraktionen. Und: widerlegte Notizen **korrigieren**,
  nicht danebenschreiben.

---

## Die Regeln, deren Bruch den Wecker kostet

Was hier steht, gilt immer. Je Regel steht hier nur der Kernsatz; der volle Wortlaut mit
Begründung liegt im genannten Skill unter `reference/pflichtregeln-aus-claude-md.md` —
**vor einer Änderung an einer Regel dort lesen.**
Die vollständigen Regellisten je Bereich und ihr Hergang stehen in den Skills oben.

### Alarme anlegen, ändern, löschen

Wortlaut: Skill `cfalarm-wecker-und-boot`.

- **Löschen heißt IMMER: erst `cancelSystemAlarm()`, dann `deleteAlarm()`.**
- **`clearInternalAlarms()` fragt ZUERST `isPersistenceBlocked()` und scheitert laut.** Räumen ohne
  Cancellen ist die gefährliche Kombination.
- **Die datengetriebenen Räumzweige von `syncAlarms()` schonen MANUELLE Alarme** (`keepManualAlarms
  = true`)
- **Der Delta-Sync hat pro Event ein eigenes `try/catch`, das `CancellationException` weiterwirft**
- **Die Wecker-Identitaet haengt NICHT allein an der Kalender-Kennung.**
- **Die Skip-Erkennung nimmt den WECKZEITPUNKT als Anker, nicht nur die id** (in beiden Gates, auch
  im Backstop).
- **Verstrichene Weckzeit ist KEINE entfernte Schicht** (`expiredEventIds`).
- **Alle `setAlarmClock()`-Aufrufstellen fangen die entzogene Exact-Alarm-Berechtigung**
  (`setExactOrInexact`: try/catch + inexakter Fallback).
- **Das Skip-Flag läuft zeitbasiert ab, nicht per ID-Match**
- **`ShiftConfig.autoAlarmEnabled = false` ist eine ECHTE, sofortige Pause**, kein stilles `return`.
- **Ein freigegebener Tag („Tag freigeben") braucht Gate UND Backstop — und nimmt MANUELLE Wecker
  aus.** Der Anker ist überall die Weckzeit (vier Stellen).
- **Freigaben: Hand + Krank laut Kalender, `freieTage` liefert die VEREINIGUNG** (#142) - jeder
  Leser liest nur sie; den Kalender-Schlüssel schreibt NUR `syncAlarms()`.

### Wecken, Vollbild, Snooze

Wortlaut: Skill `cfalarm-wecker-und-boot`.

- **`_alarmActive = true` VOR `startForeground()`**
- **Kein `startActivity()` aus dem `AlarmReceiver`**
- **Eine Instanz besitzt den Wecker**: `AlarmSoundService` hält Ton, Vibration und die einzige
  Wecker-Notification (ID 2002).
- **`AlarmSoundService`: `stopSelf(startId)` und `START_REDELIVER_INTENT`**
- **Vollbild-Dismiss und -Snooze teilen eine Einweg-Sperre** (`OneShotAlarmHandoff.claim()`).
- **In `onNewIntent()` wird ERARBEITETER Zustand nur bei einem ANDEREN Weckvorgang verworfen.**
- **`AlarmFullScreenActivity` braucht `onNewIntent()` mit `setIntent()`**
  (`launchMode="singleTask"`).
- **Snooze braucht `snoozeAlarmAction(id)`**, nicht `enhancedAlarmAction(id)`.
- **Ein AUFGEGEBENER Armierungsversuch hinterlaesst nichts Scharfes.**
- **Ein schwebender Snooze muss einen Reboot überleben** (`restorePendingSnoozes()`), und beide
  Anlässe armieren über dasselbe `armSnooze()`
- **Der Snooze-Merker ist serialisiert und schreibt mit `commit()`**, nicht `apply()`.
- **Der Schlummer-Read in `AlarmReceiver` MUSS hinter `userUnlocked` gegated sein**
- **Stille Schicht (`isSilent`) gated NUR die Auslösung.** Fail-safe: Lookup-Fehler = NICHT still.
- **„Deine Schicht beginnt um" zeigt `AlarmInfo.shiftStartTime`, nicht `triggerTime`.**
- **Wer die Wichtigkeit eines Notification-Kanals anhebt, braucht eine NEUE Kanal-ID.**
- **Die Schlummer-Beschriftung kommt aus derselben Variablen wie `scheduleSnooze()`**
- **Blockierte Benachrichtigungen sind ein Wecker ohne Oberfläche**
- **`visibilitySnapshot()` ist Diagnostik, die im Release-Log landen MUSS** (WARN).
- **Der Weckton startet sofort, nie im verzoegerten Vorweck-Zweig.**
- **Die Vorweck-Bedingung liest NUR Systemzustand, nie etwas Gespeichertes**

### Hintergrundketten und Boot

Wortlaut: Skill `cfalarm-wecker-und-boot`.

- **Kein Hintergrund-Paket importiert `ui.` oder `viewmodel.`** (nach unten greifen ist frei, nur
  die Gegenrichtung ist gesperrt).
- **NICHTS am Application-Graphen darf WorkManager oder CE-Storage beim BAUEN anfassen.**
- **Kein `getSharedPreferences()`/CE-Zugriff in einem Property-Initializer** einer Klasse am
  Application-Graphen.
- **Die 6h-Wartungskette hat GENAU einen Planer** (`scheduleNext()`, ein Request-Code).
- **Deren `finally`-Block läuft in `withContext(NonCancellable)`**
- **`AlarmMaintenanceService`: `stopSelf(startId)`**, niemals blankes `stopSelf()`.
- **`AlarmMaintenanceService.start()` fängt den abgelehnten Vordergrund-Start selbst**
- **Die 6h-Wartung MUSS Änderungen und Streichungen sehen können** und synchronisiert **immer**,
  sobald Events vorliegen.
- **`TimezoneChangeReceiver` startet die Wartung mit `forceSync=true`**
- **Die Hue-Regelausführung im `AlarmReceiver` ist gedeckelt** (`HUE_EXECUTION_BUDGET_MS = 45 s`).
- **Die stündliche Rufbereitschafts-Abfrage (`RufbereitschaftAbfrage`) ist eine EIGENE Kette**

### Master-Pause

Wortlaut: Skill `cfalarm-wecker-und-boot`.

- **`syncAlarms()` hat einen zentralen Master-Pause-Backstop**
- **Denselben Backstop haben `DimScheduleUseCase.enable()` und `DndScheduleUseCase.enable()`.**
  `disable()` bleibt ungegatet, sonst kommt `pause()` nicht mehr durch.
- **`pause()`/`resume()` laufen in `withContext(NonCancellable)`**
- **Der Pausen-Spiegel wird beim App-Start mit der CE-Wahrheit abgeglichen.**

### Kalender und Schichterkennung

Wortlaut: Skill `cfalarm-kalender-und-schichten`.

- **Eine unvollständige Eventliste ist KEINE Löschgrundlage.**
- **Ein Zustand, der den Alarm-Sync DAUERHAFT anhält, muss sichtbar sein.**
- **Kein Fehler darf als leeres Erfolgsergebnis durchrutschen**
- **Ein gescheiterter Konfigurations-Read darf NIE zur leeren Definitionsliste werden**
  (`getOrThrow()`), und es gibt **keinen stillen Default-Überschreiber**.
- **`ShiftRecognitionEngine`: ein unveränderliches Cache-Objekt, Prüfung UND Veröffentlichung hinter
  dem Mutex, PLUS Epochen-Kennung.**
- **`findDefinitionFor` und `matchesKeywords` nicht verwechseln**
- **Wer einen Schichtnamen PERSISTENT speichert, traegt ihn in den Umbenennungs-Nachzug ein.**
- **`CalendarStateHolder` ist eine Einbahnstraße**, und Laden gehört ausschließlich dem
  `CalendarViewModel`.
- **`loadEventsForSelectedCalendars()` braucht einen Generation-Counter**
- **Neue Properties in ViewModels mit `init{}` gehören VOR den `init{}`-Block**

### Persistenz

Wortlaut: Skill `cfalarm-persistenz-und-auth`.

- **`isPersistenceBlocked()` heisst „der Bestand ist unlesbar", NICHT „der letzte Schreibvorgang
  ging schief".**
- **Stille Degradierung darf nie zur Schreibwahrheit werden.**
- **Ein CE-DataStore-Read VOR der ersten Entsperrung wirft NICHT — er liefert still leere
  Preferences** und meldet Erfolg.
- **Der Direct-Boot-Spiegel wird bei JEDEM erfolgreichen Load abgeglichen.**
- **Die REIHENFOLGE von `.catch` und `.map` in einem Preferences-Flow ist tragend**
- **Die RICHTUNG der Degradation ist jeweils bewusst gewählt**: Master-Pause → `false` (nicht
  pausiert), Dimmer → aus, Onboarding-Hinweise → werden gezeigt.

### Auth

Wortlaut: Skill `cfalarm-persistenz-und-auth`.

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.**
- **`refresh()` prüft den NEUEN Token gegen die ID des ALTEN**
  (`storedToken.validateRotation(currentToken.rotationId)`)
- **Eine frische Neu-Autorisierung ist KEIN Kettenbruch.**
- **`DataStoreTokenRepository.observe()` nutzt `retryWhen`, und der Fehlerfall emittiert NICHTS**

### Dimmer, DND, Hue

Wortlaut: Skill `cfalarm-dimmer-und-dnd` / `cfalarm-hue`.

- **Es gibt GENAU EINE Dimm-Fenster-Quelle: die Regeln** (seit v1.34.0, ein Schalter `dim_enabled`).
- **Leere Fensterliste = Unterdrückung dieser Nacht**, NICHT „keine Regel".
- **Die Tick-Kette darf nicht abreißen**
- **Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen**
- **Jeder `DimOverlayPrefs`-Setter MUSS direkt danach `DimScheduleUseCase.enable()` rufen**,
  unentprellt.
- **Die Dienstzeit-Fenster kommen aus `ShiftSpanStore`, nicht aus dem Alarm-Bestand**
- **Die V1-Hue-API antwortet auch bei ABLEHNUNG mit HTTP 200**
- **Ein Fehlschlag der Bridge darf nicht zur leeren Liste degradieren**, und die Subnetz-Prüfung ist
  ein HINWEIS auf das Timeout, **niemals ein Veto**.
- **`cleanup()` auf Prozess-Singletons cancelt NUR Kinder** (`cancelChildren()`), nie den Scope.
- **`healthCheckScope` braucht einen `CoroutineExceptionHandler`**

### Navigation

Wortlaut: Skill `cfalarm-ui-und-navigation`.

- **Zurück gehört dem `BackHandler` in `MainScreen`**
- **Die vier Onboarding-Gates sind nicht optional**
