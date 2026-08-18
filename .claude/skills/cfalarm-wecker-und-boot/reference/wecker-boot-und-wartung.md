# Wecker, Vollbild, Snooze, Boot und 6h-Wartung — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- Zwei Scopes rufen bewusst NIE `.cancel()`
- `WakeLockManager`/`IWakeLockManager` sind ENTFERNT
- Eine Instanz besitzt den Wecker
- `AlarmSoundService`: `stopSelf(startId)` und `START_REDELIVER_INTENT`
- Vollbild: Dismiss und Snooze teilen eine Einweg-Sperre (`OneShotAlarmHandoff.claim()`, am Anfang
- `AlarmFullScreenActivity` braucht `onNewIntent()` mit `setIntent()`
- `visibilitySnapshot()` ist Diagnostik, die im Release-Log landen MUSS
- Alle drei `setAlarmClock()`-Aufrufstellen behandeln eine entzogene Exact-Alarm-Berechtigung gleich
- Ein schwebender Snooze ist abbrechbar (`cancelSnooze`/`cancelAllSnoozes`) — aber nur auf
- Blockierte Benachrichtigungen sind ein WECKER OHNE OBERFLAECHE — und das muss sowohl sichtbar
- Löschen heißt IMMER: erst `cancelSystemAlarm()`, dann `deleteAlarm()`
- Der `isPersistenceBlocked()`-Wächter in `clearInternalAlarms()` unterscheidet, WARUM geräumt
- Ein manueller Alarm lässt sich nicht anlegen, während „Automatische Alarme" aus ist
- Der Snooze-Merker ist serialisiert (`snoozeRegistryLock`) und schreibt mit `commit()`
- Die datengetriebenen Räumzweige von `syncAlarms()` schonen MANUELLE Alarme
- `clearInternalAlarms()` fragt ZUERST `alarmRepository.isPersistenceBlocked()` und scheitert
- Die Hue-Regelausführung im `AlarmReceiver` ist gedeckelt (`withTimeoutOrNull`,
- Kein `startActivity()` aus dem AlarmReceiver
- `_alarmActive = true` VOR `startForeground()`
- Snooze braucht `snoozeAlarmAction(id)`
- Ein schwebender Snooze muss einen Reboot überleben
- NICHTS am Application-Graphen darf WorkManager (oder CE-Storage) beim BAUEN anfassen
- Kein `getSharedPreferences()` und kein CE-Zugriff in einem Property-Initializer einer Klasse am
- Schlummer-Dauer (`AlarmPrefs`, seit v1.22.0) ist konfigurierbar, aber EINE Quelle für beide
- `AlarmMaintenanceService`: `stopSelf(startId)`, niemals blankes `stopSelf()`
- Die 6h-Wartungskette hat GENAU einen Planer: `scheduleNext()`, auf genau einem Request-Code
- Dieser `finally`-Block läuft in `withContext(NonCancellable)` und fängt den
- Die 6h-Wartung MUSS Änderungen und Streichungen sehen können — die Lade-/Sync-Entscheidung
- `BootReceiver` liest die Kalenderauswahl über den DataStore und entscheidet nicht auf einem
- `TimezoneChangeReceiver` startet die Wartung mit `forceSync=true`
- Das "Nächsten Alarm überspringen"-Flag läuft zeitbasiert ab, nicht per ID-Match
- Der Delta-Sync hat pro Event ein eigenes `try/catch`, das `CancellationException` weiterwirft
- Stille Schicht (`ShiftDefinition.isSilent`/`AlarmInfo.isSilent`, seit v1.20.0) ist KEIN Ersatz
- „Deine Schicht beginnt um" (Notification + Vollbild) muss `AlarmInfo.shiftStartTime` zeigen,
- `ShiftConfig.autoAlarmEnabled = false` ist eine ECHTE, sofortige Pause
- `AlarmMaintenanceService.start()` fängt den abgelehnten Vordergrund-Start selbst — nicht die

---

- **Zwei Scopes rufen bewusst NIE `.cancel()`** — beide sind seit v1.24.0 an Ort und Stelle
  begründet, weil jede Prüfrunde sie erneut als „vergessenes Aufräumen" gemeldet hat:
  `AlarmReceiver.receiverScope` (das System erzeugt pro Broadcast eine frische Receiver-Instanz,
  und die Arbeit MUSS `onReceive()` überleben — dafür steht `goAsync()` darüber; ein `cancel()`
  würde Ton-Start, Skip-Prüfung und Hue-Regeln mitten im Lauf abschneiden) und
  `CalendarSelectionRepository.repositoryScope` (`@Singleton` mit Prozess-Lebensdauer; ein
  `cancel()` wäre endgültig und legte den `retryWhen`-Collector still, der die einzige
  Verteidigung gegen den Direct-Boot-Fall ist — dieselbe Fehlerklasse wie
  `HueBridgeConnectionManager.cleanup()`). Gegenstück: `HueLightUseCase.followUpScope` ist
  ebenfalls Absicht, dort wäre ein `.cancel()` die Regression.

- **`WakeLockManager`/`IWakeLockManager` sind ENTFERNT** (v1.23.1, Klasse + Interface + der ungenutzte
  Konstruktor-Parameter von `AlarmManagerService` + zwei Provider in `ServiceModule`). Sie hatten
  keinen Aufrufer; die Wake-Locks des echten Weckvorgangs liegen direkt in `AlarmReceiver` (PARTIAL,
  um den Broadcast zu überleben) und `AlarmFullScreenActivity` (SCREEN_BRIGHT). Wer einem
  Wake-Lock-Verdacht nachgeht, muss DORT suchen — vorher stand hier eine Klasse, die wie der
  zuständige Ort aussah und am Laufzeitverhalten nichts änderte.
- **Eine Instanz besitzt den Wecker**: `AlarmSoundService` hält Ton, Vibration, Audio-Fokus und
  die einzige **Wecker**-Notification (ID 2002). Channel **stumm**, aber `IMPORTANCE_HIGH` (Pflicht
  für Full-Screen-Intent). Der `AlarmReceiver` darf **keine eigene Wecker-Notification** posten
  (die frühere ID 2001 brachte über ihren Channel einen zweiten Klingelton mit). Ausdrücklich
  ausgenommen: die stille Skip-Bestätigung `AlarmReceiver.showSkipNotification()` (eigener
  `SKIP_CHANNEL_ID`, `IMPORTANCE_LOW`, ID 9999, `setTimeoutAfter`) — kein Ton, kein Vollbild.
- **`AlarmSoundService`: `stopSelf(startId)` und `START_REDELIVER_INTENT`.** Blankes `stopSelf()`
  räumt bei zwei überlappenden Alarmen den gerade gestarteten mit ab; `START_STICKY` startet den
  Service mit `intent == null` neu, und daraus kann der `else`-Zweig nichts wiederherstellen — ein
  stummer Zombie-Service, während das Log wie ein funktionierender Wecker aussieht. Der Weckton
  probiert außerdem **alle** Ringtone-Kandidaten der Reihe nach und loggt einen Totalausfall laut
  (Direct Boot kann MediaStore-URIs unauflösbar machen).
- **Vollbild: Dismiss und Snooze teilen eine Einweg-Sperre (`OneShotAlarmHandoff.claim()`, am Anfang
  BEIDER Handler).** Belegt aus dem Gerätelog (05.08.2026): „dismissed" und „snoozed" 24 ms
  auseinander, beide Handler komplett durchgelaufen — Compose gibt jedem gleichzeitigen Zeiger seinen
  eigenen Klick, und die zwei bildschirmbreiten Knöpfe liegen 12 dp übereinander; „Alarm stoppen"
  plante also zusätzlich einen Schlummer-Wecker. Der Notausgang `stopAndClose()` fragt die Sperre
  bewusst NICHT (sonst blockiert sie den Fehlerpfad des Snooze). `AlarmFullScreenHandoffTest`.
- **`AlarmFullScreenActivity` braucht `onNewIntent()` mit `setIntent()`.** `launchMode="singleTask"`
  liefert eine zweite Zustellung desselben Full-Screen-Intents als `onNewIntent`, nicht als
  `onCreate` — ohne Überschreiben las `snoozeAlarm()` Schicht/ID/Snooze-Dauer aus dem VORHERIGEN Alarm.
- **`visibilitySnapshot()` ist Diagnostik, die im Release-Log landen MUSS.** Sie protokolliert
  `interactive`/`display`/`keyguardLocked`/`deviceSecure`/`wakeLockHeld` an `onCreate`/`onStart`/
  `onStop` und bei jedem Fensterfokus-Wechsel — stoppt die Activity, während der Wecker läuft, als
  **WARN** (Release-Logs enthalten nur WARN+). Das verschwindende Vollbild (05.08.2026, `STOPPED`
  276 ms nach `initialized`, Wecker klingelte 11 s weiter) ließ sich aus dem Log nicht von
  „Bildschirm aus" unterscheiden und ist am Emulator in drei Läufen nicht reproduzierbar — deshalb
  bewusst kein Fix ins Blaue. Auf DEBUG herunterstufen macht den nächsten Vorfall wieder unauswertbar.
- **Alle drei `setAlarmClock()`-Aufrufstellen behandeln eine entzogene Exact-Alarm-Berechtigung gleich
  (`AlarmManagerService.setExactOrInexact`): try/catch + inexakter Fallback.** `setAlarmClock()` ist
  NICHT davon ausgenommen (der alte KDoc behauptete das) und wirft auf API 31/32 ohne
  `SCHEDULE_EXACT_ALARM` eine `SecurityException` — ungefangen aus dem Notification-Snooze-Button riss
  das den ganzen Prozess mit. Ein verzögerter Wecker schlägt keinen Wecker; der Schicht-Wecker fiel
  vorher komplett aus, während UI und Repository „Alarme aktiv" zeigten.
  `requestExactAlarmPermission()` gehört NICHT in diesen Pfad — aus 6h-Wartung/Worker kann der
  Systemdialog wegen Background-Activity-Start gar nicht erscheinen.
- **Ein schwebender Snooze ist abbrechbar (`cancelSnooze`/`cancelAllSnoozes`) — aber nur auf
  ausdrücklichen Nutzer-Willen.** `cancelSystemAlarm()` baut ausschließlich `enhancedAlarmAction` und
  trifft den eigenen Snooze-Slot nie; ein Snooze lief dadurch durch Master-Pause, „Automatische Alarme
  aus" und `deleteAllAlarms` hindurch. Dazu ein Merker der Snooze-IDs im device-protected Storage
  (sonst sind sie nirgends persistiert). Bewusst NICHT in datengetriebenen Aufräumzweigen und nicht an
  `deleteAlarm(id)`: dort räumt der `BootReceiver` abgelaufene Alarme weg — und der Ursprungsalarm
  eines schwebenden Snooze IST abgelaufen.
- **Blockierte Benachrichtigungen sind ein WECKER OHNE OBERFLAECHE — und das muss sowohl sichtbar
  als auch im Log auswertbar sein.** Sind Benachrichtigungen fuer die App aus, laeuft
  `AlarmSoundService` weiter (Ton, Vibration), aber seine Notification wird unterdrueckt UND der
  Full-Screen-Intent abgelehnt: kein Weck-Bildschirm, keine Stopp-/Schlummer-Knoepfe, einziger
  Ausweg „App beenden" in den Systemeinstellungen. Am Emulator im echten Zustand gesehen
  (11.08.2026). Der Zustand entsteht ohne Zutun, wenn der Nutzer die EINMALIGE Abfrage ablehnt
  (`MainActivity.checkNotificationPermission()`, `LaunchedEffect` beim ersten Erreichen des
  Hauptbereichs) oder die Berechtigung spaeter entzieht — danach fragt die App nie wieder. Deshalb
  zwei Dinge: die Status-Karte `NotificationsEnabledCard` steht **VOR** `FullScreenIntentCard`
  (ohne Benachrichtigungen ist deren Aussage bedeutungslos) und fuehrt per
  `ACTION_APP_NOTIFICATION_SETTINGS` in die Einstellung — die Laufzeit-Abfrage zeigt Android nach
  einer Ablehnung gar nicht mehr; und `AlarmSoundService` loggt direkt nach `startForeground()` ein
  **WARN** (Release-Logs enthalten nur WARN+), sonst ist der Fall im Log von einem funktionierenden
  Wecker nicht zu unterscheiden.
- **Löschen heißt IMMER: erst `cancelSystemAlarm()`, dann `deleteAlarm()`.** Der Delta-Sync tat es
  umgekehrt — und damit gab es ein Fenster, in dem der Alarm im AlarmManager noch armiert war, aber
  weder Repository noch Direct-Boot-Spiegel ihn kannten. ALLE Cancel-Wege der App iterieren über den
  Repository-Bestand; es gibt keinen zweiten Anker. Bricht die Sequenz dort ab (Prozess-Tod,
  DataStore-Fehler), ist der Wecker unsichtbar UND unabbrechbar — er feuert bis zum nächsten
  Geräte-Neustart, und ein Handy läuft Wochen.
- **Der `isPersistenceBlocked()`-Wächter in `clearInternalAlarms()` unterscheidet, WARUM geräumt
  wird.** Im datengetriebenen Zweig wird nichts angefasst und laut gescheitert (Räumen ohne
  Cancellen ist die gefährliche Kombination). Bei einer AUSDRÜCKLICHEN Abschaltung (Master-Pause,
  „Automatische Alarme aus") laufen dagegen die zwei Schritte weiter, die den unlesbaren Bestand
  gar nicht brauchen: `cancelAllSnoozes()` (eigener Merker im Device-Protected-Storage) und
  `deleteAllAlarms()` (die dokumentierte `force`-Ausnahme — ohne sie re-armt der
  Direct-Boot-Restore genau die Alarme, die gerade abgeschaltet wurden). Der erste Wurf des
  Wächters stand vor allem und machte damit einen schwebenden Snooze wieder unkündbar: die App
  zeigte „pausiert", während der Schlummer-Alarm scharf blieb.
- **Ein manueller Alarm lässt sich nicht anlegen, während „Automatische Alarme" aus ist.** Der
  Schalter ist eine echte Pause, die ALLE Alarme räumt — auch manuelle (so entschieden, testlich
  festgeschrieben). Ohne die Ablehnung bekam der Nutzer eine Erfolgsmeldung für einen Wecker, den der
  nächste `syncAlarms()`-Lauf ohne Rückmeldung wieder löscht. Bei der Master-Pause war dieser
  Widerspruch längst geschlossen, beim Schwester-Schalter nicht.
- **Der Snooze-Merker ist serialisiert (`snoozeRegistryLock`) und schreibt mit `commit()`.** Drei
  unabhängige Read-Modify-Write-Pfade (Vormerken, Vergessen, Writeback des Boot-Restores) sind bei
  jedem Boot nebenläufig erreichbar; ein verlorener Eintrag heißt: der Snooze ist im AlarmManager
  scharf, aber die App kennt ihn nicht mehr — weder abbrechbar noch nach einem Reboot
  wiederherstellbar. `apply()` schreibt asynchron und verlöre denselben Eintrag bei einem
  Prozess-Tod unmittelbar danach. `armSnooze()` gibt seinen Erfolg zurück, und
  `restorePendingSnoozes()` zählt ECHTE Erfolge — vorher behauptete das Boot-Log eine
  Wiederherstellung, die nicht stattgefunden hatte.
- **Die datengetriebenen Räumzweige von `syncAlarms()` schonen MANUELLE Alarme**
  (`clearInternalAlarms(keepManualAlarms = true)` bei „keine Events" und „keine passende Schicht").
  Der Delta-Sync tat das immer (`eventId.isNotEmpty()`), die beiden Abkürzungs-Zweige davor
  umgingen die Zusicherung komplett und riefen ein pauschales `clearInternalAlarms()`. Ausgerechnet
  der manuelle Alarm ist der EINZIGE, der sich nicht aus dem Kalender rekonstruieren lässt: er kam
  nie wieder, und im Log stand „No matching shifts found - clearing all alarms", was wie
  Normalbetrieb klingt. Realer Ablauf: Urlaubswoche ohne Schicht-Treffer, Wecker für einen
  Arzttermin von Hand gestellt, App geöffnet — Wecker weg. **Ausdrückliche** Abschaltungen
  (Master-Pause, „Automatische Alarme aus", `deleteAllAlarms`) räumen weiter ALLES: dort will der
  Nutzer Stille, und der Direct-Boot-Spiegel muss wirklich leer werden. Drei Tests halten beide
  Seiten fest.
- **`clearInternalAlarms()` fragt ZUERST `alarmRepository.isPersistenceBlocked()` und scheitert
  laut.** Der Kommentar dort sicherte „lieber laut scheitern als leeren" zu, konnte das aber nicht
  halten: nach einem gescheiterten Init-Load steht der Cache auf einer leeren Liste, und
  `getAllAlarms()` gibt genau die als **Erfolg** heraus (die Sperre wurde nur intern vermerkt).
  `getOrThrow()` warf also nie, die Cancel-Schleife lief ins Leere — und `deleteAllAlarms()` leerte
  Store UND Spiegel trotzdem, weil es bewusst mit `force = true` schreibt. Genau die Kombination,
  die der Kommentar ausschließen sollte: verwaiste, armierte System-Alarme, die niemand mehr
  abbrechen kann (bei aktiver Master-Pause klingelt der Wecker dann trotz Pause).
- **Die Hue-Regelausführung im `AlarmReceiver` ist gedeckelt (`withTimeoutOrNull`,
  `HUE_EXECUTION_BUDGET_MS = 45_000L`), weil `pendingResult.finish()` erst danach kommt.**
  Der Deckel lag zunächst bei 20 s — das war zu knapp und hob die Invariante des Bridge-Auto-Aus
  auf: `executeRulesForAlarm()` schaltet erst in der Regel-Schleife alle Lampen ein und legt den
  Auto-Aus-Zeitplan ERST DANACH an; schneidet der Deckel dazwischen, ist das Licht an und es gibt
  keinen Mechanismus mehr, der es ausschaltet. Allein der Batch-Timeout EINER Regel ist 30 s.
  (Diese Datei behauptete bis 17.08.2026 weiterhin 20 s — der Code stand da längst auf 45 s.)
  Der Hue-Pfad hat keine Gesamtschranke:
  `executeRulesForAlarm()` läuft über ALLE passenden Regeln (je 30 s Batch-Timeout), danach folgt
  `scheduleBridgeAutoOff()` ganz ohne Timeout (GET + n DELETEs + ein POST pro Ziel, je 10 s
  OkHttp). Zwei Regeln und eine nicht antwortende Bridge (Handy nicht im Heim-WLAN — der Normalfall
  auf Reisen) reichen über das Broadcast-Fenster hinaus, und dann darf das System den Prozess
  abwürgen. Der Wecker selbst hängt nicht daran: Ton, Vibration und Vollbild laufen über den bereits
  gestarteten `AlarmSoundService`. Licht, das nicht angeht, ist hinnehmbar; ein abgewürgter Prozess
  nicht.
- **Kein `startActivity()` aus dem AlarmReceiver**: AlarmManager-Broadcasts stehen nicht auf der
  Exemption-Liste für Background-Activity-Starts. Einziger Weg: `setFullScreenIntent()`.
- **`_alarmActive = true` VOR `startForeground()`** — sonst schließt sich das Vollbild sofort.
- **Snooze braucht `snoozeAlarmAction(id)`**, nicht `enhancedAlarmAction(id)` — sonst bricht der
  Maintenance-Sync den Snooze ab.
- **Ein schwebender Snooze muss einen Reboot überleben** (`AlarmManagerService.
  restorePendingSnoozes()`, aufgerufen im `BootReceiver` direkt nach dem Direct-Boot-Restore der
  regulären Alarme, seit v1.23.0). AlarmManager verliert beim Neustart ALLE Alarme; der
  Ursprungsalarm ist zu dem Zeitpunkt bereits gefeuert und aus dem Repository geräumt, es gibt also
  keinen zweiten Anker. Bis v1.23.0 stand der Snooze in KEINEM der beiden Wiederherstellungs-Pfade:
  wer schlummerte und dessen Gerät in den Minuten danach neu startete, wurde nie wieder geweckt.
  Der Merker (`pending_snoozes`, DEVICE-PROTECTED, existierte schon für den Cancel-Weg) trägt
  seither auch Schichtname und Schichtbeginn — sonst zeigte das Vollbild nach einem Reboot
  „Deine Schicht beginnt um" ohne Zeit. **Der zweiteilige Altbestand (`id|triggerTime`) MUSS
  lesbar bleiben**: er ist die einzige Spur eines Snooze, der über die Aktualisierung hinweg
  läuft — gälte er als kaputt, wäre er weder wiederherstellbar noch abbrechbar. **Beide Anlässe
  armieren über dasselbe `armSnooze()`**: der PendingIntent muss bis aufs Zeichen identisch sein
  (requestCode = alarmId, `snoozeAlarmAction`), sonst trifft ein späterer Abbruch den
  wiederhergestellten Snooze nicht mehr — und ein nicht abbrechbarer Snooze klingelt mitten in
  einer gerade eingeschalteten Pause. Der Aufruf steht bewusst hinter demselben
  Master-Pause-Gate wie die Alarme (`directBootAlarmStore.isPausedNow()`, nicht der CE-Store).
- **NICHTS am Application-Graphen darf WorkManager (oder CE-Storage) beim BAUEN anfassen.** Der
  Hilt-Graph der Application wird in JEDEM Prozessstart aufgebaut — auch in dem, den das System VOR
  der ersten Entsperrung für den `directBootAware` `BootReceiver` startet. Dort ist WorkManager
  NICHT initialisiert: seine Initialisierung hängt am `androidx.startup.InitializationProvider`, und
  ContentProvider ohne `directBootAware` werden vor dem Entsperren gar nicht instanziiert —
  `WorkManager.getInstance()` wirft „WorkManager is not initialized properly". Am Emulator
  reproduziert (11.08.2026): `CFAlarmApplication` injizierte `MasterPauseUseCase` direkt, der zieht
  über seinen Konstruktor `HueSmartScheduler`, und dessen `initialize()` rief eager
  `WorkManager.getInstance()`. Der Wurf schlug aus der Feld-Injektion nach oben durch, der Prozess
  starb mit „Unable to create application" — und damit lief der Direct-Boot-Restore der Alarme UND
  der schwebenden Snoozes NIE. Die Wecker kamen erst zurück, nachdem der Nutzer das Gerät entsperrt
  hatte; startet das Gerät nachts neu und niemand entsperrt es, gibt es keinen Wecker. Zwei
  Maßnahmen, beide nötig: `MasterPauseUseCase` hängt als **`dagger.Lazy`** am Feld (Konstruktion
  erst nach erkanntem Gerätewechsel, was einen erfolgreichen CE-Read voraussetzt), und
  `HueSmartScheduler` löst WorkManager erst **beim Gebrauch** auf (Getter statt `lateinit`-Feld) und
  überspringt sich mit WARN, wenn er nicht verfügbar ist. **Kein Unit-Test kann das fangen** (auch
  `ColdStartSmokeTest` nicht: er läuft im entsperrten Prozess) — die Prüfung ist ein echter
  `adb reboot` mit gefülltem Direct-Boot-Spiegel, Ablauf im HANDOFF.
- **Kein `getSharedPreferences()` und kein CE-Zugriff in einem Property-Initializer einer Klasse am
  Application-Graphen** (`BackgroundServiceManager`, `HueBridgePinningStore`: beide `by lazy`). Der
  Zugriff selbst ist harmlos, der ZEITPUNKT ist es nicht. Wer daraus wieder einen sofortigen
  Initializer macht, baut einen Absturz, den weder ein Unit-Test noch ein Emulator ohne
  Bildschirmsperre zeigt.
- **Schlummer-Dauer (`AlarmPrefs`, seit v1.22.0) ist konfigurierbar, aber EINE Quelle für beide
  Ausloeser** (Vollbild-Button, Notification-Button) — nicht zwei getrennte Werte. Gelöst NICHT
  durch einen DataStore-Read in einem der beiden Ausloeser selbst: `AlarmSoundService.
  onStartCommand()`s `ACTION_SNOOZE_ALARM`-Zweig und `AlarmFullScreenActivity.snoozeAlarm()` sind
  beide bewusst synchron (Notausgang-Charakter, siehe Snooze-Bug-Historie oben). Stattdessen liest
  `AlarmReceiver` (bereits in einer Coroutine, `receiverScope.launch`) den Wert EINMAL pro
  Alarm-Feuern aus `AlarmPrefs` und reicht ihn als Intent-Extra (`AlarmSoundService.
  EXTRA_SNOOZE_MINUTES`) an beide Ausloeser durch — die lesen dort synchron aus dem Intent.
  **Dieser Read in `AlarmReceiver.startAlarmSoundService()` MUSS hinter `userUnlocked` gegated
  sein**, genau wie der Skip- und Silent-Check direkt daneben: `AlarmPrefs` liegt im
  `@MainDataStore` (CE-Storage), das vor der ersten Entsperrung nicht lesbar ist. Real am
  Fairphone reproduziert (05.08.2026): der erste Wurf dieses Features hatte den Read ungegatet —
  auf Direct Boot hätte das den Wecker komplett stumm gelassen (Exception im try/catch
  verschluckt, `startForegroundService()` nie erreicht), das exakte Gegenteil dessen, wofür
  `directBootAware="true"` existiert.
- **`AlarmMaintenanceService`: `stopSelf(startId)`, niemals blankes `stopSelf()`.** Zwei
  überlappende Starts teilen sich `serviceScope`; der Erste, der fertig wird, löst sonst
  `onDestroy()` → `scope.cancel()` aus und reißt den anderen mitten in der Arbeit ab.
- **Die 6h-Wartungskette hat GENAU einen Planer: `scheduleNext()`, auf genau einem Request-Code.**
  Es gab mal einen zweiten (`scheduleNextAlarm()`, Code 9999 statt 0). Verschiedene Request-Codes
  = verschiedene PendingIntents = zwei unabhängige Alarme; da der `finally`-Block von
  `onStartCommand` ohnehin immer `scheduleNext()` ruft, liefen dauerhaft zwei Wartungszyklen alle
  6h im Millisekunden-Abstand. Wer einen Lauf „sicherheitshalber" selbst nachplant, baut das
  wieder ein — der `finally`-Block deckt jeden Pfad ab.
- **Dieser `finally`-Block läuft in `withContext(NonCancellable)` und fängt den
  Master-Pause-Read.** Vorher stand der suspendierende `pausedNow()`-Read als erste Anweisung darin:
  in einer gecancelten Coroutine (`onDestroy()` → `serviceScope.cancel()`) warf er sofort, wodurch
  WEDER `scheduleNext()` NOCH `stopSelf(startId)` liefen — Request-Code 0 ist der einzige Slot, die
  rollierende Kette war damit bis zum nächsten Boot tot. Ebenfalls im `finally`: Dimmer-, DND- und
  Pre-Alarm-Refresh-Reschedule. Sie standen im tiefsten Erfolgszweig hinter fünf Returns, also
  gerade bei den häufigsten Läufen unerreichbar; bei Master-Pause wird abgeschaltet statt geplant.
- **Die 6h-Wartung MUSS Änderungen und Streichungen sehen können — die Lade-/Sync-Entscheidung
  liegt als reines `MaintenanceLoadDecision` daneben.** Zwei Gates standen vor dem Delta-Sync, der
  Update/Delete als einziger Ort beherrscht: Events wurden nur geladen, wenn der letzte Alarm < 7
  Tage entfernt lag (bei einem 14 Tage gepflegten Dienstplan praktisch nie), und danach brach die
  Wartung ab, sobald es keine Schicht OHNE bestehenden Alarm gab — ein verschobenes Event behält
  seine Event-ID, ein gestrichenes erzeugt gar keinen Match. Folge real am 30.07.2026 (~4 Tage
  unbemerkt): Wecker zur alten Zeit bzw. für eine Schicht, die es nicht mehr gab. Jetzt lädt es bei
  Puffer < 7 Tage ODER letzter echter Kalender-Abfrage ≥ 12 h ODER nächster Alarm ≤ 48 h, und
  synchronisiert **immer**, sobald Events vorliegen (`newShifts` ist nur noch Diagnose-Log). Eigener
  Frische-Stempel `last_event_load_time` — `last_maintenance_time` wird auch im Skip-Zweig gesetzt
  und ließe die Daten dauerhaft frisch aussehen. Die Leerlisten-Sperre bleibt.
- **`BootReceiver` liest die Kalenderauswahl über den DataStore und entscheidet nicht auf einem
  veralteten Snapshot.** Beim Boot ist der `StateFlow` noch nicht hydriert; und die
  Validierungs-/Löschschleife konnte einen vom parallel laufenden Wartungslauf gerade korrigierten
  Wecker wieder löschen, ohne ihn neu anzulegen (`BootAlarmValidation`). Außerdem setzt der Receiver
  **vor** der langen Recovery einen Wartungs-Anker (Foreground-Service, `forceSync=true`):
  `performCompleteSystemRecovery` läuft ohne `goAsync` in einem eigenen Scope und sitzt zuerst 5 s
  ab — in der Zeit ist der Prozess als „empty process" abschießbar, womit 6h-Kette, Sync und
  Dimmer-/DND-Planung lautlos ausfielen. Bei aktiver Master-Pause wird kein Anker gestartet
  (synchron über den Direct-Boot-Spiegel geprüft, weil `MasterPausePrefs` im CE-Storage nur
  suspendierend lesbar ist). Der `ACTION_PACKAGE_REPLACED`-Zweig ist entfernt: der Manifest-Filter
  kann ihn nicht zustellen (bräuchte `<data scheme="package"/>`, was die drei URI-losen Actions
  aussperren würde) — `MY_PACKAGE_REPLACED` deckt den echten Update-Fall ab.
- **`TimezoneChangeReceiver` startet die Wartung mit `forceSync=true`.** Ohne das Flag kehrte der
  angestoßene Lauf im Normalbetrieb zurück, ohne den Kalender anzufassen — der Receiver war
  wirkungslos. Ein bloßes Re-Arming wäre kein Ersatz: es rechnet die gespeicherten Millis mit
  derselben Zone hin und zurück. **Am Gerät belegt (14.08.2026):** der Emulator zog eine
  stehengebliebene Uhr nach (GMT → Europe/Berlin), der Receiver feuerte („Zeitzonen-Wechsel erkannt
  - erzwinge Wartungslauf"), und alle Weckzeiten wurden NEU BERECHNET — `triggerTime` −2 h bei
  unveränderter Wanduhrzeit 05:30. Die Weckzeit ist eine Wanduhrzeit auf dem Kalendertag; ein
  Re-Arming hätte 07:30 ergeben.
- **Das "Nächsten Alarm überspringen"-Flag läuft zeitbasiert ab, nicht per ID-Match.**
  `AlarmSkipUseCase.skipNextAlarm()` löscht den System-Alarm SOFORT (SKIP-IMMEDIATE-UX) — damit
  feuert er nie wieder, und der eigentlich vorgesehene Rücksetz-Pfad
  (`checkAndProcessSkip()` via `AlarmReceiver.onReceive()`) ist für genau diesen Alarm für immer
  unerreichbar. Real beobachtet (26.07.–30.07.2026, ~4 Tage): das Flag blieb hängen, die Karte
  zeigte dauerhaft "Aufheben"/bräunliches Icon, bis der Nutzer manuell aufhob — obwohl der
  eigentliche Wecker in der Zwischenzeit korrekt (normal) geklingelt hatte. Auch ein
  ID-Mismatch in `checkAndProcessSkip()` (ein *anderer* Alarm feuert) räumt das Flag nicht auf
  (`ALARM_EXECUTED`-Zweig ruft bewusst kein `clearSkipStatus()`). Fix seit v1.18.2:
  `AlarmSkipState.skippedAlarmTriggerTime` speichert die ursprüngliche Weckzeit;
  `AlarmSkipUseCase.clearExpiredSkip()` setzt das Flag automatisch zurück, sobald diese Zeit
  verstrichen ist. Aufgehängt an `AlarmUseCase.syncAlarms()` — dem einzigen Einstiegspunkt der
  Event→Alarm-Pipeline (Vordergrund-Sync beim App-Öffnen UND 6h-Wartung) — bewusst kein neuer
  Scheduler. Wer den Ablauf wieder auf reines ID-Matching zurückbaut, holt sich den Bug zurück.
  **Zusätzlich braucht das Flag ein Gate in `syncAlarms()` UND einen Backstop in
  `scheduleSystemAlarm()`** (dem einzigen Weg in den `AlarmManager`, u. a. Boot-Restore): der
  übersprungene Alarm ist aus dem Repository gelöscht, sein Event galt damit für den nächsten Sync als
  NEU — System-Alarm wieder scharf plus falsche „Neue Schicht erkannt"-Notification.
- **Der Delta-Sync hat pro Event ein eigenes `try/catch`, das `CancellationException` weiterwirft.**
  Ohne das brach ein einzelner abgelehnter Alarm (verstrichene Weckzeit) über `getOrThrow()` den
  GESAMTEN Sync ab und ließ den Rest der unsortierten Map ungesetzt; wird die Cancellation dagegen als
  Event-Fehler verbucht, meldet die Abschlusszeile „complete" mit unvollständiger Liste. Die
  Abschlusszeile sagt jetzt, wenn Events übersprungen wurden.
- **Stille Schicht (`ShiftDefinition.isSilent`/`AlarmInfo.isSilent`, seit v1.20.0) ist KEIN Ersatz
  für eine optionale `alarmTime`.** `alarmTime` bleibt bewusst ein nicht-nullables Pflichtfeld — sie
  ist der Zeit-Anker, den DND/Dimmer/Feature A (Rufbereitschaft-Cutoff) weiterhin brauchen. Eine
  echte Nullable-`alarmTime` hätte `ShiftRecognitionEngine`/`AlarmUseCase`/`AlarmManagerService`/
  `ShiftMatch` durchzogen UND Feature A die Datengrundlage entzogen (kein Alarm = kein Eintrag in
  `getAllAlarms()` = kein Cutoff-Anker). Das Flag gated stattdessen NUR die Wecker-AUSLÖSUNG:
  `AlarmReceiver.isSilentAlarm()` (reine, testbare Funktion) prüft `alarmInfo?.isSilent == true` und
  überspringt bei Treffer per frühem `return@launch` — noch VOR dem Wake-Lock — sowohl
  `AlarmSoundService`-Start (Ton/Vibration/Vollbild) als auch `executeHueRulesForAlarm()`. Der
  Broadcast selbst feuert normal weiter, die `AlarmInfo` bleibt normal in `getAllAlarms()` — DND/
  Dimmer/Feature A sind davon unberührt. **Fail-safe wie der Skip-Check daneben:** schlägt der
  `AlarmInfo`-Lookup fehl (z. B. Direct Boot vor Entsperrung), gilt der Alarm NICHT als still — im
  Zweifel wecken statt versehentlich stumm bleiben.
- **„Deine Schicht beginnt um" (Notification + Vollbild) muss `AlarmInfo.shiftStartTime` zeigen,
  NICHT `triggerTime`/die Weckzeit** (Fix v1.20.1, Extra dafür heißt seither
  `AlarmReceiver.EXTRA_SHIFT_START_TIME`, Schlüssel `"shift_start_time_formatted"`). Real
  beobachtet: bei S2 (Weckzeit 14:30, Kalender-Schichtbeginn z. B. 14:48) zeigte die Anzeige die
  Weckzeit. Die eigentliche Falle lag NICHT in der Erstplanung (`AlarmManagerService.
  createEnhancedAlarmIntent()`, liest korrekt `ShiftMatch.calendarEvent.startTime`), sondern im
  weit häufiger durchlaufenen Re-Arming-Pfad `AlarmUseCase.scheduleSystemAlarm(alarmInfo)` — jeder
  der drei `syncAlarms()`-Zweige (neu/geändert/unverändert-re-armen) läuft darüber, also praktisch
  jeder App-Start, jede 6h-Wartung, jeder Boot. Diese Funktion baute bis dahin eine SYNTHETISCHE
  `CalendarEvent.startTime` direkt aus `alarmInfo.triggerTime` (der Weckzeit) — obwohl `AlarmInfo.
  shiftStartTime` (Epoch-Millis des echten Schichtbeginns, seit dem Rufbereitschaft-Cutoff-Feature
  vorhanden) bereits verfügbar war. Nur ein Live-Test am Emulator (Alarm über `cmd alarm set-time`
  wirklich feuern lassen, nicht nur Code-Review) hat das aufgedeckt — der ansonsten korrekt
  aussehende Fix an `createEnhancedAlarmIntent()` allein hätte den Bug NICHT behoben, weil dieser
  Pfad in der Praxis kaum greift. Snooze und Direct-Boot-Restore reichen denselben Wert unverändert
  durch (Schichtbeginn ändert sich durchs Schlummern nicht). Fallback bei `shiftStartTime <= 0`
  (z. B. manueller Test-Alarm ohne echte Schicht) bleibt bewusst die Weckzeit — unveränderte
  UX für den Fall, der nicht Teil dieses Bugs war.
- **`ShiftConfig.autoAlarmEnabled = false` ist eine ECHTE, sofortige Pause** (seit v1.21.0,
  Wecker-Tab): `AlarmUseCase.syncAlarms()` ruft in diesem Zweig `clearInternalAlarms()` (cancelt
  System-Alarme + räumt Repository + Direct-Boot-Spiegel), nicht nur ein stilles `return
  emptyList()`. Wer diesen Aufruf entfernt, macht den Schalter wieder zur Attrappe, die nur
  *neue* Alarme verhindert, aber bestehende weiterlaufen lässt — das war der ursprüngliche,
  gemeldete Zustand. `ShiftViewModel.triggerAlarmCreationFromConfigUpdate()` ruft bei
  `!autoAlarmEnabled` zusätzlich direkt `alarmUseCase.deleteAllAlarms()`, unabhängig vom
  `CalendarStateHolder`-Cache-Zustand — sonst wirkt „Ausschalten" nicht, wenn gerade keine
  Kalender-Events geladen sind (realer Fall direkt nach App-Start).
- **`AlarmMaintenanceService.start()` fängt den abgelehnten Vordergrund-Start selbst — nicht die
  Aufrufer.** `startForegroundService()` wirft ab Android 12 eine
  `ForegroundServiceStartNotAllowedException`, wenn die App im Hintergrund ist und der Anlass nicht
  auf Androids Ausnahmeliste steht; **`ACTION_TIMEZONE_CHANGED` steht dort NICHT.** Von sechs
  Aufrufstellen fing genau eine nicht (`TimezoneChangeReceiver`), und eine Exception aus
  `onReceive()` reißt den Prozess mit — ausgefallen wäre damit genau die Neuberechnung, für die
  dieser Receiver als einzige Verteidigungslinie existiert. Der Fang steht deshalb in `start()`
  selbst (deckt jeden künftigen Aufrufer ab, gleiche Überlegung wie der Master-Pause-Backstop) plus
  ein Nachhol-Alarm auf **eigenem** Request-Code (`WartungsKettenPlanung.NACHHOL_REQUEST_CODE`,
  +10 s). Eigener Code, weil Code 0 der einzige Slot der rollierenden 6h-Kette ist; der
  Nachhol-Alarm plant sich nicht selbst nach, ist also kein zweiter Planer. Dazu reicht
  `AlarmMaintenanceBroadcastReceiver` das `forceSync`-Extra weiter — ohne das liefe der nachgeholte
  Lauf ohne Erzwingen zurück, also wirkungslos.
- **Nur ein EXAKTER Alarm ist ein erlaubter Anlass — der Nachholversuch ist deshalb gedeckelt.**
  Hier stand bis Prüfrunde 6 „das Feuern eines Alarms IST ein erlaubter Anlass"; das ist zu
  weit. Androids Ausnahme gilt für exakte Alarme (dieselbe Grenze, die den Weck-Notausgang im
  `AlarmReceiver` erzwingt). Ein ohne `SCHEDULE_EXACT_ALARM` **inexakt** gestellter Nachholversuch
  wird beim Feuern also erneut abgewiesen und stellt sich selbst wieder — eine endlose
  RTC_WAKEUP-Schleife im 10-Sekunden- bzw. Doze-Takt, aus „Wartung steht still" wird „Wartung steht
  still UND der Akku ist leer". `WartungsKettenPlanung.darfNachholen()` lässt deshalb nur nachholen,
  was exakt gestellt werden kann, und höchstens `MAX_NACHHOLVERSUCHE` mal (Zähler im Intent).
- **Der Wiederanlauf-Wachhund (Slot 8802) zieht die Kette wieder auf — er erzwingt keinen Lauf.**
  Er ist bewusst inexakt gestellt (sonst löschte ihn der Entzug von SCHEDULE_EXACT_ALARM mit) und
  darf aus demselben Grund keinen Vordergrunddienst starten. Seine erste Fassung tat genau das und
  nichts sonst: der Start wurde abgewiesen, `scheduleNext()` (das im `finally` des Dienstlaufs
  steht) lief nie, der Slot war nach einmaligem Feuern verbraucht — die Kette blieb tot, also genau
  der Zustand, gegen den er gebaut wurde. Deshalb ruft `AlarmMaintenanceBroadcastReceiver` beim
  Wachhund-Broadcast (Extra `EXTRA_WACHHUND`) zuerst `scheduleNext()` — reine AlarmManager-Arbeit,
  aus dem Hintergrund immer erlaubt — und prüft davor den Master-Pause-Spiegel, damit ein im selben
  Moment abgeschickter Wachhund eine frische Pause nicht aushebelt. Den sofortigen Wiederanlauf im
  Vordergrund leistet die Exact-Alarm-Statuskarte, nicht der Wachhund.


- **Full-Screen-Intent bei ENTSPERRTEM Gerät ist KEIN Bug.** Die Doku sagt: „While the user is
  using the device, the system UI might display a heads-up notification instead of launching your
  full-screen intent." Das Vollbild ist für das GESPERRTE Gerät gedacht — ein Test mit entsperrtem
  Handy in der Hand beweist nichts. Seit Android 14 entzieht der Play Store zusätzlich
  `USE_FULL_SCREEN_INTENT`, wenn er die App nicht als Wecker-App einstuft; dafür gibt es die
  Status-Karte mit `canUseFullScreenIntent()`.
