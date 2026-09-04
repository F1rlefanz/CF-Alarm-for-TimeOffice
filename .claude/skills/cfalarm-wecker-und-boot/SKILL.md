---
name: cfalarm-wecker-und-boot
description: Zusicherungen fuer Weckerausloesung, Vollbild-Weckbildschirm, Snooze, AlarmManager-Planung, BootReceiver, Direct Boot und die 6h-Wartungskette der CFAlarm-Wecker-App. Nennt die Reihenfolge beim Loeschen von Alarmen, die Regeln fuer AlarmSoundService und AlarmFullScreenActivity, das Snooze-Register, den Skip-Mechanismus und die Master-Pause. Zu verwenden bei Arbeit an AlarmReceiver, AlarmSoundService, AlarmFullScreenActivity, AlarmManagerService, AlarmMaintenanceService, BootReceiver, AlarmUseCase.syncAlarms oder MasterPauseUseCase — und immer dann, wenn ein Wecker nicht klingelt, stumm bleibt, doppelt feuert, nach einem Neustart fehlt, sich nicht abbrechen laesst oder der Vollbild-Weckbildschirm verschwindet.
---

# Wecker, Boot und Master-Pause

Unten stehen die **Kurzregeln** dieses Bereichs — was gilt, und was bei Bruch passiert.
Die wecker-kritische Teilmenge davon steht zusätzlich in `CLAUDE.md` (dort immer geladen, als
Sicherheitsnetz für den Fall, dass dieser Skill nicht anspringt); **alles Übrige steht
ausschließlich hier.** **Reicht die Kurzregel nicht, oder willst du eine davon ändern oder
umgehen: lies vorher die Hergang-Datei.** Dort steht, welcher Bug die Regel erzwungen hat — ohne
das baut man dieselbe Falle in neuer Form nach.

## Hergang und Belege

- `reference/wecker-boot-und-wartung.md` — Wecker, Vollbild, Snooze, Boot, Direct Boot, 6h-Wartung
- `reference/tag-freigeben.md` — "Tag freigeben": Gate, Backstop, manuelle Wecker, Direct-Boot-Grenze
- `reference/master-pause.md` — die Master-Pause und ihre Backstops
- `reference/vorwecken.md` — warum der Wecker den Bildschirm selbst weckt (FP6-Verdraengung)

---

## Kurzregeln

- **Löschen heißt IMMER: erst `cancelSystemAlarm()`, dann `deleteAlarm()`.** Umgekehrt entsteht ein
  armierter Alarm, den weder Repository noch Direct-Boot-Spiegel kennen — unsichtbar UND
  unabbrechbar bis zum nächsten Neustart.
- **Eine Instanz besitzt den Wecker**: `AlarmSoundService` hält Ton, Vibration, Audio-Fokus und die
  einzige Wecker-Notification (ID 2002, Channel stumm, aber `IMPORTANCE_HIGH`). Der `AlarmReceiver`
  darf **keine eigene Wecker-Notification** posten. Ausgenommen: die stille Skip-Bestätigung (ID 9999).
- **`AlarmSoundService`: `stopSelf(startId)` und `START_REDELIVER_INTENT`** — nie blankes
  `stopSelf()`/`START_STICKY`. Der Weckton probiert alle Ringtone-Kandidaten und loggt Totalausfall laut.
- **`_alarmActive = true` VOR `startForeground()`** — sonst schließt sich das Vollbild sofort.
- **Kein `startActivity()` aus dem `AlarmReceiver`** — einziger Weg ist `setFullScreenIntent()`.
- **Vollbild-Dismiss und -Snooze teilen eine Einweg-Sperre** (`OneShotAlarmHandoff.claim()`, am
  Anfang BEIDER Handler). Der Notausgang `stopAndClose()` fragt sie bewusst NICHT. Die Sperre
  gehört dem WECKVORGANG, nicht der Activity-Instanz.
- **`AlarmFullScreenActivity` braucht `onNewIntent()` mit `setIntent()`** (`launchMode="singleTask"`).
- **In `onNewIntent()` wird ERARBEITETER Zustand nur bei einem ANDEREN Weckvorgang verworfen**
  (`Weckvorgang.istAnderer`, Vergleich über die Alarm-Kennung; fehlende Kennung = derselbe
  Vorgang). Aus dem Intent abgeleitete Werte dagegen immer frisch lesen. Die Wecker-Notification
  trägt denselben PendingIntent auch als `setContentIntent()` — ein Tipp darauf ist DERSELBE
  Wecker und darf den Schlummer-Fehlerhinweis nicht wegwischen.
- **`visibilitySnapshot()` ist Diagnostik, die im Release-Log landen MUSS** (WARN). Herabstufen macht
  den nächsten Vorfall unauswertbar. Die Ursache des verschwindenden Vollbilds ist weiterhin unbelegt.
- **Alle `setAlarmClock()`-Aufrufstellen behandeln eine entzogene Exact-Alarm-Berechtigung gleich**
  (`AlarmManagerService.setExactOrInexact`: try/catch + inexakter Fallback).
  `requestExactAlarmPermission()` gehört NICHT in diesen Pfad.
- **Snooze braucht `snoozeAlarmAction(id)`**, nicht `enhancedAlarmAction(id)`.
- **Jedes Armieren eines Schlummers geht durch `SchlummerEntscheidung.armiere()`** — dort sitzt der
  Master-Pause-Backstop (zentral, nicht je Auslöser), und dort gilt die Reihenfolge erst `plane`,
  dann `merke`.
- **Ein AUFGEGEBENER Armierungsversuch hinterlässt nichts Scharfes**: scheitert das Vormerken nach
  dem Planen, räumt `gibAuf()` den Alarm ab, BEVOR der Fehlschlag gemeldet wird. Drei Ausgänge
  (`ABGERAEUMT`/`MISSLUNGEN`/`BEWUSST_BEHALTEN`) — der Wiederherstellungslauf nach einem Neustart
  räumt NICHT zurück, dort ist der Merker die Vorlage.
- **Ein gescheitertes Schlummern muss unterscheidbar sein**: `armSnooze()`-Ergebnis auswerten, erst
  planen und dann den Ton stoppen, Activity im Fehlerfall offen lassen. Der Titel gehört zum
  Ergebnis (`TITEL_PAUSE`/`TITEL_FEHLER`/`TITEL_UNKLAR`), nicht zur Renderstelle — eingeklappt am
  Sperrbildschirm ist er die einzige vollständig gelesene Zeile.
- **Ein schwebender Snooze ist abbrechbar, aber nur auf ausdrücklichen Nutzer-Willen** — nicht in
  datengetriebenen Aufräumzweigen und nicht an `deleteAlarm(id)`.
- **Ein schwebender Snooze muss einen Reboot überleben** (`restorePendingSnoozes()` im `BootReceiver`).
  Der zweiteilige Altbestand (`id|triggerTime`) MUSS lesbar bleiben; beide Anlässe armieren über
  dasselbe `armSnooze()` (identischer PendingIntent), hinter demselben Master-Pause-Gate.
- **Der Snooze-Merker ist serialisiert (`snoozeRegistryLock`) und schreibt mit `commit()`**, nicht
  `apply()`. `armSnooze()` gibt Erfolg zurück, `restorePendingSnoozes()` zählt ECHTE Erfolge.
- **Schlummer-Dauer ist EINE Quelle für beide Auslöser**: `AlarmReceiver` liest sie einmal pro Feuern
  und reicht sie als Intent-Extra durch. **Dieser Read MUSS hinter `userUnlocked` gegated sein.**
- **`clearInternalAlarms()` fragt ZUERST `isPersistenceBlocked()` und scheitert laut.** Der Wächter
  unterscheidet, WARUM geräumt wird: datengetrieben → nichts anfassen; ausdrückliche Abschaltung →
  `cancelAllSnoozes()` + `deleteAllAlarms()` (dokumentierte `force`-Ausnahme) laufen weiter.
- **Die datengetriebenen Räumzweige von `syncAlarms()` schonen MANUELLE Alarme**
  (`keepManualAlarms = true`). Ausdrückliche Abschaltungen räumen weiter ALLES.
- **Ein manueller Alarm lässt sich nicht anlegen, während „Automatische Alarme" aus ist.**
- **`ShiftConfig.autoAlarmEnabled = false` ist eine ECHTE, sofortige Pause** — `syncAlarms()` ruft
  dort `clearInternalAlarms()`, und `ShiftViewModel` zusätzlich `deleteAllAlarms()`.
- **Das Skip-Flag läuft zeitbasiert ab, nicht per ID-Match** (`skippedAlarmTriggerTime` +
  `clearExpiredSkip()`, aufgehängt an `syncAlarms()`). Dazu ein Gate in `syncAlarms()` UND ein
  Backstop in `scheduleSystemAlarm()`.
- **„Tag freigeben" braucht Gate UND Backstop, und beide nehmen MANUELLE Wecker aus**
  (`eventId.isEmpty()`). Tagesanker ist überall die WECKZEIT — in `FreieTageStore.tagVon`,
  `TagFreigabeUseCase.gehoertZuTag`, `AlarmUseCase.istTagFreigegeben` und
  `berechneWeckerAnzeige`. Hergang in `reference/tag-freigeben.md`.
- **Stille Schicht (`isSilent`) gated NUR die Wecker-AUSLÖSUNG**, nicht die `AlarmInfo` selbst;
  `alarmTime` bleibt ein nicht-nullables Pflichtfeld. Fail-safe: Lookup-Fehler = NICHT still.
- **„Deine Schicht beginnt um" zeigt `AlarmInfo.shiftStartTime`, nicht `triggerTime`.** Die Falle
  liegt im Re-Arming-Pfad `scheduleSystemAlarm()`, nicht in der Erstplanung.
- **Der Delta-Sync hat pro Event ein eigenes `try/catch`, das `CancellationException` weiterwirft.**
- **Verstrichene Weckzeit ist KEINE entfernte Schicht** (`expiredEventIds`) — sonst meldet die App
  jeden Schichtmorgen „Schicht entfernt" für den Dienst, den der Nutzer gerade antritt.
- **Die 6h-Wartungskette hat GENAU einen Planer: `scheduleNext()`, auf genau einem Request-Code.**
  Wer „sicherheitshalber" nachplant, erzeugt zwei parallele Zyklen.
- **Deren `finally`-Block läuft in `withContext(NonCancellable)`** und fängt den Master-Pause-Read;
  dort liegen auch Dimmer-, DND- und Pre-Alarm-Reschedule.
- **`AlarmMaintenanceService`: `stopSelf(startId)`**, niemals blankes `stopSelf()`.
- **`AlarmMaintenanceService.start()` fängt den abgelehnten Vordergrund-Start selbst** (nicht die
  Aufrufer) und setzt einen einmaligen Nachhol-Alarm auf **eigenem** Request-Code.
- **Die 6h-Wartung MUSS Änderungen und Streichungen sehen können** — Laden bei Puffer < 7 Tage ODER
  letzter Abfrage ≥ 12 h ODER nächstem Alarm ≤ 48 h, und danach **immer** synchronisieren. Eigener
  Frische-Stempel `last_event_load_time`. Die Leerlisten-Sperre bleibt.
- **`BootReceiver` liest die Kalenderauswahl über den DataStore**, nicht über den noch nicht
  hydrierten `StateFlow`, und setzt **vor** der langen Recovery einen Wartungs-Anker.
- **`TimezoneChangeReceiver` startet die Wartung mit `forceSync=true`** — ein bloßes Re-Arming wäre
  kein Ersatz (es rechnet dieselben Millis hin und zurück).
- **NICHTS am Application-Graphen darf WorkManager oder CE-Storage beim BAUEN anfassen.** Der Graph
  wird auch im Direct-Boot-Prozess aufgebaut. Deshalb `MasterPauseUseCase` als `dagger.Lazy` und
  WorkManager-Auflösung erst beim Gebrauch. **Kein Unit-Test fängt das** — die Prüfung ist ein echter Reboot ohne Entsperrung:
  `python tools/geraet/pruefe_direct_boot.py` (nur Emulator, verweigert jedes andere Ziel).
- **Kein `getSharedPreferences()`/CE-Zugriff in einem Property-Initializer** einer Klasse am
  Application-Graphen (`BackgroundServiceManager`, `HueBridgePinningStore`: beide `by lazy`).
- **Ein Emulator OHNE Bildschirmsperre kann Direct Boot NICHT prüfen.** Vor jedem Direct-Boot-Test
  `adb shell locksettings set-pin 1234` und nach dem Reboot NICHT entsperren.
  (Testverfahren im Skill `cfalarm-bauen-und-testen`.)
- **Blockierte Benachrichtigungen sind ein Wecker ohne Oberfläche**: `NotificationsEnabledCard` steht
  VOR `FullScreenIntentCard`, und `AlarmSoundService` loggt direkt nach `startForeground()` ein WARN.
- **Die Hue-Regelausführung im `AlarmReceiver` ist gedeckelt** (`withTimeoutOrNull`,
  `HUE_EXECUTION_BUDGET_MS = 45 s`), weil `pendingResult.finish()` erst danach kommt. Nicht kleiner
  machen: allein der Batch-Timeout einer einzigen Regel ist 30 s, und ein zu knapper Deckel lässt das
  Licht an, ohne dass der Auto-Aus-Zeitplan je entsteht.
- **Zwei Scopes rufen bewusst NIE `.cancel()`**: `AlarmReceiver.receiverScope` und
  `CalendarSelectionRepository.repositoryScope`. Gegenstück: `HueLightUseCase.followUpScope`.
- **`WakeLockManager`/`IWakeLockManager` sind ENTFERNT** (v1.23.1). Die Wake-Locks des echten
  Weckvorgangs liegen in `AlarmReceiver` (PARTIAL) und `AlarmFullScreenActivity` (SCREEN_BRIGHT) —
  dort suchen, nicht nach einer zuständig klingenden Klasse.

## Vorwecken — Kurzregeln

Seit 1.39.3. Hergang, Messwerte und die widerlegten Saetze: `reference/vorwecken.md`.

- **Der Ton startet IMMER sofort, nie im verzoegerten Zweig.** Verzoegert wird ausschliesslich
  `startForeground()` mit der FSI-Notification. Der Ton ist der Wecker; wer ihn mit verzoegert,
  verzoegert den Weckruf selbst.
- **Das Gate ist [WeckbildschirmVerdraengungPrefs.jeVerdraengt], NICHT der Hinweis-Zaehler.**
  Der Zaehler beantwortet "passiert es gerade" und wird bei jedem sauberen Wecker
  zurueckgestellt; das Gate beantwortet "ist dieses Geraet betroffen" und bleibt. Wer beides
  wieder zusammenlegt, laesst die Massnahme sich durch ihren eigenen Erfolg abschalten — genau
  das ist am 04.09.2026 zweimal hintereinander passiert.
- **Jeder Fehler beim Ermitteln der Eingaben fuehrt zu Vorlauf 0**, also zum unveraenderten
  Verhalten. Ein nicht lesbarer Merker darf keinen Wecker verzoegern.
- **Vorgeweckt wird nur bei dunklem UND gesperrtem Bildschirm.** Ist der Bildschirm an, gibt es
  kein Aufwecken und damit keine Gesichtsentsperrung, die verdraengen koennte; ohne Keyguard
  entscheidet SystemUI ohnehin auf Heads-up statt Vollbild.

## Master-Pause — Kurzregeln

- **Eigenständig neben `autoAlarmEnabled`** — `pause()`/`resume()` rühren den Flag NICHT an, sondern
  rufen direkt `alarmUseCase.deleteAllAlarms()`.
- **`syncAlarms()` hat einen zentralen Master-Pause-Backstop**, nicht nur Gates an den Aufrufstellen —
  als erste inhaltliche Prüfung innerhalb von `SafeExecutor.safeExecute`. Die Einzel-Gates bleiben
  zusätzlich (sparen unnötige Fetches), sind aber nicht mehr die einzige Verteidigungslinie.
- **Denselben Backstop haben `DimScheduleUseCase.enable()` und `DndScheduleUseCase.enable()`.**
  `disable()` bleibt bewusst ungegatet, sonst kommt `pause()` nicht mehr durch.
- **`pause()` stoppt auch einen gerade KLINGELNDEN Wecker** (`ACTION_STOP_ALARM` an den
  `AlarmSoundService`, unbedingt und nicht auf `alarmActive` gegated, eigenes try/catch).
- **`pause()`/`resume()` laufen in `withContext(NonCancellable)`** — beide stellen einen Zustand HER,
  und der Schalter wird als erstes geschrieben.
- **Der Pausen-Spiegel wird beim App-Start mit der CE-Wahrheit abgeglichen**
  (`reconcileDirectBootMirror()`) — `savePaused()` schluckt seinen Fehler.
- **`HueSmartScheduler.initializeSmartScheduling()` läuft bei JEDEM Kaltstart**; der Master-Pause-Check
  steht als allererste Prüfung INNERHALB der Coroutine.
- **`BackgroundServiceManager.initializeMaintenanceService()` ist `suspend`** und prüft die Pause zuerst.
- **`DimScheduleUseCase.disable()`/`DndScheduleUseCase.disable()` rühren KEINE persistierten Toggles an.**
- **Die Master-Pause überlebt weder Gerätewechsel noch Konfigurations-Import** — beides Absicht.
