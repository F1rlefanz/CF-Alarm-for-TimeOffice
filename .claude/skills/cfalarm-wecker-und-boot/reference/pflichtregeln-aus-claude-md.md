# Pflichtregeln aus der CLAUDE.md, mit Begründung

Die CLAUDE.md nennt je Regel nur den Kernsatz, damit sie immer geladen bleibt. Hier steht der
volle Wortlaut, wie er bis zur Kürzung dort stand. Gilt immer; Änderungen an einer Regel
betreffen beide Stellen.

## Inhalt

- Alarme anlegen, ändern, löschen
- Wecken, Vollbild, Snooze
- Hintergrundketten und Boot
- Master-Pause

## Alarme anlegen, ändern, löschen

- **Löschen heißt IMMER: erst `cancelSystemAlarm()`, dann `deleteAlarm()`.** Umgekehrt entsteht ein
  armierter Alarm, den weder Repository noch Direct-Boot-Spiegel kennen — unsichtbar UND
  unabbrechbar bis zum nächsten Neustart.
- **`clearInternalAlarms()` fragt ZUERST `isPersistenceBlocked()` und scheitert laut.** Räumen ohne
  Cancellen ist die gefährliche Kombination.
- **Die datengetriebenen Räumzweige von `syncAlarms()` schonen MANUELLE Alarme**
  (`keepManualAlarms = true`) — nur der manuelle Alarm lässt sich nicht aus dem Kalender
  rekonstruieren. Ausdrückliche Abschaltungen räumen weiter ALLES.
- **Der Delta-Sync hat pro Event ein eigenes `try/catch`, das `CancellationException` weiterwirft** —
  sonst bricht ein einzelner abgelehnter Alarm den gesamten Sync ab.
- **Die Wecker-Identitaet haengt NICHT allein an der Kalender-Kennung.** Ein abonnierter
  Dienstplan-Feed bekommt von Google alle paar Tage neue Event-IDs fuer dieselben Termine.
  `syncAlarms()` paart deshalb ERST (Kennung, sonst Weckzeit + Schicht) und
  entscheidet DANN; bei reinem Kennungswechsel wird die neue Kennung still uebernommen, die
  `AlarmInfo.id` bleibt. Dieselbe Frage stellt die Boot-Wiederherstellung - sonst loescht ein
  Neustart im Rotationsfenster den GESAMTEN Bestand.
- **Die Skip-Erkennung nimmt den WECKZEITPUNKT als Anker, nicht nur die id** (in beiden Gates,
  auch im Backstop). `skipNextAlarm()` loescht den Eintrag; nach einer Kennungsrotation gaebe es
  sonst nichts mehr zu paaren, und der uebersprungene Wecker klingelte am freien Morgen.
- **Verstrichene Weckzeit ist KEINE entfernte Schicht** (`expiredEventIds`).
- **Alle `setAlarmClock()`-Aufrufstellen fangen die entzogene Exact-Alarm-Berechtigung**
  (`setExactOrInexact`: try/catch + inexakter Fallback). Ein verzögerter Wecker schlägt keinen Wecker.
- **Das Skip-Flag läuft zeitbasiert ab, nicht per ID-Match** — plus Gate in `syncAlarms()` UND
  Backstop in `scheduleSystemAlarm()`.
- **`ShiftConfig.autoAlarmEnabled = false` ist eine ECHTE, sofortige Pause**, kein stilles `return`.
- **Ein freigegebener Tag („Tag freigeben") braucht Gate UND Backstop — und nimmt MANUELLE Wecker
  aus.** Der Anker ist überall die Weckzeit (vier Stellen). Ohne den Backstop holt der
  `BootReceiver` den Wecker nach einem Neustart zurück; ohne die Ausnahme für manuelle Wecker
  löscht die Freigabe einen Wecker, den kein Kalender rekonstruieren kann — und der Backstop
  verhindert zusätzlich, dass an dem Tag überhaupt einer gestellt werden kann. Auch ein
  schwebender Snooze (Anker: die Weckzeit im Snooze-Merker).
- **Freigaben: Hand + Krank laut Kalender, `freieTage` liefert die VEREINIGUNG** (#142) - jeder
  Leser liest nur sie; den Kalender-Schlüssel schreibt NUR `syncAlarms()`. Hergang im Wecker-Skill.

## Wecken, Vollbild, Snooze

- **`_alarmActive = true` VOR `startForeground()`** — sonst schließt sich das Vollbild sofort.
- **Kein `startActivity()` aus dem `AlarmReceiver`** — einziger Weg ist `setFullScreenIntent()`.
- **Eine Instanz besitzt den Wecker**: `AlarmSoundService` hält Ton, Vibration und die einzige
  Wecker-Notification (ID 2002). Der `AlarmReceiver` darf **keine eigene** posten.
- **`AlarmSoundService`: `stopSelf(startId)` und `START_REDELIVER_INTENT`** — nie blankes
  `stopSelf()`/`START_STICKY` (stummer Zombie-Service bei sonst normal aussehendem Log).
- **Vollbild-Dismiss und -Snooze teilen eine Einweg-Sperre** (`OneShotAlarmHandoff.claim()`).
- **In `onNewIntent()` wird ERARBEITETER Zustand nur bei einem ANDEREN Weckvorgang verworfen.**
  Aus dem Intent abgeleitete Werte werden immer frisch gelesen; ein Fehlerhinweis und die
  Einweg-Sperre gehoeren dagegen zum laufenden Vorgang. Die Wecker-Notification traegt denselben
  PendingIntent auch als `setContentIntent()` — ein Tipp darauf ist DERSELBE Wecker und darf einen
  Schlummer-Fehlerhinweis nicht wegwischen. Vergleich ueber die Alarm-Kennung.
- **`AlarmFullScreenActivity` braucht `onNewIntent()` mit `setIntent()`** (`launchMode="singleTask"`).
- **Snooze braucht `snoozeAlarmAction(id)`**, nicht `enhancedAlarmAction(id)`.
- **Ein AUFGEGEBENER Armierungsversuch hinterlaesst nichts Scharfes.** Scheitert das Vormerken
  NACH dem Planen, wird der bereits gestellte Alarm abgebrochen, bevor der Fehlschlag gemeldet
  wird — sonst steht er scharf im AlarmManager, waehrend die App „kein weiterer Weckruf" sagt, und
  ohne Merker kann ihn niemand mehr abbrechen. Der WIEDERHERSTELLUNGS-Lauf nach einem Neustart
  raeumt dabei nichts weg: dort ist der Merker die Vorlage, nicht das Ergebnis.
- **Ein schwebender Snooze muss einen Reboot überleben** (`restorePendingSnoozes()`), und beide
  Anlässe armieren über dasselbe `armSnooze()` — sonst trifft ein späterer Abbruch ihn nicht mehr.
- **Der Snooze-Merker ist serialisiert und schreibt mit `commit()`**, nicht `apply()`.
- **Der Schlummer-Read in `AlarmReceiver` MUSS hinter `userUnlocked` gegated sein** — `AlarmPrefs`
  liegt im CE-Storage, ungegatet bleibt der Wecker bei Direct Boot komplett stumm.
- **Stille Schicht (`isSilent`) gated NUR die Auslösung.** Fail-safe: Lookup-Fehler = NICHT still.
- **„Deine Schicht beginnt um" zeigt `AlarmInfo.shiftStartTime`, nicht `triggerTime`.**
- **Wer die Wichtigkeit eines Notification-Kanals anhebt, braucht eine NEUE Kanal-ID.** Android
  ändert die Importance eines bestehenden Kanals nur nach UNTEN und ignoriert alle übrigen Felder;
  ein unter derselben ID neu angelegter Kanal kommt mit seinen ALTEN Einstellungen zurück. Ein
  frisch installiertes Gerät zeigt das NIE.
- **Die Schlummer-Beschriftung kommt aus derselben Variablen wie `scheduleSnooze()`**, nie aus
  einem festen Text — sonst verspricht der Knopf am Weckbildschirm eine andere Dauer, als er
  schlummert.
- **Blockierte Benachrichtigungen sind ein Wecker ohne Oberfläche** — deshalb die Status-Karte davor
  und ein WARN direkt nach `startForeground()`.
- **`visibilitySnapshot()` ist Diagnostik, die im Release-Log landen MUSS** (WARN).
- **Der Weckton startet sofort, nie im verzoegerten Vorweck-Zweig.** Bei dunklem, gesperrtem
  Bildschirm wartet ausschliesslich `startForeground()` mit der FSI-Notification (600 ms, siehe
  `VorweckEntscheidung`). Wer den Ton mit verzoegert, verzoegert den Weckruf selbst.
- **Die Vorweck-Bedingung liest NUR Systemzustand, nie etwas Gespeichertes** — ein Merker im
  CE-Storage ist im Direct Boot nicht lesbar; Hergang im Wecker-Skill.

## Hintergrundketten und Boot

- **Kein Hintergrund-Paket importiert `ui.` oder `viewmodel.`** (nach unten greifen ist frei, nur
  die Gegenrichtung ist gesperrt). `AlarmReceiver`, `AlarmSoundService`, `BootReceiver` und die
  6h-Wartung laufen bei gesperrtem Gerät und im Direct-Boot-Prozess, ohne sichtbare Activity — eine
  Referenz von dort in die Oberfläche ist der kurze Weg zu einem Leak oder Klassenauflösungsfehler,
  den kein Unit-Test sieht. Geprüft von `tools/invarianten/pruefe_code.py`.
- **NICHTS am Application-Graphen darf WorkManager oder CE-Storage beim BAUEN anfassen.** Der Graph
  wird auch im Direct-Boot-Prozess aufgebaut; ein Wurf dort tötet den Prozess, und die
  Wiederherstellung der Alarme läuft NIE. **Kein Unit-Test fängt das** — die Prüfung ist ein echter Reboot ohne Entsperrung:
  `python tools/geraet/pruefe_direct_boot.py` (nur Emulator, verweigert jedes andere Ziel).
- **Kein `getSharedPreferences()`/CE-Zugriff in einem Property-Initializer** einer Klasse am
  Application-Graphen. Der Zugriff ist harmlos, der ZEITPUNKT nicht.
- **Die 6h-Wartungskette hat GENAU einen Planer** (`scheduleNext()`, ein Request-Code). Wer
  „sicherheitshalber" nachplant, erzeugt zwei parallele Zyklen.
- **Deren `finally`-Block läuft in `withContext(NonCancellable)`** — sonst stirbt die rollierende
  Kette bis zum nächsten Boot.
- **`AlarmMaintenanceService`: `stopSelf(startId)`**, niemals blankes `stopSelf()`.
- **`AlarmMaintenanceService.start()` fängt den abgelehnten Vordergrund-Start selbst**, nicht die
  Aufrufer — eine Exception aus `onReceive()` reißt den Prozess mit.
- **Die 6h-Wartung MUSS Änderungen und Streichungen sehen können** und synchronisiert **immer**,
  sobald Events vorliegen. Die Leerlisten-Sperre bleibt.
- **`TimezoneChangeReceiver` startet die Wartung mit `forceSync=true`** — ein Re-Arming wäre kein
  Ersatz, die Weckzeit ist eine Wanduhrzeit auf dem Kalendertag.
- **Die Hue-Regelausführung im `AlarmReceiver` ist gedeckelt** (`HUE_EXECUTION_BUDGET_MS = 45 s`).
  Nicht kleiner machen: der Batch-Timeout EINER Regel ist schon 30 s, und ein zu knapper Deckel
  lässt das Licht an, ohne dass der Auto-Aus-Zeitplan je entsteht.
- **Die stündliche Rufbereitschafts-Abfrage (`RufbereitschaftAbfrage`) ist eine EIGENE Kette**,
  die nur `AlarmMaintenanceService.start(forceSync = true)` anstößt — kein zweiter Planer der
  6h-Kette. Quelle ist `ShiftDefinition.isOnCall` (auch für den DND-Cutoff). Hergang im
  Kalender-Skill.

## Master-Pause

- **`syncAlarms()` hat einen zentralen Master-Pause-Backstop**, nicht nur Gates an den Aufrufstellen —
  als erste inhaltliche Prüfung innerhalb von `SafeExecutor.safeExecute`.
- **Denselben Backstop haben `DimScheduleUseCase.enable()` und `DndScheduleUseCase.enable()`.**
  `disable()` bleibt ungegatet, sonst kommt `pause()` nicht mehr durch.
- **`pause()`/`resume()` laufen in `withContext(NonCancellable)`** — sie stellen einen Zustand HER.
- **Der Pausen-Spiegel wird beim App-Start mit der CE-Wahrheit abgeglichen.**
