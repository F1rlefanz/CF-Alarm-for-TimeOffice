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
  **Im KALT gestarteten Prozess fehlt die `AlarmInfo`** (der Bestand wirft den gerade klingelnden
  Wecker beim Laden weg) - dann entscheidet die AKTUELLE Schichtdefinition zum Intent-Schichtnamen,
  exakt verglichen (`AlarmReceiver.isSilentAlarm`, seit 1.52.1; vorher klingelte eine stille Schicht
  nach naechtlichem Prozess-Ende). **Kein Intent-Extra "still"**: es koennte veralten und einen Wecker
  stumm halten, der wieder klingeln soll. Manuelle Wecker ("... (Manuell)") treffen nie eine Definition.
  **Der Name ist keine Identitaet** (gleichnamige Definitionen sind speicherbar, die Erkennung
  ordnet ueber Keywords zu): still nur, wenn ALLE gleichnamigen still sind - nie `firstOrNull`,
  sonst macht eine stille Dublette davor den Wecker aus der lauten stumm.
- **„Deine Schicht beginnt um" zeigt `AlarmInfo.shiftStartTime`, nicht `triggerTime`.** Die Falle
  liegt im Re-Arming-Pfad `scheduleSystemAlarm()`, nicht in der Erstplanung.
- **Der Delta-Sync hat pro Event ein eigenes `try/catch`, das `CancellationException` weiterwirft.**
- **Verstrichene Weckzeit ist KEINE entfernte Schicht** (`expiredEventIds`) — sonst meldet die App
  jeden Schichtmorgen „Schicht entfernt" für den Dienst, den der Nutzer gerade antritt.
- **Die 6h-Wartungskette hat GENAU einen Planer: `scheduleNext()`, auf genau einem Request-Code.**
  Wer „sicherheitshalber" nachplant, erzeugt zwei parallele Zyklen.
- **`scheduleNext(context, frueherenTerminBehalten = true)` NUR an den drei Eintritten in
  `MainScreen`** (LaunchedEffect beim Eintritt in den Hauptbereich, `finishOnboarding`,
  `GateSchritt.Fertig`): ein gemerkter Termin in (jetzt + 1 s, jetzt + 6 h] wird neu gestellt,
  aber nicht verschoben (`WartungsKettenPlanung.zielTermin`); der Wachhund rechnet vom ZIEL, nicht
  von jetzt. Alle anderen Aufrufer (Wartungs-`finally`, `BootReceiver`, `resume()`, Wachhund)
  planen ab jetzt + 6 h. „Idempotent" galt nur für die Anzahl: bis 1.47.1 schob jedes Öffnen der
  App den fälligen Lauf weg. Kein zweiter Planer — derselbe Slot 0.
- **Der Termin-Merker `WartungsKettenTermin` liegt in DEVICE-PROTECTED SharedPreferences
  (`wartung_kette.xml`)**, weil `scheduleNext()` auch aus dem `BootReceiver` vor der Entsperrung
  läuft. `merke()` erst NACH dem Stellen, `vergiss()` in `cancelNext()` (Master-Pause); jeder
  Zugriff wirft nie (Lesefehler = kein Termin = jetzt + 6 h). Reist nicht im Backup mit (außerhalb
  der Domain `sharedpref`). Zweiter Leser: „Nächster Abgleich im Hintergrund" in der Status-Karte.
- **Der `finally`-Block der Wartung läuft in `withContext(NonCancellable)`** und fängt den
  Master-Pause-Read; dort liegen auch Dimmer-, DND- und Pre-Alarm-Reschedule.
- **`AlarmMaintenanceService`: `stopSelf(startId)`**, niemals blankes `stopSelf()`.
- **`AlarmMaintenanceService.start()` fängt den abgelehnten Vordergrund-Start selbst** (nicht die
  Aufrufer) und setzt einen einmaligen Nachhol-Alarm auf **eigenem** Request-Code.
- **Die 6h-Wartung MUSS Änderungen und Streichungen sehen können** — sie lädt seit 1.48.0 bei
  **jedem** Lauf (kein Lade-Gate mehr) und synchronisiert danach **immer**. `forceSync` heißt nur
  noch „am 15-min-Zwischenspeicher vorbei". Eigener Frische-Stempel `last_event_load_time`. Die
  Leerlisten-Sperre bleibt.
- **`BootReceiver` liest die Kalenderauswahl über den DataStore**, nicht über den noch nicht
  hydrierten `StateFlow`, und setzt **vor** der langen Recovery einen Wartungs-Anker.
- **Ein Update VOR der ersten Entsperrung (MY_PACKAGE_REPLACED, gesperrt) macht NUR den
  Spiegel-Restore** — wie LOCKED_BOOT_COMPLETED; die volle Recovery bringt BOOT_COMPLETED nach dem
  Entsperren. Vorher lief sie: `armiereNebenketten` → Dimmer-`enable()` las den settings-DataStore
  gesperrt (still „leer", für den ganzen Prozess), und nach dem Entsperren schrieb die
  Dimmer-Migration im selben Prozess „Dimmer aus" zurück (Emulator, 13.10.2026; `pruefe_direct_boot.py`
  sieht das nicht, es installiert nicht im gesperrten Zustand).
- **Ein fehlgeschlagener Token-Abruf ist NICHT pauschal ein Anmeldeproblem.** `WartungTokenFehler`
  stuft nach dem Vertrag von GoogleAuthUtil ein (`IOException` = voruebergehend,
  `GoogleAuthException` = endgueltig) und entscheidet deshalb an der URSACHE eines
  `RefreshFailed`, nicht an seinem Typ — dafuer MUSS die `cause` durchgereicht werden. Seit dem
  Umstieg auf den AuthorizationClient (Oktober 2026) stellt `AutorisierungsEinstufung` diesen
  Vertrag her; dort steht die Falle „offline = `hasResolution`" (Auth-Skill). Das `when`
  ueber die versiegelte `TokenException` bleibt exhaustiv.
- **„Voruebergehend" heisst entprellt, nicht stumm.** Gemeldet wird ab dem ZWEITEN Fehlschlag in
  Folge, mit einem Text ohne Anmelde-Behauptung; ein gueltiges Token setzt Zaehler und
  „bereits gemeldet" zurueck (am TOKEN aufgehaengt, nicht am Gesamterfolg des Laufs). Wer den
  Netzfall ganz still schaltet, macht eine dauerhaft stehende Synchronisation unsichtbar.
- **Die stuendliche Rufbereitschafts-Abfrage (`service/RufbereitschaftAbfrage`) ist eine EIGENE
  Kette**: Request-Code 8803, einziger Planer `reschedule()` (rechnet immer vom Ist-Zustand:
  Schichtspannen mit `ShiftDefinition.isOnCall`, freigegebene Tage, Master-Pause), Empfaenger
  `RufbereitschaftAbfrageReceiver`, und der startet NUR `AlarmMaintenanceService.start(forceSync =
  true)`. Neu geplant wird sie im `finally` der Wartung (nach dem Sync, denn sie liest die frisch
  geschriebenen Spannen), im `BootReceiver` und in `ZeitkettenArmierer` zusammen mit DND (Boot und
  Wartung über `ZeitkettenArmierer.armiereNebenketten()`, die EINE Stelle für alle vier
  Nebenketten samt Pause-Abräumen; die Master-Pause räumt/plant sie in `pause()`/`resume()`) und
  seit 1.48.0 nach jedem gelungenen Sync im Vordergrund
  (`ZeitkettenArmierer.planeNachVordergrundSync()`: sie und der Pre-Alarm-Refresh immer, Dimmer und
  DND seit 1.49.1 NUR bei geändertem Schichtstand — sie schalten in `enable()` sofort am Gerät, ein
  bloßer Blick in die App soll nichts umschalten; der Vorher-Stand wird VOR dem Sync gelesen). Ein
  Termin liegt mindestens eine Minute in der Zukunft (`MINDEST_ABSTAND_MS`), sonst plant der Tick
  sich bei Uhren-Schlupf selbst noch einmal. Exakte Alarme mit inexaktem Fallback wie
  `scheduleNext()`; kein WorkManager-Periodic, weil Doze den um Stunden schiebt. Hergang im
  Kalender-Skill `cfalarm-kalender-und-schichten` (Hergang-Datei zum Kalender-Datenfluss,
  Abschnitt Rufbereitschaft).
- **Die Netz-Nachholung ist ein EINMALIGER WorkManager-Auftrag** (`NetworkType.CONNECTED`), kein
  zweiter Planer der Kette und kein Timer. Nur bei nachgewiesener Netzursache und gedeckelt
  (`MAX_NETZ_NACHHOLVERSUCHE`) — ein Captive Portal erfuellt „Netz verfuegbar" dauerhaft. Sie geht
  bewusst ueber `AlarmMaintenanceService.start()`, damit es EINE Wartungsimplementierung gibt.
- **Die zwei Abgleiche nach „TimeOffice öffnen" (`service/TimeOfficeNachfassAbgleich`, +3 und
  +10 min) sind EINMALIG und kein zweiter Planer der Kette**: eigene Slots 8804/8805 (auf Code 0
  ersetzten sie den nächsten regulären Lauf), kein Selbst-Nachplanen, Empfänger
  `TimeOfficeNachfassReceiver` startet NUR `AlarmMaintenanceService.start(forceSync = true)`. Ein
  zweiter Druck ersetzt dieselben Slots, statt zu stapeln. `plane()` fragt den Pausen-Spiegel (DE,
  synchron), `MasterPauseUseCase.pause()` räumt beide über `abbrechen()` ab, `resume()` stellt sie
  bewusst NICHT neu (sie hängen an einem Knopfdruck, nicht an einem Ist-Zustand). Exakt mit
  inexaktem Fallback; ohne Exact-Alarm-Berechtigung entfallen sie faktisch (ein ungenauer Alarm
  darf keinen Vordergrunddienst starten). Kein WorkManager. Hergang in der Hergang-Datei.
- **Belegte feste Request-Codes der Hintergrundketten** (die Wecker selbst nicht mitgezählt): 0
  (6h-Kette), 8801 (Nachhol-Alarm), 8802 (Wachhund), 8803 (Rufbereitschafts-Abfrage), 8804/8805
  (TimeOffice-Nachfass), 7710/7712 (Dimmer-/DND-Tick). Ein neuer Slot wird vorher gegen diese
  Liste geprüft und hier nachgetragen.
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

Seit 1.39.3, ohne Geraete-Unterscheidung seit 1.39.5. Hergang, Messwerte und die widerlegten
Saetze: `reference/vorwecken.md`.

- **Der Ton startet IMMER sofort, nie im verzoegerten Zweig.** Verzoegert wird ausschliesslich
  `startForeground()` mit der FSI-Notification. Der Ton ist der Wecker; wer ihn mit verzoegert,
  verzoegert den Weckruf selbst.
- **`VorweckEntscheidung.vorlaufMillis` liest NUR Systemzustand** (`isInteractive`,
  `isKeyguardLocked`) — **kein gespeicherter Merker, kein DataStore, keine SharedPreferences.**
  Bis 1.39.4 entschied ein bleibender Merker `je_verdraengt` mit, ob vorgeweckt wird; der lag im
  CE-Storage und war im Direct Boot nicht lesbar, weshalb ausgerechnet der erste Wecker nach
  einem naechtlichen Neustart ungeschuetzt lief. Wer dort wieder etwas Gespeichertes abfragt,
  holt diese Luecke zurueck — und laesst ausserdem eine Typzuweisung in
  `VorweckEntscheidungTest` nicht mehr uebersetzen.
- **Vorgeweckt wird nur bei dunklem UND gesperrtem Bildschirm.** Ist der Bildschirm an, gibt es
  kein Aufwecken und damit keine Gesichtsentsperrung, die verdraengen koennte; ohne Keyguard
  entscheidet SystemUI ohnehin auf Heads-up statt Vollbild.
- **Jeder Fehler beim Ermitteln der Eingaben fuehrt zu Vorlauf 0**, also zum unveraenderten
  Verhalten.
- **[WeckbildschirmVerdraengungPrefs] traegt nur noch den HINWEIS**, nicht mehr die Massnahme.
  Der Zaehler faellt bei jedem sauberen Wecker auf 0 — das ist jetzt gefahrlos und war es
  vorher nicht.

## Sanfter Weckton-Anstieg — Kurzregeln

Optional, ab Werk AUS. Schalter, Anlaufzeit und Startpegel im Wecker-Tab; Rechenkern
`alarm/LautstaerkeAnstieg.kt` (rein, unter Test), Werte in `AlarmPrefs`.

- **Der Anstieg fasst die System-Lautstaerke NICHT an.** Er skaliert ausschliesslich den eigenen
  Player per `MediaPlayer.setVolume()`. `AudioManager.setStreamVolume(STREAM_ALARM, ...)` ist
  verboten: stirbt der Prozess mitten im Anstieg — nachts, ohne Zeugen — bliebe die
  Alarm-Lautstaerke des GERAETS dauerhaft auf dem Anfangswert stehen, und das naechste Wecken
  waere leise, ohne dass irgendetwas darauf hinweist.
- **Ton und Melodie bleiben Sache von Android.** Es gibt bewusst KEINE App-eigene Ton- oder
  Lautstaerkeauswahl daneben — sie waere eine zweite Wahrheit und eine Stelle, an der die App
  leiser sein kann, als der Nutzer glaubt. Der Anstieg ist die einzige Ausnahme, weil Android
  genau ihn nicht anbietet.
- **Jede Unklarheit degradiert auf VOLLE Lautstaerke**, nie auf einen leisen Wecker: unlesbarer
  DataStore, fehlende Intent-Extras, unbrauchbare Eingaben. Auch Direct Boot faellt darunter —
  der Receiver liest den CE-Storage dort nicht, der Anstieg entfaellt also.
- **Der Anstieg braucht einen BACKSTOP, der nicht an der Schrittkette haengt.** Ein zweiter,
  unabhaengiger `postDelayed` stellt am Ende bedingungslos auf voll. Ohne ihn friert ein
  abgerissener Schritt den Wecker auf dem Anfangspegel ein — das ist ein verschlafener Dienst.
- **Jeder Anstiegs-Callback prueft `playerGeneration`**, sonst dreht ein Anstieg des VORIGEN
  Players den neuen leiser (stop -> snooze -> start).
- **Der Anstieg hat einen EIGENEN Handler**, nicht den `vorweckHandler`: beide raeumen mit
  `removeCallbacksAndMessages(null)` auf, und der ausstehende Vordergrund-Start darf dabei nicht
  mitgeloescht werden.
- **Der Startpegel darf nie 0 sein** (`LautstaerkeAnstieg.MIN_STARTPEGEL`): `0` hoch irgendwas
  ist 0 — der Wecker bliebe bis zum letzten Schritt stumm und wuerde dann schlagartig laut.
- **Die Kurve ist geometrisch, nicht linear.** `setVolume()` nimmt eine Amplitude, das Ohr hoert
  logarithmisch; linear klingt, als sei der Anstieg nach einem Viertel der Zeit vorbei.
- **Die Obergrenze der Anlaufzeit (120 s) ist Absicht.** Ein laengerer Anlauf ist kein sanfterer
  Wecker, sondern ein spaeterer.
- **Die Vibration ist NICHT Teil des Anstiegs** — sie startet weiterhin sofort in voller Staerke.
  Bewusst offen gelassen; wer das aendert, aendert die Weckwirkung selbst.

## Probehoeren — Kurzregeln (seit 1.50.0, #139)

`alarm/WecktonProbe.kt` (Prozess-Singleton, Zustand als StateFlow) + `alarm/AndroidProbeAudio.kt`.

- **Probehoeren laeuft NIE ueber die Wecker-Kette**: kein `AlarmReceiver`, kein
  `AlarmSoundService`-Start, keine Notification 2002, kein `OneShotAlarmHandoff`, kein
  AlarmManager. Geteilt werden nur REINE Bausteine: die Ton-Auswahl `WecktonQuellen.kandidaten()`
  und die Kurve `LautstaerkeAnstieg` — eine zweite Kopie der Rampe wuerde etwas vorspielen, das
  morgens nicht klingt.
- **`AlarmSoundService` weiss nichts von der Probe.** Laufen beide kurz parallel (echter Wecker
  waehrend der hoechstens 125 s Probe), klingelt der Wecker trotzdem — das ist die einzige
  Invariante, die zaehlt. Die Probe nimmt `AUDIOFOCUS_GAIN_TRANSIENT` und stoppt bei Fokusverlust.
- **Der Stopp haengt nicht am `viewModelScope`** (Lehre der Dimm-Vorschau): Besitzer ist der
  Singleton; die Oberflaeche stoppt bei Lifecycle ON_STOP, und ein noch ausstehender Start wird von
  `probehoerenStoppen()` mit abgebrochen (Review 14.10.2026: Stopp vor Start liess die Probe sonst
  ohne Knopf im Hintergrund weiterlaufen). Am Emulator gemessen: Fokus ~1 s nach HOME freigegeben.
- **Strikt lazy**: nichts im Konstruktor/Initializer — der Graph wird im Direct-Boot-Prozess gebaut.
  `pruefe_direct_boot.py` am 14.10.2026 gruen (3/3 Wecker, RUNNING_LOCKED).

**Namen in der Oberflaeche seit 1.50.0:** „Überspringen“ heisst „Nicht wecken“ (zurueck: „Doch
wecken“), „Tag freigeben“ heisst „Dienst fällt aus“ (zurueck: „Zurücknehmen“). Im Code und in
diesem Skill bleiben die Mechanismus-Namen (Skip, `TagFreigabeUseCase`) — sie benennen die
Mechanik, nicht den Knopf.

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
