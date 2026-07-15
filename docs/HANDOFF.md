# Handoff — CF-Alarm for TimeOffice

**Lebendes Dokument.** Erledigtes wird gestrichen, nicht abgehakt — was hier steht, ist offen.
Die Historie steht im Git-Log, nicht hier.

**Stand:** 15.07.2026 · `main` = **v1.10.6 / versionCode 49** · alles gemerged und gepusht.

> **Ungeprüft:** die Regel-Validierung (v1.10.4) — sie greift erst, wenn man eine Regel speichert
> — und der Log-Kleinkram aus v1.10.6 (nur inspiziert, noch kein Build gelaufen). Alles andere ist
> am Gerät bzw. im Log bestätigt.

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

### 3. Startup-Ruckler — ungeklärt

`Skipped 41–48 frames` / `Davey! ~800–890ms` bei jedem Start. **Wichtig:** steht auch nach dem
Beheben der doppelten Bridge-Init noch im Log — die naheliegende Ursache war es also **nicht**.
Nicht mit halben Vermutungen „reparieren"; erst messen.

**Durch Inspektion eingegrenzt (15.07.) — gemessen ist davon nichts:**

- **Abgehakt: Tink ist es nicht.** `TinkEncryptionHelper.aead` ist `by lazy` und wird erst beim
  ersten Token-Zugriff angefasst; der läuft in einer Coroutine. Der Android-Keystore — der teure
  Teil — liegt damit gar nicht im Startpfad.
- **Kandidat 1: WorkManager initialisiert sich selbst, noch vor `Application.onCreate()`.** Es
  gibt weder einen `Configuration.Provider` noch einen Manifest-Eintrag, der den
  `androidx.startup.InitializationProvider` entfernt → der Default-Initializer läuft als
  ContentProvider und zieht dabei seine Room-DB auf dem Hauptthread hoch. Das liegt mitten im
  gemessenen Fenster. Gegenmittel wäre On-Demand-Initialisierung.
- **Kandidat 2: `HueApiClient` wird zweimal gebaut**, jedes Mal mit eigenem OkHttp/Retrofit samt
  TrustManager/SSLContext. Einmal im Konstruktor von `HueBridgeConnectionManager` — ob auf dem
  Hauptthread, ist ein Rennen: entweder `CFAlarmApplication.initializeApp()` (IO-Coroutine) oder
  Hilts Feld-Injection in `MainActivity.onCreate()` (Hauptthread) ist zuerst da. Und einmal in
  `HueBridgeRepository`, das zusätzlich `OfficialHueDiscoveryService` und `HueBridgePinningStore`
  baut — dieser Weg läuft sicher auf dem Hauptthread (Hilt erzeugt es beim HueViewModel).
- **Kandidat 3: `HueBridgeConnectionManager.initialize()` läuft auf dem Hauptthread**
  (aus `HueBridgeRepository.init`) und ruft dort synchron `startSmartHealthMonitoring()` und
  `initializeSmartScheduling()` → `WorkManager.getInstance()` + `enqueueUniquePeriodicWork`.

**Messrezept:** Debug-Build, Android Studio Profiler → *System Trace*, App **kalt** starten
(`adb shell am force-stop …` davor). Interessant ist der Hauptthread zwischen Prozessstart und
erstem Frame. `Davey!` nennt nur die Dauer — der Trace nennt den Verursacher.