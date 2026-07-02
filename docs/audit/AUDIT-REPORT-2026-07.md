# Release-Reife-Audit „CF Alarm for TimeOffice“ — Phase-1-Bericht

**Datum:** 2026-07-02 · **Version im Audit:** 1.7.0 (versionCode 32) · **Branch:** `main`
**Auftrag:** Schonungslose Release-Reife-Prüfung vor dem öffentlichen Test (siehe `AUDIT-PROMPT-Fable5.md`)
**Status:** Phase 2 — Umsetzung läuft. Branch `fix/audit-gate0-alarm-reliability` (lokaler Build ausstehend; Gradle läuft nicht in der Autorenumgebung).

> **Lebendes Dokument.** Erledigte Findings sind zu abgehakten Einzeilern mit Commit-Ref kollabiert; der Detailtext liegt in der Git-Historie. Legende: ✅ behoben (im Build zu verifizieren) · 🔧 teilweise/in Arbeit · ⬜ offen.
>
> **Fortschritt Gate 0:** P0 **3/5 behoben** — ✅ P0-1, P0-3, P0-4 · ⬜ P0-2 (Direct Boot) + P0-5 (Recovery→WorkManager) folgen als Batch 2.
> Kritische P1 behoben: ✅ Weckton-onStop (+ Stop-Button), ✅ manuelle-Alarme/deleteAll, ✅ Boot-Recovery-Fetch-Fehler, ✅ ungenutzte Permissions.
> Alle Fixes durch adversariale Worker-Review geprüft — 1 Regressionsrisiko gefunden und im selben Batch behoben (Stop-Button).

---

## 0. Methodik & Belastbarkeit dieses Berichts

- **Vorgehen:** Orchestriertes Audit mit 6 parallelen Domänen-Auditoren (Alarm-Verlässlichkeit, Nebenläufigkeit/Prozess-Tod, Sicherheit/Datenschutz, Architektur, UI/UX/Onboarding, Infrastruktur/Release). Jeder Auditor hat seine Kern-Dateien vollständig gelesen und Querbezüge per Grep geprüft. Ergebnis: **85 Findings** (5× P0, 18× P1, 37× P2, 25× P3) plus Stärken-Inventar je Bereich.
- **⚠️ Einschränkung — adversariale Verifikationsstufe ausgefallen:** Die geplante zweite Stufe (jeder P0/P1-Befund wird von einem unabhängigen Prüf-Agenten zu widerlegen versucht) ist **komplett fehlgeschlagen** — Grund: *monatliches Ausgabelimit erreicht* (`You've hit your monthly spend limit`). Die Domänen-Audits selbst waren zu diesem Zeitpunkt bereits fertig und sind vollwertig; es fehlt die maschinelle Gegenprüfung.
- **Kompensation:** Ich habe die **5 P0 und die folgenschwersten P1 selbst am Code nachverifiziert** (Read/Grep im Hauptprozess). Ergebnis der Stichprobe: **0 Fehlbefunde** — jeder geprüfte kritische Befund hält stand. Verifikationsstatus ist pro Finding markiert:
  - ✅ **selbst verifiziert** (von mir am Code bestätigt)
  - ⚪ **auditor-berichtet** (plausibel belegt, aber nicht gegengeprüft — vor Umsetzung im Zweifel kurz am Code bestätigen)
- **Gradle-Hinweis:** In dieser Umgebung läuft kein Gradle (Loopback blockiert). Alle Aussagen sind statisch. Reboot-/Update-/DND-Szenarien müssen auf echter Hardware gegengetestet werden — das ist beim Kernbefund (Boot-Recovery) ausdrücklich einzuplanen.

---

## 1. Gesamturteil

**Die App ist im Kern gesünder, als ihr Ruf als gewachsenes Solo-Projekt vermuten lässt — aber sie ist NICHT bereit für einen öffentlichen Test.** Der Grund ist präzise lokalisierbar und nicht struktureller Natur:

- Der **Klingel-Pfad ab gesetztem Alarm** (`setAlarmClock` → `AlarmReceiver` → Foreground-`AlarmSoundService` → Full-Screen-UI) ist **überdurchschnittlich solide** gebaut: richtige API-Wahl, Doze-fest, ANR-sicher, entkoppelter Ton mit Race-Guard, ernsthafte OEM-/Batterie-Nutzerführung.
- Die **Wiederherstellungs- und Synchronisations-Pfade** dagegen können **den Wecker vollständig ausfallen lassen** — und zwar teils *deterministisch*, nicht nur in Randfällen. Das bricht exakt das Kernversprechen der App („der Wecker klingelt, auf den sich der Frühdienst verlässt“).

**Alle sechs Auditoren kommen unabhängig zum selben strategischen Schluss: Refactoring, kein Rewrite** (siehe §2).

### Ampel nach Bereich

| Bereich | Ampel | Kernaussage |
|---|---|---|
| Alarm-Verlässlichkeit | 🟡 | ✅ P0-1/P0-3/P0-4 + Weckton-P1 behoben; ⬜ Direct Boot (P0-2) + Recovery-Scope (P0-5) offen (Batch 2) |
| Nebenläufigkeit/Prozess-Tod | 🟡 | ✅ Maintenance-/deleteAll-Destruktion + Boot-Fetch-Fehler behoben; ⬜ RMW-Races, Doppelkette, Direct Boot offen |
| Sicherheit/Datenschutz | 🟡 | Krypto-Kern vorbildlich; PII-Klartext-Logging in Release + ungenutzte Permissions |
| Architektur | 🟢 | Fundament (Schichten, DI, Interfaces) trägt; Krankheit sitzt in der Koordinationsschicht |
| UI/UX/Onboarding | 🟡 | Breite, solide Substanz; 3 harte Schwächen im kritischen Pfad; Zwangs-Gate im Onboarding |
| Infrastruktur/Release | 🟡 | Gute Tests (aber Alarm-Kette ungetestet), keine CI, Play-/OAuth-Hürden nicht adressiert |

---

## 2. Strategische Entscheidung: Refactoring vs. Rewrite

### Empfehlung: **Inkrementelles, fokussiertes Refactoring — klar KEIN (Teil-)Rewrite.**

**Begründung.** Alles, was ein Rewrite liefern würde, ist bereits vorhanden **und funktioniert**:

| Was ein Rewrite bringen soll | Ist-Zustand in CF Alarm |
|---|---|
| Saubere Schichtentrennung | ✅ Vorhanden; projektweit nur **2** Import-Verstöße (beide `HueBridgeConnectionManager` in Composables). `hue/usecase` ist 100% Android-frei. |
| Konsistente DI | ✅ Hilt korrekt: durchgängig `@Binds` für Interfaces, saubere DataStore-Qualifier, keine `@Singleton`-Inflation. |
| Entkoppelte ViewModels | ✅ Keine einzige ViewModel-zu-ViewModel-Referenz; `CalendarStateHolder` als einziges Shared-State-Konstrukt. |
| Richtige Kernlogik am richtigen Ort | ✅ `AlarmUseCase` enthält bereits checksummen-basierten Delta-Sync; `HueRuleUseCase` fachlich durchdachte Sicherheitsnetze. |
| Solides Fehlermodell | ✅ sealed `AppError` + `SafeExecutor` im Core konsequent; 0 TODO/FIXME im Quellbaum. |

**Krank ist die App nur auf der Verhaltens- und Hygiene-Ebene der Koordinationsschicht:**
- Die Kern-Pipeline *Events→Alarme* ist **dreifach divergent** implementiert (CalendarViewModel, ShiftViewModel, AlarmMaintenanceService) und hebelt dabei den vorhandenen Delta-Sync per `deleteAll()+delay(100)` wieder aus.
- **Timing statt Reaktivität**: `delay()`-Polling als Synchronisationsersatz (Nährboden der „CRITICAL FIX“-Kaskaden).
- **Zwei DI-Welten**: Hilt-Bindings existieren, aber Teile der App holen dieselben Singletons per `getInstance()` daran vorbei.
- **Fix-Archäologie / toter Code**: nie gelesener SharedPrefs-Auth-Kanal (inkl. Reflection-Hack), nie laufender `onDestroy`-Cleanup (mit `System.gc()`), tote Hue-Duration-API — Code, der Sicherheitsnetze *vortäuscht*.

**Gegen einen Rewrite spricht zusätzlich das Risiko:** Ein Neubau würde genau die subtile, teuer gereifte Fachlogik gefährden, die heute funktioniert — Alarm-Delta-Sync, Sunrise-Fallback-Ketten, OEM-/Battery-Handling, DST-Weckzeitberechnung. Das ist der teuerste denkbare Wegwurf.

**Aufwandsschätzung Refactoring:** grob **6–10 Personentage**, zerlegbar in unabhängige, einzeln auslieferbare Schritte.

### Top-5-Refactoring-Ziele (Reihenfolge = Priorität)

1. **Alarm-Sync in EINEN Domain-Orchestrator konsolidieren** (Effort L) — eine mutex-geschützte `syncAlarms(events)`-Operation; CalendarViewModel/ShiftViewModel/MaintenanceService rufen nur noch diese; `deleteAllAlarms`-Vorlauf, Doppel-Scheduling und alle `delay()`-Hacks raus. *Höchster Nutzen für die Alarm-Zuverlässigkeit — und behebt gleich mehrere P0/P1.*
2. **Tote Fix-Schichten entfernen** (Effort S–M) — `onDestroy`-Cleanup in 6 ViewModels, `cf_alarm_auth`-SharedPrefs + Reflection-Hack, tote Hue-Duration-API, halbtoter `CalendarStateHolder`-Anteil. *Billig, senkt Risiko (auch für die spätere R8-Reaktivierung), verkleinert alle Folge-Refactorings.*
3. **`getInstance()`-Parallelwelt auf Hilt umstellen** (Effort M) — ConnectionManager/SmartScheduler injizieren; stellt Testbarkeit und Single-Source-of-Truth des Object-Graphs her.
4. **State-/Lifecycle-Korrektheit in der UI** (Effort S–M) — `StateFlow.update` statt handgestricktem Batching, Formular-State des Hue-Regel-Editors in ViewModel/SavedState heben, `BackHandler` für die Custom-Navigation.
5. **Konventionen vereinheitlichen, Rauschen tilgen** (Effort M) — `AppError`/`SafeExecutor` auch in der Hue-Schicht, eine Result-Semantik; Emoji-/PHASE-Marker aus Kommentaren und Logs. *Danach fallen die ViewModel-Splits fast von selbst.*

---

## 3. Release-Blocker (P0) — im Detail

> Gemeinsamer Nenner aller fünf: **Der Wecker verschwindet oder wird nie wiederhergestellt.** Vier davon habe ich selbst am Code verifiziert; alle fünf sind mit **S–M** behebbar (nur der Direct-Boot-Vollausbau ist L).

### ✅ P0-1 · BootReceiver-Filter repariert — `<data>` + `PACKAGE_REPLACED` entfernt
Behoben in `93583c5`. Review OK. **Verifikation im Build:** echter Reboot + Logcat („LEVEL 4: Boot event received").

### ⬜ P0-2 · Direct Boot faktisch nicht unterstützt — `LOCKED_BOOT_COMPLETED` wird ignoriert  *(offen — Batch 2)*
**`alarm/receiver/BootReceiver.kt:117` · Effort L**
Der Receiver ist `directBootAware=true` und für `LOCKED_BOOT_COMPLETED` registriert, aber der `when`-Block hat **keinen** Zweig dafür → `else` „Ignoring unhandled action“. Zudem liegen alle Alarm-Daten im credential-encrypted `@MainDataStore`, der vor der ersten Entsperrung **nicht lesbar** ist; `AlarmReceiver`/`AlarmSoundService` sind nicht `directBootAware`. `BOOT_COMPLETED` kommt bei File-Based-Encryption erst **nach** dem ersten Entsperren.
**Szenario (bleibt auch nach P0-1-Fix bestehen):** Reboot nachts, Nutzer entsperrt nicht vor der Weckzeit → keine Wiederherstellung möglich → verschlafen.
**Fix:** Nächste Alarm-Triggerzeiten zusätzlich in einen **Device-Protected-Storage-DataStore** spiegeln (`createDeviceProtectedStorageContext()`); bei `LOCKED_BOOT_COMPLETED` daraus die System-Alarme rein per `setAlarmClock` neu setzen (kein Kalender/Token nötig). `AlarmReceiver` + `AlarmSoundService` + Notification-Channel-Code `directBootAware` machen. Volle Kalender-Validierung erst bei `BOOT_COMPLETED`.

### ✅ P0-3 · Maintenance übergibt vollständige Event-Liste an den Delta-Sync
Behoben in `0fb21f9`. Review OK (14-Tage-Fenster ≥ Alarm-Puffer via `MIN_BUFFER_DAYS=7` → keine Fehllöschung).

### ✅ P0-4 · Boot-Recovery fail-safe — löscht nicht mehr bei fehlgeschlagenem Event-Load
Behoben in `4ef4840` (`validationPossible`-Gate: löschen nur bei erfolgreichem, nicht-leerem Abruf). Volle Zuverlässigkeit erst mit P0-5 (killbarer Recovery-Scope).

### ⬜ P0-5 · Boot-Recovery läuft im ungeschützten Prozess-Scope (kein goAsync/Foreground/WorkManager)  *(offen — Batch 2)*
**`alarm/receiver/BootReceiver.kt:139` · Effort M**
`onReceive` kehrt sofort zurück; die gesamte Recovery (5 s Delay, bis zu 3 Retries à 10 s, Netz-Calls, 30 s Health-Check) läuft in einem selbst erstellten Scope. Nach Rückkehr ist der Prozess ein leerer Cached-Prozess — direkt nach dem Boot (hoher Memory-Druck) bevorzugter Kill-Kandidat. Wird er vorher gekillt: Alarme kommen nicht in den AlarmManager zurück **und** die 6h-Kette wird nicht neu geplant → Totalausfall bis zum manuellen App-Start. Derselbe fragile Pfad läuft bei jedem App-Update.
**Fix:** Recovery in einen expedited `OneTimeWorkRequest` (WorkManager, `setExpedited`) oder kurzlebigen Foreground-Service verlagern; im Receiver nur enqueuen. Minimal: `goAsync()` + sofortiges Re-Scheduling aus dem Repository (ohne Delays/Netz), Validierung später.

> **Bündelungs-Hinweis:** P0-2 bis P0-5 sowie mehrere Alarm-P1 lösen sich größtenteils mit **Refactoring-Ziel #1** (ein transaktionaler, fail-safe Alarm-Sync-/Recovery-Pfad). Das ist der Hebel mit dem höchsten Ertrag.

---

## 4. Kritische Befunde (P1) — nach Thema gebündelt

**Verifikationsstatus:** ✅ = selbst am Code bestätigt · ⚪ = auditor-berichtet.

### 4a. Alarm klingelt/verschwindet (Blocker-nah, teils nur knapp unter P0)
- ✅ **P1 · Power-Taste/Anruf/Wegwischen beendet den klingelnden Alarm dauerhaft** — behoben in `a9e50e7`: Stop aus `onStop`/`onDestroy`/`onTaskRemoved` entfernt; die FGS-Notification erhielt einen „Wecker aus"-Action-Button als immer erreichbaren Stop-Weg (schließt die von der Review gefundene Sackgasse).
- ✅ **P1 · Manuell erstellte Alarme werden bei jedem Kalender-Load kommentarlos gelöscht** — behoben in `a35ae88` (`deleteAllAlarms()`+`delay(100)` vor dem Delta-Sync entfernt).
- ✅ **P1 · Boot-Recovery löscht bei fehlgeschlagenem Fetch alle Alarme** — behoben in `4ef4840` (`validationPossible`-Gate; gleiche Wurzel wie P0-4).
- ⬜ **P1 · `LOCKED_BOOT_COMPLETED` registriert, aber ignoriert** — offen, Duplikat-Blickwinkel zu P0-2 (Batch 2). *(`BootReceiver.kt:117`, Effort L)*

### 4b. Nebenläufigkeit / Prozess-Tod des Alarm-Bestands
- ⚪ **P1 · AlarmRepository: async Init-Load + ungeschützte Read-Modify-Write** — leerer StateFlow-Cache beim Kaltstart; `saveAlarm`/`deleteAlarm` sind unsynchronisierte RMW-Zyklen → paralleler Write kann den ganzen DataStore-Key mit einer 1-Element-Liste überschreiben (**Datenverlust**). *(`AlarmRepository.kt:82`, Effort M)* → **Fix:** Load-Gate (`CompletableDeferred`/`first()`) + Mutex bzw. `update{}`/`edit` als einzige Quelle.
- ⚪ **P1 · Zwei parallele Maintenance-Ketten (requestCode 0 vs. 9999) + keine Reparatur beim App-Start** — doppelte Broadcasts alle 6 h; die Kette wird nie beim normalen App-Start neu geplant → nach Force-Stop/Crash dauerhaft weg bis Reboot/Re-Login. *(`AlarmMaintenanceService.kt:376`, Effort S)* → **Fix:** eine Scheduling-Methode/ein requestCode; `scheduleNext` idempotent bei jedem App-Start.
- ⚪ **P1 · `stopSelf()` ohne `startId` + `serviceScope.cancel()` bricht parallele Maintenance mitten im Scheduling ab** — Cancel zwischen `saveAlarm` und `scheduleSystemAlarm` → Alarm im Repository, aber nicht im AlarmManager; wird nie nachgeholt („zeigt Wecker an, der nicht klingelt“). *(`AlarmMaintenanceService.kt:180`, Effort S)* → **Fix:** `stopSelf(startId)`; kritische Sequenz `NonCancellable`.
- 🔧 **P1 · Pipeline dreifach divergent orchestriert** — das akute Verlustfenster (`deleteAll`+`delay(100)` in CalendarViewModel) ist mit `a35ae88` beseitigt; die vollständige Konsolidierung auf **einen** Alarm-Sync-Orchestrator (Refactoring-Ziel #1, betrifft auch ShiftViewModel + MaintenanceService) bleibt für Gate 2 offen.
- ⚪ **P1 · Nebenläufigkeitsschutz per `@Volatile`-Boolean (check-then-act) statt Mutex** — zwei parallele Aufrufer lesen beide `false`; oder der zweite bekommt `Result.success(emptyList())`, was der Maintenance-Service als „nichts zu tun“ fehldeutet. *(`AlarmUseCase.kt:83`, Effort S)* → **Fix:** `Mutex.withLock`; Skip-mit-Leerergebnis entfernen.

### 4c. Sicherheit / Datenschutz
- ✅ **P1 · Persistentes File-Logging schreibt in RELEASE PII im Klartext** — `SimpleFileTree` wird in `CFAlarmApplication.onCreate()` **immer** geplantet (Debug **und** Release) und schreibt Google-Konto-E-Mail und Kalender-Event-Titel nach `getExternalFilesDir()/debug_logs.txt` (bis 50 MB, unbegrenzte Aufbewahrung). Auf Android 8–10 (minSdk 26!) mit `READ_EXTERNAL_STORAGE` durch Fremd-Apps lesbar. Widerspricht `privacy.html` und dem Data-Safety-Formular. *(`CFAlarmApplication.kt:66`, Effort M)* → **Fix:** In Release nur `Log.WARN`+ aufwärts, PII maskieren; Rotationsgröße/Retention begrenzen; Event-Titel nie auf `business`-Level.
- ✅ **P1 · `SYSTEM_ALERT_WINDOW` (+ `DISABLE_KEYGUARD`) deklariert, aber ungenutzt** — behoben in `93583c5` (beide aus dem Manifest entfernt; Full-Screen-Alarm läuft über die Activity-Attribute weiter).

### 4d. UI/UX (kritischer Pfad)
- ✅ **P1 · Ladefehler der Kern-Pipeline werden nicht kommuniziert** — `calendarState.error` wird im `EventListScreen` explizit **nicht** angezeigt (auskommentierter Snackbar-Block, „For now, just log and clear“); `shiftState.error`/`alarmState.error` werden in **keinem** Screen gerendert; `MainContentScreen`-Scaffold hat keinen `SnackbarHost`. → Home-Tab zeigt kommentarlos „Keine Schicht/kein Alarm“, Nutzer hält das für die Wahrheit. *(`EventListScreen.kt:253`, Effort M)* → **Fix:** `SnackbarHost` + vorhandene `ErrorMessage`-Komponente mit Retry.
- ⚪ **P1 · Battery-Exemption ist ein Zwangs-Gate ohne „Später“** — verweigert der Nutzer die Akku-Freigabe, kommt er **nie** in die App (Endlosschleife Screen ↔ Dialog); bei jedem Neustart erneut erzwungen. Play-Review-heikel; das persistente Warn-Card-Sicherheitsnetz im SettingsTab existiert bereits. *(`BatteryOnboardingScreen.kt:216`, Effort S)* → **Fix:** „Später“-Button → Home + Dismissed-Flag; Re-Prompt max. einmal.
- *(Die `onStop`-Ausprägung aus UX-Sicht ist mit 4a identisch.)*

### 4e. Infrastruktur / Release (Prozess-Hürden mit langer Vorlaufzeit)
- ⚪ **P1 · Google-OAuth-Verification für `calendar.readonly` (sensitive scope) nirgends vorbereitet** — ohne verifizierten Consent-Screen: **max. 100 Test-Nutzer über die Projekt-Lebenszeit** + „nicht überprüft“-Warnbildschirm beim Login. Verification dauert erfahrungsgemäß **Wochen**. **→ Sofort starten, unabhängig von allem anderen — das ist die längste Vorlaufzeit im ganzen Projekt.** *(`OAuth2TokenManager.kt:93`, Effort M + Wartezeit)*
- ⚪ **P1 · Keine CI-Pipeline** — kein `.github/`; die (guten) Unit-Tests und das strikte Lint laufen nur, wenn lokal daran gedacht wird. Besonders kritisch, weil delegierte Änderungen sonst keinerlei automatische Verifikation haben. Build ist dank ENV-Fallback CI-ready (keine Code-Änderung nötig). *(Effort M — fertige YAML in §6.)*

---

## 5. P2/P3 — thematische Verdichtung

37× P2, 25× P3. Vollständige Liste in **Anhang A**. Die relevanten Cluster:

- **Alarm-Härtung (P2):** stille `scheduleSystemAlarm`-Erfolgsmeldung trotz Fehlschlag (`AlarmUseCase.kt:336`); kein Zeitzonen-/`TIME_SET`-Receiver → Wecker klingelt nach Zonenwechsel versetzt; kein Fallback bei Exact-Alarm-Entzug auf API 31/32; `START_STICKY`-Restart mit null-Intent tut nichts; keine Absicherung gegen Wecker-Stream-Lautstärke 0.
- **Sicherheit (P2/P3):** Keyset-Korruption → dauerhafter Auth-Soft-Lock ohne Recovery; `privacy.html` widerspricht dem Code in 3 Punkten; **4 weitere ungenutzte Permissions** (`DISABLE_KEYGUARD`, `CHANGE_WIFI_MULTICAST_STATE`, `MODIFY_AUDIO_SETTINGS`, `ACCESS_NOTIFICATION_POLICY`); „Logs senden“ verschickt PII an eine Trashmail ohne Consent-Hinweis; **Hue-TOFU-Pinning faktisch wirkungslos** (Mismatch loggt nur und überschreibt den Pin sofort); Google Sign-In ohne Nonce; NSC-IP-„Domains“ greifen wirkungslos.
- **Architektur (P2/P3):** toter `MainActivity.onDestroy`-Cleanup (mit `System.gc()`); mDNS-Discovery kann konstruktionsbedingt nie Bridges liefern; ~20 Hue-Formularfelder nur in `remember` (Datenverlust bei Rotation); zwei DI-Welten; Fehlermodell zweigeteilt; 829 Emoji-Zeilen / 132 PHASE-Marker als Wartbarkeitsrauschen.
- **UX (P2/P3):** Dark Mode unvollständig (Light-only-XML-Theme → weißer Start-Blitz); Abmelden/Löschen/Reset ohne Bestätigung; `POST_NOTIFICATIONS` kontextlos vor dem Login; **fertige `CountdownTimer`- und `NoAlarmCard`-Komponenten liegen ungenutzt als toter Code** (genau die Vertrauensbildung, die dem Home-Tab fehlt); Du/Sie-Sprachmix; Lokalisierung unvorbereitet (8 Strings in `strings.xml` vs. 110+ Inline-Literale); Alarm-Dismiss ruft `cancelAll()` und löscht alle App-Notifications.
- **Infra (P2/P3):** Kern-Alarm-Kette komplett ungetestet (Blocker: `AlarmManagerService` ohne Interface); `MainActivityTest` ist ein Zombie gegen eine entfernte UI; 3 von 4 Play-Console-Deklarationen fehlen; `proguard-rules.pro` mit `-dontshrink/-dontoptimize` + toten Firebase-Regeln (Minify-Reaktivierung wäre wirkungslos); `lint-baseline.xml` existiert, wird aber nicht referenziert.

---

## 6. Cross-cutting Deliverables

### 6.1 Alarm-Zuverlässigkeit — Szenario-Matrix

| # | Szenario | Verdikt | Beleg (eine Zeile) |
|---|----------|---------|--------------------|
| a | Reboot nachts, Nutzer entsperrt NICHT (Direct Boot) | 🔴 **AUSFALL** | Doppelt kaputt: `<data>` blockiert BOOT/LOCKED_BOOT (Manifest:113-119) **und** `LOCKED_BOOT_COMPLETED` im `else` (BootReceiver.kt:117); Daten im CE-Storage. |
| b | App-Update über Nacht (`MY_PACKAGE_REPLACED`) | 🔴 **AUSFALL** | Wird ohne Daten-URI gesendet, matcht den `<data>`-Filter nicht (Manifest:116+118) → Recovery läuft nie. |
| c | App abends aus Recents gewischt | 🟡 **OK/RISIKO** | `setAlarmClock` überlebt Swipe auf Stock-Android; RISIKO auf Force-Stop-OEMs; wischt man **während** des Klingelns, stoppt `onTaskRemoved` den Ton. |
| d | Exact-Alarm-Permission entzogen | 🟡 **RISIKO** | API 33+ nicht entziehbar = OK; API 31/32: Entzug cancelt Alarme, kein `PERMISSION_STATE_CHANGED`-Receiver, `scheduleSystemAlarm` meldet trotzdem Erfolg. |
| e | Do-Not-Disturb aktiv | 🟢 **OK** | `USAGE_ALARM`/`STREAM_ALARM` (AlarmSoundService.kt:216-221); Restrisiko nur bei Nutzer-Config „auch Wecker stumm“. |
| f | Akku-Sparmodus / tiefes Doze | 🟢 **OK** | `setAlarmClock` (höchste Prio, Doze-fest); Sound-FGS unter Exact-Alarm-Exemption; Wartung via `setExactAndAllowWhileIdle`. |
| g | Zeitzonenwechsel / manuelle Uhrzeit | 🟡 **RISIKO** | Kein `TIMEZONE_CHANGED`/`TIME_SET`-Receiver; Alarme als RTC-Millis der alten Zone eingefroren; Health-Check korrigiert nur bei Puffer < 7 Tagen. |
| h | Kein Netz > 24 h (Token abgelaufen) | 🟢 **OK** | Bereits geplante Alarme klingeln lokal; Maintenance bricht nur ab, plant im `finally` den nächsten Lauf; erst nach > 7 Tagen fehlen neue Alarme (by design). |
| i | OEM-Killer (Samsung/Xiaomi/…) | 🟡 **RISIKO** | Gute Nutzerführung existiert, aber nach Force-Stop hängt die Selbstheilung am kaputten BootReceiver bzw. am manuellen App-Start. |

**Kernaussage:** Der Klingel-Pfad ab gesetztem Alarm ist solide. Die Ausfälle konzentrieren sich vollständig auf **Wiederherstellung** (a, b) und **Sync-Destruktion** — beides mit den P0-Fixes + Refactoring-Ziel #1 adressierbar. Szenarien d/g sind P2-Härtung.

### 6.2 CI-Pipeline (GitHub Actions) — paste-ready

Voraussetzung: Repo-Secret `GOOGLE_WEB_CLIENT_ID` anlegen (der Wert steht ohnehin im APK-BuildConfig, kein Hochsicherheits-Geheimnis). `app/build.gradle.kts:80-81` liest ihn bereits per `System.getenv()` — **keine Code-Änderung nötig**. Release-Signing ist für PR-CI nicht erforderlich.

```yaml
# .github/workflows/ci.yml
name: CI
on:
  push: { branches: [main] }
  pull_request:
concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true
jobs:
  build-test-lint:
    runs-on: ubuntu-latest
    timeout-minutes: 45
    env:
      GOOGLE_WEB_CLIENT_ID: ${{ secrets.GOOGLE_WEB_CLIENT_ID }}   # PFLICHT: sonst GradleException
    steps:
      - uses: actions/checkout@v4
      - name: Make gradlew executable        # Repo wird unter Windows gepflegt
        run: chmod +x gradlew
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: gradle/actions/setup-gradle@v4
        with:
          validate-wrappers: true
          cache-read-only: ${{ github.ref != 'refs/heads/main' }}
      - run: ./gradlew testDebugUnitTest --stacktrace
      - run: ./gradlew lintDebug --stacktrace            # abortOnError=true wirkt als Merge-Gate
      - run: ./gradlew assembleDebug --stacktrace
      - uses: actions/upload-artifact@v4
        with: { name: app-debug, path: app/build/outputs/apk/debug/app-debug.apk, retention-days: 14 }
      - if: failure()
        uses: actions/upload-artifact@v4
        with: { name: reports, path: "app/build/reports/", retention-days: 14 }
```
Danach Branch-Protection auf `main` mit `build-test-lint` als Pflicht-Check. Bewusst **nicht** enthalten: Emulator/Instrumented (erst wenn der Zombie-`MainActivityTest` ersetzt ist) und Release-Signing (erst für Store-Upload-Automation).

### 6.3 Die 10 wichtigsten fehlenden Tests (Alarm-Kette zuerst)

Größter Einzel-Hebel: **`IAlarmManagerService`-Interface einziehen** — schaltet Tests #1+#2 rein auf der JVM frei. Robolectric nur für #7 (und ggf. #9) nötig.

| # | Test | Warum kritisch | Machbarkeit |
|---|------|----------------|-------------|
| 1 | `AlarmUseCase`-Delta-Sync (geändert/gelöscht/unverändert/Vergangenheit/autoAlarm=false/leer) | Hier lebte „alter Alarm klingelt nach Event-Änderung“ | JVM + Fakes; **Blocker: `AlarmManagerService`-Interface** |
| 2 | `AlarmSkipUseCase` (Skip cancelt System+Repo, Status wird geleert) | Ghost-Alarm oder fälschlich stiller Alarm | JVM; gleicher Interface-Blocker |
| 3 | `AlarmRepository` Roundtrip + korruptes JSON darf nicht alles verwerfen | Alarme müssen App-Neustart überleben | JVM: DataStore-Fake (15 Zeilen) |
| 4 | `ShiftRecognitionEngine` Cache-Invalidierung nach Config-Änderung | Veralteter Cache → alte Weckzeit | JVM; Clock-Injection für Determinismus |
| 5 | DST-/Zeitzonen-Kanten in `calculateAlarmTime` | DST-Nacht = klassischer Totalausfall | JVM: `TimeZone.setDefault(Europe/Berlin)` |
| 6 | `OAuth2TokenManager` Expiry-Puffer | Abgelaufener Token → nach 7 Tagen keine Alarme | `TokenData` sofort JVM; Manager braucht `GoogleAuthUtil`-Wrapper |
| 7 | `AlarmManagerService` setAlarmClock/cancel-Pfad | Letzte Meile zum OS; falsche Flags = stiller Ausfall | **Robolectric** (`ShadowAlarmManager`) |
| 8 | `AlarmMaintenanceService` Health-Check/Puffer (`MIN_BUFFER_DAYS=7`) | Entscheidet, ob überhaupt synchronisiert wird | Logik in `MaintenanceDecision` extrahieren → JVM |
| 9 | `BootReceiver`-Recovery (reschedule, Vergangenes filtern, Retry) | Neustart über Nacht → alle Wecker weg | Robolectric ODER Logik extrahieren |
| 10 | `ShiftConfigRepository` mit korruptem/altem JSON | Korrupte Config darf App/Maintenance nicht crashen | JVM + DataStore-Fake |

### 6.4 Play-Console-Readiness (interner Track → offener Test)

| Punkt | Status | Aktion |
|-------|--------|--------|
| FGS `specialUse` (Formular + Video) | Text paste-ready (`docs/play-store/…`) | Video aufnehmen, Formular ausfüllen |
| FGS `mediaPlayback` (AlarmSoundService) | **fehlt** | Begründung dokumentieren; Fallback-Argumentation „Weckton“ bereithalten |
| `USE_FULL_SCREEN_INTENT` (App-Content-Formular) | **fehlt** | Kategorie „Alarm clock“ → Default-Grant; Deklaration ausfüllen |
| `USE_EXACT_ALARM` | in FGS-Doku erwähnt | Formulartext finalisieren |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | korrekt user-initiiert | 1 Absatz Begründung ablegen; Feature optional halten |
| `SYSTEM_ALERT_WINDOW` + `DISABLE_KEYGUARD` | **ungenutzt** | **Vor Einreichung streichen** (P1) |
| OAuth-Verification (calendar.readonly) | **nicht adressiert** | **Sofort starten** (100-Nutzer-Cap; Privacy-Domain via Search Console verifizieren) |
| Privacy Policy | Falschaussage zur Kalenderdaten-Verschlüsselung | korrigieren; URL identisch in Play Console + Consent-Screen |

**Data-Safety-Kurzfassung:** Google-Konto/Kalender werden nur lokal (Tink-verschlüsselt) gehalten und verlassen das Gerät nicht Richtung Entwickler → „keine Datenerhebung“ ist vertretbar; Log-/Crash-Mail fällt unter die „user-initiated“-Ausnahme, **solange** kein Auto-Upload und Event-Titel im Log maskiert werden.

### 6.5 Crash-Reporting-Empfehlung

**Kein Cloud-SDK.** Stufe 1 (jetzt): vorhandenen lokalen Crash-Handler (`last_crash.txt` existiert bereits) zum **user-initiierten Crash-Report-Dialog** beim nächsten App-Start ausbauen (~30 Zeilen Eigenbau *oder* **ACRA im Mail-Modus**). Stufe 2 (nur falls dreistellige Testerzahl): **Sentry mit EU-Region + Opt-in**.
- **Firebase Crashlytics: Nein** — Datentransfer an Google/US, würde das Kernversprechen der Privacy Policy („keine Analytics, keine Cloud, alles lokal“) brechen; bei der Zielgruppe (Gesundheitswesen) ist genau das ein Feature.
- **ACRA (Mail): passt exakt** — kein Server, Versand nur per Nutzeraktion → Data Safety bleibt „keine Datenerhebung“, DSGVO trivial. Erreicht ~90 % des Crashlytics-Nutzens bei 0 % Datenschutzkosten.

### 6.6 Onboarding-Bilanz & UX-Top-5

**Ist-Parcours (Erst-Nutzer):** ① App-Start → **sofort `POST_NOTIFICATIONS` (kontextlos)**, ggf. parallel OnePlus-Dialog → ② Google Sign-In → ③ Kalender-OAuth (kontextuell, gut ✓) → ④ Kalenderauswahl → ⑤ **Battery-Exemption (Zwangs-Gate, kein Skip)** → ⑥ OEM-Warnung (bei der Geräte-Mehrheit) → ⑦ Home; optional Hue mit just-in-time-Permission (✓). **Bilanz:** 5–7 Schritte, 3–4 System-Prompts (1 kontextlos, 1 erzwungen); **4 redundante OEM-Warn-Mechanismen** konsolidierbar.

**Top-5 (Impact ÷ Aufwand):**
1. **Battery-Gate entschärfen** („Später“-Button, S) — macht aus der Sackgasse ein normales Onboarding; Settings-Warn-Card fängt es ab.
2. **`CountdownTimer` + `NoAlarmCard` einbinden** (S) — beide fertig als toter Code vorhanden; beantworten die Kernfrage „klingelt es, und wann?“.
3. **Alarm-Screen fehlbediensicher** (M) — Ton nicht aus `onStop`; Auto-Snooze; Notification-Actions; `dp` statt Pixel, 24 dp Button-Abstand, Snooze als große Primärfläche.
4. **Globale Fehler-Sichtbarkeit** (M) — `SnackbarHost` + `ErrorMessage` im Home-Tab; beendet das stille Verschlucken.
5. **`POST_NOTIFICATIONS` in den Kontext verschieben** (S) — hinter den Login, mit Begründung; erhöht die Grant-Rate der für Alarme kritischen Permission.

---

## 7. Was gut ist (Substanz, die den Refactoring-Weg trägt)

Damit das Bild fair bleibt — die App hat ein **belastbares Fundament**:
- **Alarm-Kern:** richtige `setAlarmClock`-API + `canScheduleExactAlarms`-Handling; entkoppelter Sound-FGS mit `USAGE_ALARM` + Generation-Counter-Race-Guard; ANR-sicherer Receiver (`goAsync` + `finally`); doppelter Skip-Schutz; ernsthafte OEM-/Batterie-Nutzerführung.
- **Nebenläufigkeit:** **0** `GlobalScope`/`runBlocking` in 157 Dateien; alle 12 Scopes mit `SupervisorJob`; deterministische Alarm-IDs + `FLAG_UPDATE_CURRENT` machen Doppel-Scheduling idempotent; `CalendarEventCache` mit korrektem Mutex; atomare Token-/Hue-Persistenz.
- **Sicherheit:** Tink-AEAD mit **Android-Keystore-Master-Key** (kein Klartext-Keyset-Antipattern); minimaler OAuth-Scope; sanitisiertes Token-Logging; include-only-Backup-Regeln (kein Token-/Keyset-Leak); durchgängig `FLAG_IMMUTABLE`; kein Intent-Spoofing möglich; keine Tracking-SDKs.
- **Architektur:** Schichten gehalten (nur 2 Import-Verstöße), Hilt sauber, keine ViewModel-Kopplung, richtige Delta-Sync-Logik bereits am richtigen Ort, professionelles Fehler-Grundgerüst, 0 TODO/FIXME.
- **UI/UX:** vorbildliche Loading/Empty/Error-Zustände in mehreren Screens; prominente „Autorisierung verloren“-Karte; wiederverwendbare `ErrorMessage`-Komponente; Design-Tokens vorhanden; just-in-time-Permission bei Hue.
- **Infra:** die 7 jüngeren Unit-Testklassen sind **echte, hochwertige** Tests (nachgerechnete Erwartungswerte, handgeschriebene Fakes, aktiv hergestellte Testbarkeit); selbstkritische Testkultur; Lint ernst genommen; paste-ready Play-Store-Vorarbeit.

---

## 8. Empfohlene Roadmap (Phase 2, nach Abnahme)

**Gate 0 — Release-Blocker (zwingend vor JEDEM weiteren Test):**
- ✅ **Batch 1 (Branch `fix/audit-gate0-alarm-reliability`):** P0-1, P0-3, P0-4 + P1 (Weckton-onStop, manuelle-Alarme, Boot-Fetch-Fehler, ungenutzte Permissions). Minimal-Fixes, review-geprüft. **Lokaler Build + Hardware-Test ausstehend.**
- ⬜ **Batch 2:** P0-2 (Direct-Boot-Spiegelung in Device-Protected-Storage, L) + P0-5 (Recovery in WorkManager, M) — der einzige noch offene „Wecker-fällt-aus"-Kern.
- ⬜ **Gate 2 (später):** Refactoring-Ziel #1 (Alarm-Sync-Orchestrator) konsolidiert die Minimal-Fixes zu einer atomaren `syncAlarms()`-Operation.
- **Verifikation auf echter Hardware (nach jedem Batch):** Reboot vor Weckzeit, App-Update über Nacht, Power-Druck/Anruf während Klingeln, Task-Swipe, Zonenwechsel.

**Parallel & sofort (lange Vorlaufzeit, blockiert den offenen Test):**
- **OAuth-Verification einreichen** (Wochen Bearbeitungszeit).
- Ungenutzte Permissions streichen; Play-Console-Deklarationen (mediaPlayback, FULL_SCREEN_INTENT) vorbereiten.

**Gate 1 — Vor öffentlichem Test:**
- Release-PII-Logging entschärfen; Privacy Policy an Code angleichen.
- Globale Fehler-Sichtbarkeit (SnackbarHost); Battery-Gate „Später“; `POST_NOTIFICATIONS` in Kontext.
- CI-Pipeline (§6.2) + Branch-Protection; Zombie-`MainActivityTest` entfernen/ersetzen.
- Tests #1–#3 (`IAlarmManagerService` einziehen).

**Gate 2 — Härtung/Qualität (nach dem ersten offenen Test iterierbar):**
- Refactoring-Ziele #2–#5 (toter Code, Hilt-Vereinheitlichung, Lifecycle-State, Konventionen).
- P2-Alarm-Härtung (Zeitzonen-Receiver, stille Schedule-Fehler, Lautstärke-0); Dark Mode; Crash-Dialog; Tests #4–#10.

---

## Anhang A — Vollständige P2/P3-Liste

Format: `[Effort] Titel — Datei:Zeile`. Kurzfassung nach Bereich in §5.

### P2 (37)

**Alarm (8)**
- [M] `scheduleSystemAlarm` meldet Erfolg, obwohl kein System-Alarm gesetzt wurde (Fehler nur im ignorierten Status-Objekt) — `usecase/AlarmUseCase.kt:336`
- [M] Boot-Recovery läuft nach Receiver-Rückkehr in ungeschütztem Prozess — `alarm/receiver/BootReceiver.kt:139`
- [S] Entzug der Exact-Alarm-Permission auf API 31/32 wird nicht erkannt (kein `PERMISSION_STATE_CHANGED`-Receiver) — `AndroidManifest.xml:37`
- [M] Zeitzonenwechsel/manuelle Uhrzeitänderung nicht behandelt — Wecker zur falschen Lokalzeit — `service/AlarmManagerService.kt:181`
- [S] `START_STICKY`-Restart des AlarmSoundService mit null-Intent tut nichts — `service/AlarmSoundService.kt:148`
- [S] Keine Absicherung gegen stummgestellten Wecker-Stream (Lautstärke 0), keine DND-/Vibrations-Härtung — `service/AlarmSoundService.kt:216`
- [S] Zwei parallele Maintenance-PendingIntents (requestCode 0 vs. 9999), kein Rescheduling beim App-Start — `service/AlarmMaintenanceService.kt:137`
- [M] AlarmRepository: async init-Load kann frühe Zugriffe/Writes verlieren — `repository/AlarmRepository.kt:74`

**Nebenläufigkeit (5)**
- [S] AlarmSoundService: `START_STICKY`-Restart mit null-Intent startet weder Foreground noch Sound neu — `service/AlarmSoundService.kt:148`
- [S] AlarmReceiver: äußerer `launch` ohne catch/CoroutineExceptionHandler — unbehandelte Exception crasht den Prozess im Alarm-Moment — `AlarmReceiver.kt:79`
- [M] Kein Abgleich Repository ↔ AlarmManager: einmal verpasstes `scheduleSystemAlarm` wird nie nachgeholt — `usecase/AlarmUseCase.kt:177`
- [S] ShiftRecognitionEngine: Cache-Hash wird VOR dem Ergebnis gesetzt — paralleler Aufrufer erhält veraltete Matches — `shift/ShiftRecognitionEngine.kt:126`
- [S] `MainActivity.onDestroy`-Cleanup ist toter Code — würde er laufen, würde er Prozess-Singletons bei jeder Rotation lahmlegen — `MainActivity.kt:265`

**Sicherheit (4)**
- [S] Keyset-/Dateikorruption → dauerhafter Auth-Soft-Lock ohne Recovery-Pfad — `auth/security/EncryptedDataStoreFactory.kt:111`
- [S] Datenschutzerklärung widerspricht dem Code-Verhalten in drei Punkten — `docs/privacy.html:84`
- [S] Vier deklarierte Permissions ohne Code-Nutzung (`DISABLE_KEYGUARD`, `CHANGE_WIFI_MULTICAST_STATE`, `MODIFY_AUDIO_SETTINGS`, `ACCESS_NOTIFICATION_POLICY`) — `AndroidManifest.xml:67`
- [S] „Logs senden“ verschickt PII-haltige Logs an eine Trashmail-Adresse ohne Einwilligungshinweis — `util/LogEmailUtil.kt:18`

**Architektur (9)**
- [S] `MainActivity.onDestroy`: totes, aber gefährliches Cleanup (`onCleared()` von außen, Coroutine auf gecanceltem Scope, `System.gc()`) — `MainActivity.kt:259`
- [M] Handgestricktes State-Update-Batching in CalendarViewModel kann Updates verlieren — `viewmodel/CalendarViewModel.kt:175`
- [S] mDNS-Discovery kann konstruktionsbedingt nie Bridges zurückgeben — lokaler Fallback tot — `hue/discovery/HueMdnsDiscoveryService.kt:63`
- [S] Custom-Navigation ohne System-Back-Behandlung — Zurück-Geste beendet die App auf Unterscreens — `ui/screens/MainScreen.kt:118`
- [M] Hue-Regel-Editor hält ~20 Formularfelder nur in `remember` — Datenverlust bei Rotation/Prozess-Tod — `ui/screens/hue/HueRuleConfigScreen.kt:106`
- [M] Zwei DI-Welten: Hilt-Bindings existieren, aber UI/Repos/ViewModel holen Singletons per `getInstance()` daran vorbei — `ui/screens/tabs/HueTabContent.kt:416`
- [M] Tote Feature-/Fix-Schichten: ungenutzter Auth-SharedPrefs-Kanal (inkl. Reflection-Hack), tote Hue-Duration-API, halbtoter CalendarStateHolder — `viewmodel/AuthViewModel.kt:457`
- [S] ViewModels casten UseCase-Interfaces auf konkrete Implementierungen — Interface-Architektur umgangen — `viewmodel/AuthViewModel.kt:550`
- [M] Systematische Timing-Hacks (`delay` als Synchronisation) statt reaktiver Abhängigkeiten — `viewmodel/CalendarViewModel.kt:755`

**UX (5)**
- [M] Alarm-Screen: Stoppen/Snooze kleben ohne Abstand aneinander, Padding in Pixeln statt dp, kein fehlbediensicheres Pattern — `AlarmFullScreenActivity.kt:276`
- [S] Dark Mode unvollständig: XML-Theme ist Light-only (kein DayNight/values-night) — weißer Start-Blitz, helle System-Dialoge — `res/values/themes.xml:6`
- [S] Abmelden, Schichttyp-/Hue-Regel-Löschen und Config-Reset ohne Bestätigung — `ui/screens/ShiftConfigScreen.kt:267`
- [S] `POST_NOTIFICATIONS` wird beim allerersten Start vor dem Login ohne Begründung abgefragt; Ablehnung endet in Sackgassen-Toast — `MainActivity.kt:91`
- [S] Home-Tab zeigt keinen Countdown zum nächsten Alarm; fertige `CountdownTimer`-/`NoAlarmCard`-Komponenten sind toter Code — `ui/components/CountdownTimer.kt:65`

**Infrastruktur (6)**
- [M] Kern-Alarm-Kette (Delta-Sync, Skip-Logik, Persistenz) komplett ungetestet — blockiert durch fehlendes Interface für `AlarmManagerService` — `usecase/AlarmUseCase.kt:45`
- [S] `MainActivityTest` testet eine UI, die nicht mehr existiert — würde bei Ausführung fehlschlagen — `androidTest/…/MainActivityTest.kt:38`
- [S] Privacy Policy behauptet AES-256-GCM-Speicherung der Kalenderdaten — tatsächlich nur unverschlüsselt im RAM-Cache — `docs/privacy.html:66`
- [M] Für den offenen Test fehlen 3 von 4 Play-Console-Deklarationen — nur specialUse-FGS vorbereitet — `AndroidManifest.xml:59`
- [M] `proguard-rules.pro` enthält `-dontshrink/-dontoptimize` + Pauschal-Keeps + tote Firebase-Regeln — Minify-Reaktivierung wirkungslos/fehleranfällig — `app/proguard-rules.pro:352`
- [S] Crash-Diagnose skaliert nicht über Solo-Betrieb hinaus: `last_crash.txt` wird geschrieben, aber nie proaktiv angeboten — `CFAlarmApplication.kt:142`

### P3 (25)

**Alarm (2)**
- [S] Skip-Notification-Channel wird nie erstellt; Extra-Key-Mismatch lässt Weckzeit in Notification/Activity leer — `AlarmReceiver.kt:339`
- [M] Alarm klingelt unbegrenzt (`isLooping` ohne Timeout), Notification verschwindet aber nach 5 Minuten — `service/AlarmSoundService.kt:223`

**Nebenläufigkeit (1)**
- [S] HueSmartScheduler: Pre-Alarm-Checks mit falschem Unique-Namen gecancelt; nach Bridge-Trennen bleibt der Alarm-Observer bis Prozess-Neustart tot — `hue/scheduling/HueSmartScheduler.kt:240`

**Sicherheit (6)**
- [M] Hue-TLS: TOFU-Pinning faktisch wirkungslos — Mismatch loggt nur und überschreibt den Pin sofort; beliebige self-signed Zertifikate akzeptiert — `hue/network/HueTrustManager.kt:294`
- [S] Google Sign-In ohne Nonce (`setNonce(null)`) — `auth/CredentialAuthManager.kt:53`
- [S] Toter unverschlüsselter Token-Speicherpfad: `@TokenDataStore`-Provider ungenutzt, Klartext-Token-Keys in `auth_prefs` — `di/modules/DataModule.kt:51`
- [S] Network-Security-Config: IP-Präfix-„Domains“ wirkungslos — die gesamte Hue-domain-config greift nie — `res/xml/network_security_config.xml:58`
- [S] Debug-Logs persistieren 20-Zeichen-Token-Präfix und volle JWT-Payload in die Log-Datei — `usecase/AuthUseCase.kt:75`
- [S] BootReceiver: `PACKAGE_REPLACED`-Intent-Filter ist totes Gewicht — `AndroidManifest.xml:117`

**Architektur (5)**
- [S] AuthViewModel: „collect once“-Kommentar trifft nicht zu — Initial-Checks laufen bei jeder AuthData-Emission erneut — `viewmodel/AuthViewModel.kt:148`
- [M] Fehlermodell zweigeteilt: Core nutzt `AppError`/`SafeExecutor`, Hue-Schicht generische Exceptions + Erfolgs-Flags in `Result.success` — `hue/usecase/HueLightUseCase.kt:117`
- [M] Onboarding-Entscheidungslogik (Battery→OEM→Maintenance-Start) dreifach in MainScreen dupliziert — `ui/screens/MainScreen.kt:131`
- [L] Datei-Granularität: die großen ViewModels bündeln 4–6 Fremdrollen — Splitten überwiegend mechanisch, bei CalendarViewModel riskant — `viewmodel/CalendarViewModel.kt:80`
- [M] Kommentar-/Logging-Rauschen: 829 Emoji-Zeilen, 132 PHASE/CRITICAL-FIX-Marker, Changelog-Prosa im Code — `viewmodel/AlarmViewModel.kt:537`

**UX (6)**
- [S] StatusTab färbt legitime Leerzustände rot: „Keine Schichten erkannt“/„Keine aktiven Alarme“ erscheinen als Fehler — `ui/screens/tabs/StatusTabContent.kt:108`
- [S] Inkonsistente Anrede: Du-Form im Kernprodukt, Sie-Form in Hue-Screens/UIText/OEM-Dialogen — `ui/screens/tabs/HueTabContent.kt:662`
- [L] Lokalisierung nicht vorbereitet: `strings.xml` mit nur 8 Strings, daneben UIText-Konstanten + 110+ Inline-Literale — `res/values/strings.xml:1`
- [M] Design-System halbfertig: Theme unverändertes Studio-Template (Purple/Pink), Typografie nur `bodyLarge`, Tokens nur in halber Screen-Menge genutzt — `ui/theme/Color.kt:5`
- [S] EventListScreen markiert Schichten mit eigener hardcodierter Keyword-Heuristik statt der ShiftConfig und zeigt Debug-Kalender-IDs — `ui/screens/EventListScreen.kt:330`
- [S] Alarm-Dismiss ruft `notificationManager.cancelAll()` und löscht alle Notifications der App — `AlarmFullScreenActivity.kt:379`

**Infrastruktur (5)**
- [S] `TestSuite.kt` listet nur 2 von 8 Testklassen; `StateSynchronisationTest` testet Kotlin-Sprachmechanik statt Produktionslogik — `test/…/TestSuite.kt:17`
- [S] `lint-baseline.xml` (301 Zeilen, 27× FullBackupContent) existiert, wird aber in `build.gradle.kts` nicht referenziert — `app/lint-baseline.xml:1`
- [S] `google-api-services-calendar` auf Discovery-Revision von Juli 2022 eingefroren — `gradle/libs.versions.toml:36`
- [S] Gson und kotlinx.serialization parallel im Einsatz — Konsolidierung möglich, geringer Nutzen — `gradle/libs.versions.toml:5`
- [S] `gradle.properties` enthält mehrere wirkungslose/nicht existierende Properties (Verdacht: Cargo-Cult) — `gradle.properties:42`

---

*Erstellt in Phase 1 (read-only). Kein Code wurde geändert, kein Commit erstellt, nichts gepusht. Die adversariale Verifikationsstufe ist ausgabelimit-bedingt ausgefallen; die 5 P0 und die folgenschwersten P1 wurden vom Autor manuell am Code gegengeprüft (0 Fehlbefunde in der Stichprobe).*
