---
name: cfalarm-kalender-und-schichten
description: Zusicherungen fuer den Google-Kalender-Datenfluss, die Vollstaendigkeit von Eventlisten, die Schichterkennung ueber Muster und Keywords sowie die externe TimeOffice-Abhaengigkeit der CFAlarm-Wecker-App. Erklaert, warum eine unvollstaendige Eventliste keine Loeschgrundlage ist, wie sich findDefinitionFor und matchesKeywords unterscheiden, und warum ein gescheiterter Konfigurations-Read nie zur leeren Definitionsliste werden darf. Zu verwenden bei Arbeit an CalendarUseCase, CalendarViewModel, CalendarStateHolder, ShiftRecognitionEngine, ShiftConfig, ShiftDefinition, ShiftViewModel oder TimeOfficeHealthHelper — und immer dann, wenn Schichten nicht erkannt werden, Wecker unerwartet verschwinden oder faelschlich als 'Schicht entfernt' gemeldet werden.
---

# Kalender-Datenfluss, Schichterkennung und TimeOffice

Unten stehen die **Kurzregeln** dieses Bereichs — was gilt, und was bei Bruch passiert.
Die wecker-kritische Teilmenge davon steht zusätzlich in `CLAUDE.md` (dort immer geladen, als
Sicherheitsnetz für den Fall, dass dieser Skill nicht anspringt); **alles Übrige steht
ausschließlich hier.** **Reicht die Kurzregel nicht, oder willst du eine davon ändern oder
umgehen: lies vorher die Hergang-Datei.** Dort steht, welcher Bug die Regel erzwungen hat — ohne
das baut man dieselbe Falle in neuer Form nach.

## Hergang und Belege

- `reference/kalender-datenfluss.md` — Laden, Vollstaendigkeit, Lazy-Praefix, Aenderungs-Notification
- `reference/schichterkennung.md` — Musterabgleich, Wortgrenzen, Cache-Epoche, Konfigurations-Reads
- `reference/timeoffice.md` — die externe App, aus der der Dienstplan kommt

---

## Kurzregeln

- **`CalendarStateHolder` ist eine Einbahnstraße**: `CalendarViewModel` schreibt, `ShiftViewModel` liest.
- **Laden gehört ausschließlich dem `CalendarViewModel`** — keinen zweiten Ladepfad einbauen.
- **Eine unvollständige Eventliste ist KEINE Löschgrundlage.** Zwei Quellen der Unvollständigkeit:
  Teilerfolg einzelner Kalender und das Lazy-Präfix (10 Events pro Kalender). **Jeder löschende
  Konsument geht über `getCalendarEventsWithStatus()` und prüft `CalendarFetchOutcome.isComplete`.**
  Der `CalendarStateHolder` trägt `eventsComplete` mit; `clearEvents()` setzt es auf `false`.
  Drei Aufrufer bleiben absichtlich auf `getCalendarEventsWithCache()`, weil sie nichts löschen —
  bitte nicht erneut als Befund melden.
- **Kein Fehler darf als leeres Erfolgsergebnis durchrutschen** — „leer" ist für eine Wecker-App die
  gefährlichste Lüge. Totalausfall wirft; Teilerfolg bleibt Erfolg; Worker und Wartung haben je ein
  eigenes Leerlisten-Gate; `getCurrentSelectedCalendarIds()` liest den DataStore, nicht den `StateFlow`.
- **Endlosschleifen-Bremse im Kalender-`LaunchedEffect`** (`availableCalendars.isEmpty() && error == null`)
  — nicht entfernen.
- **Der Collector der Kalenderauswahl nimmt sich wieder auf (`retryWhen`)**, statt beim ersten
  Upstream-Fehler dauerhaft zu enden.
- **Ganztägige Termine gehen durch `CalendarEventConverter`** und setzen `isAllDay`;
  `calculateAlarmTime()` überspringt dann die Nachtschicht-Vortags-Heuristik.
- **Nachgeladen wird immer ein PRÄFIX, nie eine Seite ab Offset** (`offset = 0`, `maxEvents =
  angezeigt + limit`); `mergeMoreEvents()` dedupliziert und sortiert defensiv.
- **`loadEventsForSelectedCalendars()` braucht einen Generation-Counter**, kein In-Flight-Flag — und
  die Prüfung muss VOR JEDEM Schreiben stehen, auch vor dem ersten `isLoading = true`.
- **Neue Properties in ViewModels mit `init{}` gehören VOR den `init{}`-Block.** Kotlin initialisiert
  in Textreihenfolge, und ein `StateFlow`-Collector feuert synchron während der Konstruktion —
  reale `NullPointerException`, die 329 grüne Tests nicht gefangen haben.
- **Schicht-Änderungs-Notification lebt INNERHALB von `syncAlarms()`**, nicht bei dessen Aufrufern;
  alle drei Notifier-Aufrufe in eigenem `try/catch`. Der allererste Sync flutet nicht
  (`isFirstSync`), `notifyUpdated()` hat eine eigene Schwelle (≥10 min oder Name geändert).
- **Pre-Alarm-Refresh**: pro Alarm ein WorkManager-Job 3 h vorher (max. 14 Tage, max. 10 Jobs).
  Schließt die Lücke NICHT — echtes Push ist auf einem Android-Gerät nicht möglich.

## Schichterkennung — Kurzregeln

- **Zwei verschiedene Funktionen, zwei verschiedene Regeln** — die Verwechslung hat zweimal Wecker gekostet:
  - **`findDefinitionFor(shiftName)`** ordnet einem BESTEHENDEN Alarm eine Definition zu: exakter Name
    → exaktes Keyword → `contains` ohne Wortgrenzen, dort nur Keywords ab `MIN_FUZZY_KEYWORD_LENGTH = 2`.
  - **`matchesKeywords(eventTitle)`** erkennt Schichten in KALENDERTITELN, mit **Wortgrenzen**.
- **Wortgrenzen über Unicode-Kategorien, NICHT `\b`** — Javas `\b` ist ASCII-basiert und invertiert
  die Semantik bei Umlauten/`ß`. Konstanten auf **Dateiebene** (`ShiftDefinition` ist `@Serializable`).
- **Die einbuchstabigen Standard-Keywords „F"/"S"/"N" gehören in die Vorgaben** — ohne sie sank die
  Erkennung am echten Feed von 4 auf 1 Schicht. Restrisiko bewusst akzeptiert und testlich festgeschrieben.
- **Jede Standard-Definition hat neben dem Stationskürzel ein generisches, mehrbuchstabiges Muster.**
- **`ShiftDefinition.isEnabled` wird in `performRecognition()` respektiert** — bewusst NICHT in
  `findDefinitionFor()`.
- **Ein gescheiterter Konfigurations-Read darf NIE zur leeren Definitionsliste werden** (`getOrThrow()`).
- **Eine defekte Schicht-Konfiguration erfährt der Nutzer nur über das Log.** Rohdaten liegen als
  `shift_config_broken`, der Sync wird ausgelassen, bestehende Alarme bleiben — ein sichtbarer
  Hinweis fehlt noch, bewusst offengelassen.
- **Kein stiller Default-Überschreiber der Schicht-Konfiguration** — alle drei Fallbacks sind entfernt.
  Der bewusste Weg zum Default heißt `resetToDefaults()` und gehört dem Nutzer.
- **„Auf Standardwerte zurücksetzen" rührt `autoAlarmEnabled` nicht an.**
- **`ShiftRecognitionEngine`: EIN unveränderliches Cache-Objekt hinter Volatile-Referenz, Prüfung UND
  Veröffentlichung hinter `recognitionMutex`, PLUS eine Epochen-Kennung** (der Mutex allein reicht
  nicht — `clearRecognitionCache()` läuft synchron).
- **`ShiftViewModel` beobachtet `IShiftUseCase.shiftConfig`** und zieht Anzeige, Erkennung UND Alarme
  nach; eigene Änderungen per Gleichheitsvergleich übersprungen.
- **Geraten wird nicht mehr — vorgeschlagen wird** (`ShiftCodeSuggester`). Die App ordnet NICHTS selbst zu.
- **`ShiftUseCase.add/update/deleteShiftDefinition` sind ENTFERNT** — sie speicherten, ohne die
  System-Alarme anzufassen. Einziger richtiger Weg: `ShiftViewModel.updateShiftConfig(config)`.
- **`withCodeAssignedTo()` macht DREI Dinge zusammen**: Muster ergänzen, Zieldefinition aktivieren,
  Kürzel bei allen anderen entfernen. Jedes einzeln wäre wirkungslos oder gefährlich.
- **Fünf bekannte `syncAlarms()`-Aufrufer**; ein sechster erbt das Master-Pause-Gating automatisch,
  **muss sich aber selbst um die Vollständigkeit seiner Eventliste kümmern**.

## TimeOffice — Kurzregeln

- **CFAlarms gesamte Funktion hängt an einer Kette außerhalb dieser App** — TimeOffice
  (`de.pradtke.timeoffice`) schreibt den Dienstplan in einen eigenen Google-Kalender.
- **Es gibt KEINE öffentliche API für den Hibernation-Status einer ANDEREN App.** Nur die
  Akku-Ausnahme ist für fremde Pakete prüfbar. Die Karte täuscht deshalb keinen Status vor.
- **Kein `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`-Dialog für TimeOffice** — immer der Weg über
  dessen App-Info-Seite.
- **`<queries>` im Manifest ist Pflicht** für `isInstalled()` (Package-Visibility ab Android 11).
- **Kein Unit-Test für `TimeOfficeHealthHelper`** — bewusst, gleiche Konvention wie die
  Schwester-Helper (dünne Wrapper ohne eigene Logik).
- **Das Onboarding-Gate hängt an `handleAuthenticationSuccess()`, nicht nur an `proceedPastGates()`** —
  sonst sehen Bestandsnutzer den Prompt nie automatisch.
