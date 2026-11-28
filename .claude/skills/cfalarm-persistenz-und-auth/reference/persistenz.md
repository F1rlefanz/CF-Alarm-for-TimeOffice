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
- `isPersistenceBlocked()` heisst NUR "der Bestand ist in diesem Prozess unlesbar"
- `saveAlarm()` meldete Erfolg, obwohl nichts geschrieben wurde
- Die Quellen der Tagesleiste: Unbekannt statt leer, je Quelle einzeln (#196)
- Dimmen und „Nicht stören" am Typ: Speicherform, Marker, Migration, Entwurf (Umbau Etappe 3)

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

- **`isPersistenceBlocked()` heißt „der Alarm-Bestand ist in diesem Prozess nicht lesbar" — und
  nichts sonst** (Prüfrunde 8). Es gibt einen ZWEITEN Weg in den reinen Arbeitsspeicher: nicht der
  Init-Load, sondern der SCHREIBweg scheitert (voller Speicher, IOException, beschädigte
  `preferences_pb`). `AlarmRepository.persistToDataStore()` fängt das bewusst — der Alarm wird
  trotzdem armiert und klingelt —, setzt aber die Sperre absichtlich NICHT (der nächste Versuch soll
  wieder schreiben dürfen). Dafür gibt es jetzt den eigenen Merker
  `istLetzterSchreibvorgangGescheitert()`. Die zweite Fixwelle hatte ihn in `isPersistenceBlocked()`
  mit `persistenceBlocked` **verodert** — naheliegend, weil beides „nicht dauerhaft" bedeutet, und
  genau das war der Fehler: `AlarmUseCase.clearInternalAlarms()` deutet dieses Signal als „unlesbar"
  und überspringt dann bei einer ausdrücklichen Abschaltung (Master-Pause, „Automatische Alarme
  aus", `deleteAllAlarms`) bewusst die gesamte `cancelSystemAlarm()`-Schleife. Ein EINZIGER
  fehlgeschlagener Write hätte gereicht: die Master-Pause hätte Repository und Direct-Boot-Spiegel
  geleert und jeden System-Alarm scharf zurückgelassen — die in `CLAUDE.md` verbotene Kombination
  „Räumen ohne Cancellen", und ohne Bestandsliste ist so ein Alarm durch nichts mehr abbrechbar.
  Nach einem geworfenen Write ist der Bestand im Speicher dagegen VOLLSTÄNDIG lesbar, die Schleife
  MUSS also laufen. Deshalb: **wer Dauerhaftigkeit anzeigen will, fragt beide Signale; wer
  entscheidet, ob geräumt werden darf, fragt ausschließlich `isPersistenceBlocked()`.** Und
  allgemein: **wer ein bestehendes Signal um eine zweite Bedeutung erweitert, muss JEDE Leserstelle
  einzeln daraufhin ansehen** — im Zweifel ein eigenes Signal.
- **`saveAlarm()` meldete Erfolg, obwohl nichts geschrieben wurde** (Prüfrunde 8, Befund 11 — gefunden
  beim eigenen Nachlesen im Zwillings-Feld eines widerlegten Befunds). `persistToDataStore()` kehrt
  bei gesperrter Persistenz still zurück und fängt außerdem jede Exception ab; `saveAlarm()` gab
  danach bedingungslos `Result.success` zurück. Der Wecker lag dann nur im Arbeitsspeicher — kein
  Preferences-Eintrag, kein Direct-Boot-Spiegel, nach Prozesstod oder Neustart spurlos weg, und beim
  MANUELLEN Wecker unwiederbringlich (er lässt sich aus keinem Kalender rekonstruieren). Die
  naheliegende Antwort `Result.failure` ist erprobt und verworfen: sie bräche die Zusicherung der
  Sperre („die System-Alarme werden trotzdem gesetzt") und machte den Kalender-Sync schlechter —
  `syncAlarms()` ruft `saveAlarm(...).getOrThrow()` VOR `scheduleSystemAlarm(...)`, ein Wurf ließe
  den Alarm also im Cache stehen, ohne ihn je zu armieren („stummer Wecker MIT Anzeige"). Stattdessen:
  ein WARN direkt an der Stelle (landet im Release-Log), der getrennte Schreibfehler-Merker, und die
  Stellen, die Dauerhaftigkeit wirklich brauchen, fragen NACH dem Speichern nach
  (`AlarmViewModel.createManualAlarm()`, und fuer Ueberspringen wie Tag-Freigabe gemeinsam
  `loescheDauerhaftMitNachfassen()` in `usecase/DauerhaftesLoeschen.kt`).

## Die Quellen der Tagesleiste: Unbekannt statt leer, je Quelle einzeln (#196, November 2026)

Die Tagesleiste der Typ-Seite (Einheit und Dienstzeit: Skill `cfalarm-kalender-und-schichten`)
liest Spannen, Dimm-Regeln, Dimm- und DND-Schalter, Hue-Regeln und Hue-Konfiguration über
`ShiftViewModel.tagesleisteQuellen` (`tagesleiste/TagesleisteQuellen.kt`). Festlegung des Betreuers:

- **Jeder Lesefehler wird `Quelle.Unbekannt`, nie eine leere Liste — und zwar JE QUELLE EINZELN VOR
  dem `combine`** (`fehlerAlsUnbekannt` für werfende Flows, `alsQuelle` für `Result`). `null` heißt
  nur „noch nicht gelesen". Ein einziger Fang HINTER dem `combine` (so macht es `schichtFolgen` mit
  `emit(emptyMap())`) ist hier genau das verbotene Muster und ließe bei einem Fehler alle Spuren
  zugleich ausfallen.
- **`TagesleisteQuellen.stand(spur)` sagt je Spur BEKANNT/UNBEKANNT:** die Spannen hängen an JEDER
  Spur (die Dienstzeit legt den Weckpunkt fest, ein Nachtdienst weckt am Vortag). Dimmen und „Nicht
  stören" hängen seit #219 beide an `typen` (bis dahin hing „Nicht stören" an der Dimm-Quelle nur,
  solange „folgt dem Dimmer" an war).
- **Grenze (belegt, #210), für Dimmen und DND seit #219 erledigt:** Damals lasen Dimm-Regeln
  (`DimRuleRepository.rules`), Dimm-Schalter und DND-Schalter (`safeData`) über Stores, die selbst
  auf leer/aus degradieren und den Fehler nicht weitermelden. Seit #219 liest die Tagesleiste
  Dimmen und „Nicht stören" aus der Konfiguration (`TagesleisteQuellen.typen`) über
  `IShiftUseCase.getCurrentShiftConfig()` — ein `Result`, neu gelesen bei jedem neuen
  `currentShiftConfig`, nie über den Flow `shiftConfig`, der auf Standardwerte degradiert. Ein
  Lesefehler macht BEIDE Spuren `Quelle.Unbekannt` (Test `TagesleisteQuellenTest`). Offen bleibt der
  Hue-Teil: `HueConfigRepository.getConfiguration()` („Bridge eingerichtet") degradiert weiter
  selbst; das holt Etappe 4.
- **Falle `getScheduleRules()`:** fängt `Exception`, also auch `CancellationException`, und verpackt
  sie in `Result.failure` — `alsQuelle` wirft sie weiter.
- **Spannen werden bei ZWEI Anlässen neu gelesen** (Prüfer-Befund): neue Eventliste UND das Ende von
  `triggerAlarmCreationFromConfigUpdate` (`spannenAusloeser`, im `finally`, also auch bei
  gescheitertem Sync — der schreibt die Spannen vor dem Scheitern). Der Sync schreibt die Spannen
  neu, die Eventliste bleibt gleich; ohne Auslöser fand `dienstzeitAus` nach dem Umbenennen eines
  Typs nichts mehr. Der Test geht über den echten Einstieg `updateShiftConfig`, kein Test-Haken.
  **Offen (#211):** der Kalender-Sync im `CalendarViewModel` setzt die Events VOR `syncAlarms()` —
  dafür sieht das `ShiftViewModel` kein Signal.
- **Falle im Test:** `aktualisiereLetztenSchichtStand()` fasst den Span-Store schon nach der ersten
  Event-Emission an (`letzterStand()`); „ohne Abo nichts gelesen" gilt für `spansNow()`, nicht für
  das Lazy. Die Vorlauf-Rechnung (`LichtQuelle.vorlaufMinuten`) greift erst beim Aufruf auf
  `HueRuleUseCase` zu — Abo und Lesen bauen den Hue-Graphen nicht.
- Test: `TagesleisteQuellenTest` (12 Mutationen gemessen am 05.11.2026, alle rot: u. a.
  Hue-Fehlschlag als leere Liste, ein Fang hinter dem `combine`, `CancellationException`
  geschluckt, Spannen-Fehlschlag als leere Liste, kein Neu-Lese-Auslöser nach dem Speichern,
  Auslöser nur im Erfolgszweig des Syncs).

## Dimmen und „Nicht stören" am Typ: Speicherform, Marker, Migration, Entwurf (Umbau Etappe 3, November 2026)

Wie Dimmer und DND die Felder LESEN: Skill `cfalarm-dimmer-und-dnd`, `reference/typ-fenster.md`.
Hier steht, wie sie gespeichert und übertragen werden.

- **Die Fenster am Typ liegen roh** (#213, `model/TypEinstellungen.kt`):
  `TypDimmen.fensterGespeichert: List<JsonElement>` (privat), gedeutet in `fenster`; Leser nehmen `fenster`, Schreiber
  `mitFenster(...)`, nie `copy(fensterGespeichert = ...)`. **Warum:** `DimWindow` trägt
  kotlinx-Enums, und `ShiftConfigRepository.json` hat kein `coerceInputValues`. Ein unbekannter
  Anker (spätere Version, dann Revert) machte sonst die ganze Schicht-Konfiguration `Broken` und mit
  ihr den Wecker-Abgleich; so bleibt das undeutbare Fenster wortgleich gespeichert und dimmt nur
  nicht. Test: `TypEinstellungenModellTest`. Dieselbe Überlegung trägt `DimWindow.startAmVorabend`
  als Feld statt eines neuen Ankers (#227).
- **`ShiftConfig.modellStufe` ist der Migrations-Marker** (#213). Er liegt bewusst im JSON und nicht
  als eigener Schlüssel: ein Revert verliert ihn zusammen mit den neuen Feldern, und die Migration
  läuft dann erneut aus dem Altbestand. Wer eine `ShiftConfig` neu baut statt per `copy()`, übernimmt
  ihn. **Hergang:** Beim Bau von #213 fiel die Stufe beim Zurücksetzen auf 0, weil das Zurücksetzen
  nicht in `ShiftConfig.kt` lebt, sondern in `resetToDefaultsPreservingAutoAlarm`
  (`ShiftConfigScreen.kt`). Seither geht es über `ShiftConfig.aufStandardZurueckgesetzt()`; nur der
  Zweig ohne geladene Konfiguration bleibt bei Stufe 0. Der Test geht über die echte Funktion.
- **Die Migration liest den Altbestand STRIKT** (#216): `DimRuleRepository.regelnOderFehler()`,
  `DimOverlayPrefs.dimmenAnOderFehler()`, `DndPrefs.altbestandOderFehler()`. Über die Anzeige-Wege
  (`rules`, `safeData`), die auf leer bzw. aus degradieren, hätte `TypModellMigration` einen
  unlesbaren Regelbestand als „keine Regeln" an jeden Typ geschrieben und die Stufe gesetzt. Die
  Mutation „Regeln über `getRules()`" macht `TypModellMigrationTest` rot. Prüfreihenfolge und
  Anlässe: Dimmer-Skill.
- **Die Migration übersetzt nur, was noch dem Standard entspricht, je Einstellungs-OBJEKT** (#234).
  Körnung: `TypDimmen`, `TypNichtStoeren` samt Ruf-Cutoff, an der festen Zeile ihr Dimmen und ein
  eingeschaltetes „Solange gedimmt wird". Bei leerem Altbestand
  (`TypEinstellungenUebersetzung.Altbestand.istLeer`: keine Regel, auch keine deaktivierte, beide
  DND-Schalter aus, Cutoff 05:00) setzt sie nur die Stufe. **Hergang:** Tor 7 hält die
  Standard-Konfiguration auf Stufe 0 an. Ein auf der Typ-Seite gespeichertes Fenster hob das Tor auf,
  und `TypModellMigration` ersetzte beim nächsten Anlass Fenster, Schalter und Wärme durch den
  Altbestand (ohne Regel: keine Fenster). Je FELD übersetzt käme das Fenster aus dem fehlenden
  `dim_enabled` ausgeschaltet zurück — deshalb das Objekt als Einheit. Befund aus #221, Test
  `TypModellMigrationTest` „frische Installation …". **Restfall akzeptiert:** eine Eingabe gleich
  dem Standard („Dimmen an" ohne Fenster) gilt als unberührt und wird übersetzt.
- **Der Entwurf der Typ-Seite trägt vom Dimmen nur, was die Seite zeigt** (#221):
  `DimmenEntwurf(an, fenster, farbwaerme)`, nullable in `TypEntwurf`. `null` heißt „kein Stand, es
  gilt das Gespeicherte", nie „keine Fenster". `anwenden()` geht auf den GERADE gespeicherten Typ und
  kodiert Fenster nur neu, wenn sie sich unterscheiden — so bleiben undeutbare und nicht darstellbare
  Fenster beim Speichern stehen. Ein unlesbarer Dimm-Teil im Bundle verwirft den ganzen Entwurf.
  Tests: `TypEntwurfDimmenTest` (Mutationsprobe: `TypDimmen(an = an)` statt `gespeichert.copy` macht
  fünf rot).
- **Die Farbwärme reist seit #224 mit** (`DimmenEntwurf.farbwaerme: FarbwaermeWahl?`): `null` =
  unberührt, `FarbwaermeWahl(null)` = „wie in den Einstellungen". Geschrieben wird sie nur, wenn das
  Blatt „Weitere Optionen" sie geändert hat; die gespeicherte Wahl erneut zu wählen setzt wieder
  „unberührt". Das ersetzt die #221-Aussage, die Farbwärme bleibe beim Speichern immer stehen. Ein
  Bundle ohne den Schlüssel liest sich unverändert (Test `DimmenWeitereOptionenTest`). Wie Dimmen und
  „Nicht stören" im Bundle liegen (ein JSON-Element an achter Stelle): UI-Skill, `typ-seite.md`.
