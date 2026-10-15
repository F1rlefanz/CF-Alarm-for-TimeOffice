# Philips Hue: API-Semantik, Regeln, Verbindung — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- Das Auto-Aus gehört der BRIDGE, nicht dem Handy
- Die V1-API antwortet auch bei ABLEHNUNG mit HTTP 200
- Ein Fehlschlag der Bridge darf nicht zur leeren Liste degradieren
- `getBridgeConfig` prüft die Antwort (`bridgeid` oder `mac` müssen da sein), statt sie nur zu
- Der gesamte Hue-Pfad ist IPv4-only
- Die Subnetz-Prüfung ist ein HINWEIS auf das Timeout, NIEMALS ein Veto
- Der `HueApiClient` führt jeden Call ABBRECHBAR aus; außer Haus wird gedrosselt, nicht verboten
- `healthCheckScope` braucht einen `CoroutineExceptionHandler` — der `SupervisorJob` reicht
- Eine im Direct-Boot übersprungene Hue-Planung wird NACHGEHOLT
- `cleanup()` auf Prozess-Singletons cancelt NUR Kinder
- Das 90-Zeichen-Limit für `command` aus der offiziellen Doku greift nicht
- Timer (`PT00:30:00`), nicht absolute Zeit
- Auf der Bridge liegen fremde Zeitpläne
- `autoOffTargetsOf()` filtert bewusst NICHT nach Schichtnamen
- `HueLightAction.targetId` ist BRIDGE-LOKAL — der Anker über Geräte hinweg ist `targetName`
- `UNIVERSAL_SHIFT_PATTERN = "ALL"` ist seit v1.24.0 über den Regel-Editor erreichbar
- `HueBridgeConnectionManager.initialize()` muss idempotent bleiben
- `Result<RuleValidationResult>` hat zwei Ebenen
- `MIN_RULE_NAME_LENGTH = 1`, nicht mehr
- „Bridge eingerichtet" und „Bridge verbunden" sind zwei Fragen
- `HueSmartScheduler.getInstance()` veröffentlicht `INSTANCE` erst NACH `initialize()`
- Regel speichern navigiert sofort weg
- Szenen: was am 25.08.2026 an der echten Bridge gemessen wurde

---

- **Das Auto-Aus gehört der BRIDGE, nicht dem Handy** (seit v1.11.0). `executeRulesForAlarm()`
  legt es im selben Atemzug mit dem Einschalten als Bridge-Zeitplan ab. Der frühere
  `AutoOffWorker` ist **ersatzlos** weg — kein Fallback, und keiner wird gebraucht: ging das
  Licht an, war die Bridge erreichbar und der Zeitplan entsteht; war sie es nicht, ging kein
  Licht an und es gibt nichts auszuschalten. Wer hier einen zweiten Mechanismus „zur Sicherheit"
  einbaut, baut die Fehlerklasse „Handy nicht im Heim-WLAN" wieder ein.
- **Die V1-API antwortet auch bei ABLEHNUNG mit HTTP 200.** Das Urteil steht im Body
  (`[{"error":…}]`). `makeSecureHueRequest` kennt nur den Status — wer sich darauf verlässt,
  hält einen abgelehnten Zeitplan für angelegt und das Licht geht nie wieder aus. Dafür gibt es
  `HueV1Envelope` (`internal object`, rein und testbar) — **auch die STEUER-Endpunkte werten den Body
  aus**, nicht nur `result.isSuccess`: nach einem entzogenen Whitelist-Eintrag meldete die Kette „5/5
  actions successful" und legte danach noch den Auto-Aus-Zeitplan an, für Licht, das nie anging.
  Dieselbe Zwei-Ebenen-Falle wie bei `Result<RuleValidationResult>`. Drei Regeln, alle aus echten
  Gerätelogs:
  - **`parseAll` ist streng** („kein Eintrag enthält `error`") und ausschließlich das Urteil der
    Fehlerhüllen-Wächter der GET-Endpunkte (`getLights`/`getGroups`/`getSchedules`). Eine Fehlerhülle
    (HTTP 200 + JSON-**Array** statt Map) wird VOR dem Parsen erkannt und geworfen, statt still „0
    Lampen" zu liefern — der Nutzer sah sonst „Keine Lampen gefunden", ohne Hinweis auf die nötige
    Neukopplung.
  - **`parseControl` ist bewusst milder** („mindestens ein `success`"): ein PUT auf `/state` liefert
    einen Eintrag **pro Attribut**, und die Bridge lehnt einzelne ab, während sie die anderen anwendet
    (Farbtemperatur, Bridge-Attribut ct, an einer Lampe ohne Farbtemperatur = error 6, an
    ausgeschalteter = error 201). Mit `parseAll`
    wurde daraus ein Fehlschlag, `startSunrise` stieg nach Schritt 1 aus, die Lampe blieb am Wecktag
    auf `bri=1`. Abgelehnte Einzelattribute werden geloggt.
  - **Jedes vorhandene `success`-Feld ist ein Erfolg — auch als String.** Ein DELETE antwortet
    `[{"success":"/schedules/1 deleted"}]`; der frühere `as? Map<*, *>` machte aus einem erfolgreichen
    Löschen ein Failure — schädlich, weil das Aufräumen der Auto-Aus-Timer darauf angewiesen ist.
- **Ein Fehlschlag der Bridge darf nicht zur leeren Liste degradieren.**
  `HueLightUseCase.getAllLightTargets()` fing das Failure ab und lieferte `success(LightTargets(leer,
  leer))` — genau die stille leere Lampenliste, die die Hüllen-Wächter beseitigen sollten. Scheitern
  BEIDE Abfragen, wird der Fehler durchgereicht; der Teilerfolg-Zweig bleibt (eine Bridge ohne
  Gruppen ist normal). `HueLightTargetsFailureTest` trennt „Bridge lehnt ab" von „Bridge hat nichts".
- **`getBridgeConfig` prüft die Antwort (`bridgeid` oder `mac` müssen da sein), statt sie nur zu
  deserialisieren.** Beide Aufrufer benutzen sie als „hat sie geworfen?"-Orakel, sie ist damit de
  facto die Bridge-/Zugangsdaten-Prüfung der App — Gson erzwingt Kotlins Non-Null-Deklarationen aber
  NICHT, `fromJson("{}", …)` liefert ein Objekt voller nulls und wirft nicht. Wandert der DHCP-Lease,
  hätte ein beliebiges anderes Gerät an derselben IP als „unsere Bridge" gegolten.
- **Der gesamte Hue-Pfad ist IPv4-only** (Präfix-Klemme, URL-Bau ohne eckige Klammern,
  `isBridgeReachableNow`). mDNS wählt deshalb die erste **IPv4**-Adresse: in einem IPv6-Heimnetz
  meldete die Discovery sonst Erfolg, danach scheiterte jeder Zugriff, und der N-UPnP-Fallback greift
  nicht, weil mDNS ja „etwas" gefunden hatte. Nur-IPv6 liefert ehrlich `null`. Eine Nicht-IPv4-Adresse
  ist kein „SECURITY"-Vorfall — Adressfamilie, nicht Angriff; die Klemme bleibt genauso streng.
- **Die Subnetz-Prüfung ist ein HINWEIS auf das Timeout, NIEMALS ein Veto** (Fix 13.08.2026).
  `isBridgeReachableNow()` → `NetworkStateMonitor.isReachableSubnet()` verlangt, dass das Gerät
  eine eigene IPv4 **im selben Subnetz** wie die Bridge hat. Als Abkürzung für „zu Hause vs.
  unterwegs" ist das brauchbar — aber es ist eine Heuristik, und sie liefert Falsch-Negative
  überall dort, wo ein Router zwischen zwei erreichbaren Netzen vermittelt: **Gast-WLAN,
  getrenntes VLAN, Mesh-/Repeater-Setups mit eigenem Subnetz, Doppel-NAT — und der Emulator**
  (10.0.2.x hinter NAT, routet aber ins Heimnetz; `ping` auf die Bridge: 0 % Verlust).
  Vorher stand die Prüfung als Veto **VOR** dem echten Test. Am Emulator aufgeschlagen: das
  Pairing bekam eine HTTPS **200** von der Bridge, und 7 ms später verwarf
  `validateConnectionCredentials()` genau diese Bridge mit „not reachable from current network" —
  eine antwortende Bridge, abgelehnt von einer Vermutung über sie, ohne Weg für den Nutzer, das
  zu übergehen. Jetzt entscheidet die Heuristik nur noch, ob der Versuch das volle
  OkHttp-Timeout (10 s) bekommt oder nach `OFF_SUBNET_PROBE_TIMEOUT` (3 s) abgeschnitten wird;
  der echte Request ist das Urteil. **Beide** Aufrufstellen sind so gebaut (Einrichtung UND der
  Cache-Pfad in `getValidatedConnection()`) — nur eine davon zu ändern hieße: das Setup
  verbindet, und jede spätere Nutzung scheitert weiter. Unverändert bleibt, dass der
  Verbindungszustand dabei **nicht** auf `ERROR` herabgestuft wird (ein transientes „falsches
  Netz" ist kein Bridge-/Zugangsdaten-Fehler). `HueBridgeConnectionManagerTest` hält beide
  Richtungen fest; am Gerät belegt (Kopplung erfolgreich, Log: „Bridge trotz fremden Subnetzes
  erreichbar — die Heuristik lag falsch").
- **Der `HueApiClient` führt jeden Call ABBRECHBAR aus — und außer Haus wird gedrosselt, nicht
  verboten** (#138, 1.49.3). Befund am Fairphone (1.47.0, Datei-Log nur WARN+), 12.10.2026: 30
  Bridge-Timeouts und 1.798 Stacktrace-Zeilen bei 135 KB Log — rund 90 % des Inhalts an einem
  Tag außer Haus. Drei Ursachen, im Code nachvollzogen:
  1. *Der Kurzversuch griff nicht.* `execute()` lief blockierend in `withContext(IO)`; ein
     Coroutine-Abbruch unterbricht keinen Socket-Connect. `withTimeoutOrNull(3 s)` wartete also
     das volle `connectTimeout` (10 s) ab — im Log „gedeckelt auf 3s", direkt danach
     „failed to connect … after 10000ms". Dasselbe galt für JEDEN Deckel auf diesem Pfad
     (Wiederverbindung 10 s, Neusuche 30 s, das 45-s-Budget im `AlarmReceiver`). Jetzt
     `enqueue` + `invokeOnCancellation { call.cancel() }`; die Antwort wird im Callback per
     `response.use` gelesen, damit auch eine nach dem Abbruch eintreffende nicht offen bleibt
     (`HueResponseSchliessenTest` prüft beide Schreibweisen). Die Manager-Tests konnten das nie
     sehen — sie mocken den Client, und ein `delay()` im Mock ist abbrechbar. Deshalb misst
     `HueApiClientAbbruchTest` die Wanduhr an einem echten OkHttp-Call mit blockierendem
     Interceptor.
  2. *Doppelter Stacktrace auf ERROR je Versuch:* `HueApiClient` loggte jede Exception mit
     Stacktrace, `recoverConnection()` die `TimeoutCancellationException` noch einmal (deren
     Stacktrace zeigt nur Coroutine-Maschinerie). Jetzt: Verbindungsausfall im Client nur Debug,
     andere IO-Fehler (TLS, Protokoll — eine erreichbare, aber ablehnende Gegenstelle) WARN ohne
     Stacktrace; im Manager pro Abwesenheit EINE WARN-Zeile mit Grund und eine bei der Rückkehr
     mit der Zahl der Versuche (`protokolliereProbe`). Der Weckpfad bleibt laut:
     `HueLightRepository` und `AlarmReceiver` loggen ihr Scheitern selbst auf ERROR.
  3. *Kein Rückzug außer Haus.* Die Gesundheitsschleife war NICHT die Quelle (sie prüft nur im
     Zustand CONNECTED, nach dem ersten Fehlschlag steht ERROR). Getrieben wurden die Versuche
     vom Netzwerk-Beobachter: Mobilfunk-„validiert" flattert, jedes `true` startete eine volle
     Wiederverbindung samt Neusuche. Jetzt zieht sich NUR dieser Pfad zurück (15 min,
     verdoppelnd bis 2 h). **Das ist eine Häufigkeit, kein Veto:** Weckpfad
     (`getValidatedConnection`), Prüfen-Knopf und App-Öffnen fragen jedes Mal echt, und sobald
     die Subnetz-Prüfung „zu Hause" sagt, wird sofort und ungedrosselt versucht. Den Rückzug an
     jedes `isNetworkAvailable`-Ereignis zurückzusetzen wäre falsch — genau diese Ereignisse
     waren das Dauerfeuer.
  Am Emulator belegt (13.10.2026): Bridge erreichbar trotz fremden Subnetzes, über den neuen
  Call-Weg mit echtem TLS; Bridge per temporärer Host-Firewall gesperrt → keine Stacktraces aus
  dem Transport, die Abwesenheit genau einmal auf WARN. Die Firewall-Sperre wirkt als sofortige
  Ablehnung, nicht als Hänger — die 3-s-Grenze belegt der Unit-Test, nicht diese Messung.
- **`healthCheckScope` braucht einen `CoroutineExceptionHandler` — der `SupervisorJob` reicht
  NICHT.** Ein SupervisorJob isoliert nur Geschwister; die Exception läuft trotzdem zum
  Thread-Default-Handler und beendet den PROZESS. In diesem Scope liegen fünf fire-and-forget
  `launch`-Blöcke, mehrere davon greifen ungeschützt auf den Hue-DataStore zu
  (`restoreConnectionFromStorage()` macht `dataStore.data.first()`); der
  `ReplaceFileCorruptionHandler` fängt nur Korruption, eine IOException reicht DataStore durch. Für
  eine Wecker-App ist das die falsche Reihenfolge der Wichtigkeit: ein misslungener
  Lichtsteuerungs-Read darf nie den Prozess beenden, der die Alarme hält. Zusätzlich liegt das
  `try/catch` des Netzwerk-Recovery-Collectors **INNERHALB** des `collect` — außen herum hätte ein
  einzelner fehlgeschlagener Versuch den Collector beendet, und der startet in diesem Prozess nie
  wieder (`initialize()` ist per Wächter idempotent): die autonome Wiederverbindung bei der
  Heimkehr ins Heim-WLAN wäre dauerhaft tot. Der Scope-Handler rettet den Prozess, nicht das
  Feature — beides ist nötig.
- **Eine im Direct-Boot übersprungene Hue-Planung wird NACHGEHOLT
  (`HueSmartScheduler.retrySkippedSchedulingIfNeeded()`).** Der Prozess, der vor der ersten
  Entsperrung startet, stirbt beim Entsperren nicht — er ist genau der Prozess, in dem der Nutzer
  die App danach bedient, und `HueBridgeConnectionManager.initialize()` ist per Wächter idempotent.
  Ohne das Nachholen fehlten für dessen gesamte Lebensdauer tägliche Planung, Pre-Alarm-Checks und
  der Alarm-Beobachter. Zwei Aufrufstellen: der **ignorierte** Zweig von `initialize()` und
  `onAppForeground()`. Ein früherer Kommentar behauptete, ein späterer Aufruf plane von selbst neu —
  das war falsch.
- **`cleanup()` auf Prozess-Singletons cancelt NUR Kinder.** `HueBridgeConnectionManager.cleanup()`
  rief `healthCheckScope.cancel()` — auf einem Singleton mit Prozess-Lebensdauer endgültig: jedes
  spätere `launch` (Restore, Health-Monitoring, Netzwerk-Recovery) wäre lautlos nie mehr gestartet,
  heilbar nur durch Prozess-Neustart, und beim Wecken blieb das Licht ohne Fehlermeldung aus. Jetzt
  `cancelChildren()` + Zurücksetzen von `initialized`, wie `HueSmartScheduler.cleanup()` und
  `WakeLockManager.releaseAllWakeLocks()`. Folge: `startNetworkRecoveryMonitoring()` läuft **nicht**
  „nur einmal pro Prozess" — ein späteres `initialize()` startet den Collector erneut.
- **Das 90-Zeichen-Limit für `command` aus der offiziellen Doku greift nicht.** Ein reales
  Command misst mit 40-Zeichen-Username ~111 Zeichen und wird akzeptiert (verifiziert 15.07.2026
  gegen BSB002, apiversion 1.78.0; die öffentlich archivierte Doku ist von 2013/API 1.0). Nicht
  „reparieren" — `BridgeScheduleSerializationTest` hält das absichtlich fest.
- **Timer (`PT00:30:00`), nicht absolute Zeit.** Der Zeitplan entsteht zur Weckzeit, gemeint ist
  „+30 Minuten". Der Timer zählt auf der Uhr der **Bridge** herunter → immun gegen falsch
  gestellte Bridge-Zeitzone, Sommerzeit und Drift zwischen Handy und Bridge.
- **Auf der Bridge liegen fremde Zeitpläne** (real: zwei „Hue dimmer switch 1"). Aufgeräumt wird
  ausschließlich, was `BridgeTimer.NAME_PREFIX` trägt. Und aufgeräumt werden **muss**: sonst legt
  jeder Snooze einen weiteren Timer an, und der älteste schaltet zu früh aus.
  **Geräumt wird je ZIEL**, nur was gleich für dieselbe Lampe/Gruppe neu angelegt wird (Review 2
  zu #140, 15.10.2026): bis dahin räumte jeder Lauf ALLE eigenen Timer, und fiel eine Regel mit
  laufender Vorab-Rampe zur Weckzeit weg (Tageslicht, deaktiviert), nahm das Auto-Aus einer
  anderen Regel ihren Vorab-Timer mit — die Rampen-Lampen blieben an. Ein stehen gebliebener
  fremder Timer ist einmalig und räumt sich per `autodelete` selbst ab.
- **`autoOffTargetsOf()` filtert bewusst NICHT nach Schichtnamen.** Die Auswahl gehört allein
  `findApplicableRules` — und die matcht seit v1.11.0 **exakter Definitionsname ODER
  `UNIVERSAL_SHIFT_PATTERN`**, kein Keyword, kein Teiltreffer (`HueRuleMatchingTest`). Ein zweiter
  Filter gegen den Schichtnamen würde genau die UNIVERSAL-Regeln wegwerfen, deren `shiftPattern` per
  Definition NICHT dem Schichtnamen gleicht: sie verlören ihr Auto-Aus, das Licht bliebe an. Diese
  Funktion besitzt nur den Rechenweg (welche Ziele, welche Verzögerung inkl.
  Sonnenaufgangs-Versatz). **Die frühere Begründung „findApplicableRules matcht auch über Keywords"
  war veraltet** — sie verleitete dazu, das Keyword-Matching „wiederherzustellen", also genau die
  S-auf-S2-Fehlerfamilie neu zu bauen (siehe Skill `cfalarm-kalender-und-schichten`).
- **`HueLightAction.targetId` ist BRIDGE-LOKAL — der Anker über Geräte hinweg ist `targetName`**
  (seit v1.25.0). Die ID reist im Konfigurations-Export (`hue_schedule_rules`) und im
  Android-Backup mit und zeigt auf einer anderen Bridge ins Leere: die Regel sieht vollständig aus
  und schaltet am Wecktag nichts oder die falsche Lampe — bemerkt wird das erst morgens. Drei
  Teile, die zusammengehören: `HueRuleConfigScreen.buildActions()` **schreibt** den Namen mit
  (bis v1.24.2 war das Feld deklariert, aber von niemandem gesetzt und von niemandem gelesen —
  totes Kapital, das wie ein vorhandener Anker aussah); `HueTargetReconciler` (rein, testbar)
  ordnet über den Namen neu zu; `HueViewModel.refreshLightTargets()` ist der EINE Aufhängepunkt.
  Die Haltungen sind nicht verhandelbar: bei **Mehrdeutigkeit lieber nicht zuordnen als falsch**
  (dieselbe Haltung wie `ShiftCodeSuggester`), **Lampen- und Gruppen-Namensraum bleiben getrennt**
  (real gemessen: „Deckenlampe", „Diele", „Ecklampe" existieren auf der Bridge des Nutzers als
  Gruppe UND als Lampe — maßgeblich ist `isGroup`, weil genau das über /lights/ vs. /groups/
  entscheidet), und eine **gescheiterte Abfrage ändert und meldet NICHTS**. Dafür führt
  `LightTargets` seit v1.25.0 `lightsFailed`/`groupsFailed` mit: der Teilerfolg-Zweig von
  `getAllLightTargets()` machte „Gruppen nicht abrufbar" sonst ununterscheidbar von „Bridge hat
  keine Gruppen" — und das hätte Regeln wegen eines fremden WLANs als kaputt markiert. Das
  Zurückschreiben läuft INNERHALB einer `dataStore.edit{}`-Transaktion
  (`IHueConfigRepository.updateScheduleRules`), damit eine gleichzeitige Nutzeränderung nicht
  verlorengeht. **Bei offenem Regel-Editor wird NICHT abgeglichen**: das Formular hält einen
  Schnappschuss, der nächste „Speichern" machte die Zuordnung sonst kommentarlos rückgängig. Und
  der Abgleich gehört NICHT in den Weckpfad — der Hue-Zweig im `AlarmReceiver` ist gedeckelt
  (`HUE_EXECUTION_BUDGET_MS`, 45 s; Hergang im Skill `cfalarm-wecker-und-boot`). Am Emulator gegen die echte Bridge belegt (14.08.2026): ID künstlich verbogen →
  „Deckenlampe" landete auf **Lampe 4**, nicht auf der gleichnamigen **Gruppe 10**; Bridge offline
  → Bestand unangetastet, keine Markierung.
- **`UNIVERSAL_SHIFT_PATTERN = "ALL"` ist seit v1.24.0 über den Regel-Editor erreichbar**
  (Eintrag „Alle Schichten" in `ShiftPatternCard`). Davor wertete der UseCase das Muster zwar aus,
  aber keine Oberfläche konnte es setzen — ein funktionierender Codepfad ohne Zugang. Drei Dinge
  hängen zusammen und dürfen nicht auseinanderlaufen: die UI referenziert die **Konstante**
  (`internal`, in der Companion von `HueRuleUseCase`) statt ein eigenes `"ALL"`-Literal zu führen;
  der Rücklesepfad erkennt sie mit **demselben Maßstab** wie `findApplicableRules`
  (`equals(..., ignoreCase = true)`) — sonst zeigt eine gespeicherte Universal-Regel beim
  Wiederöffnen „nichts ausgewählt" und der nächste Speichervorgang überschreibt sie unbemerkt mit
  einem Schichtnamen; und Regel-Liste wie Vorschau zeigen „Alle Schichten" statt des rohen
  Sentinels. Das **Matching selbst bleibt unverändert** — der Editor wurde erreichbar gemacht,
  nicht die Erkennung gelockert.
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
- **„Bridge eingerichtet" und „Bridge verbunden" sind zwei Fragen.** Der `HueSmartScheduler` plant
  nichts, solange `hasStoredBridge()` false ist (ohne Bridge kann jeder Job nur scheitern — der
  Fallback-Health-Check tat das lautstark bei jedem Start). `hasStoredBridge()` liest den
  **persistierten** Wert. Nicht auf `getCurrentConnectionInfo()` umbauen: das liefert nur bei
  `CONNECTED` etwas, fällt nach einem misslungenen Health-Check auf `ERROR` — und dann würde die
  App genau die Pre-Alarm-Checks abschalten, die der Weg zurück sind.

- **`HueSmartScheduler.getInstance()` veröffentlicht `INSTANCE` erst NACH `initialize()`.** Vorher
  stand die Zuweisung davor: warf `initialize()` (das kann es — es löste früher eager
  `WorkManager.getInstance()` auf, siehe Skill `cfalarm-wecker-und-boot`), blieb ein halb initialisiertes
  Singleton zurück, das `getInstance()` für den ganzen Prozess kommentarlos weiter herausgab —
  jeder WorkManager-Zugriff darauf scheiterte, heilbar nur durch Prozess-Neustart. Dieselbe
  Fehlerklasse wie `cleanup()` auf Prozess-Singletons.
- **Regel speichern navigiert sofort weg** (`HueRuleConfigScreen`: `createRule()` ist
  fire-and-forget, `onSaveComplete()` folgt unmittelbar). Ein Fehler landet dadurch erst auf dem
  `HueSettingsScreen` statt im Formular. Seit v1.10.4 kann die Validierung tatsächlich ablehnen —
  bisher nur theoretisch, weil die UI-Validierung dieselben Bedingungen vorher abfängt. Wird das
  je unangenehm: auf das Result warten, bevor navigiert wird.

- **Der Import-Pfad des Ziel-Abgleichs ist am Gerät belegt** (14.08.2026,
  `ConfigBackupUseCase.reconcileImportedHueTargets()` — vorher nur durch Unit-Tests gedeckt):
  echter Export über SAF, die Datei außerhalb der App zur „fremden Bridge" verbogen (ein Ziel mit
  gültigem Namen, eines mit erfundenem), dann importiert → „Wohnzimmer" wurde SOFORT beim Import
  von der ungültigen ID 8 auf 1 zurückgeordnet, „Gaestebad" blieb unangetastet und stand
  **namentlich** im Fertig-Dialog („1 Hue-Regel-Ziel(e) gibt es auf deiner Bridge nicht"). Der
  Export trägt `targetName` mit — ohne das wäre der Abgleich auf einer anderen Bridge unmöglich.

- **Szenen: was am 25.08.2026 an der echten Bridge gemessen wurde** (BSB002, apiversion 1.78.0,
  `GET/PUT` direkt gegen `https://192.168.178.24`, Zugangsdaten aus dem Emulator-DataStore). Die
  Szenen-Unterstuetzung ist auf diesen Zahlen gebaut, nicht auf der Doku:
  - **73 Szenen**, davon **67 `GroupScene`** und **6 `LightScene`**; `recycle:true` bei genau 2.
    Die LISTEN-Antwort traegt **kein** `lightstates` (das gibt es nur bei `GET /scenes/<id>`) und
    bleibt damit bei ~21 kB. 73 Eintraege in einer flachen Liste waeren unbedienbar — deshalb
    waehlt die Oberflaeche erst den Raum und dann die Szene.
  - **Szenennamen kollidieren massiv ueber Raeume hinweg**: „Energie tanken" existiert **10×**,
    „Entspannen", „Lesen", „Konzentrieren", „Nachtlicht" und „Ruhephase" je **9×**. Ein Anker
    allein ueber den Szenennamen waere damit in der Praxis IMMER mehrdeutig und wuerde nach einem
    Geraete-/Bridge-Wechsel nichts mehr zuordnen. **Innerhalb** einer Gruppe kollidierte dagegen
    **kein einziger** Name — deshalb ist der Anker das Paar (Szenenname, Gruppenname), und
    deshalb wird die Gruppe ZUERST aufgeloest.
  - **`PUT /groups/<g>/action` mit `{"scene":"<id>"}` antwortet mit GENAU EINEM Eintrag**
    (`[{"success":{"/groups/5/action/scene":"3JXxZqxyctKtYXK"}}]`), nicht mit einem pro Lampe.
    `parseControl` ist damit richtig; ein Attribut-PUT daneben liefert weiterhin einen Eintrag je
    Attribut (im selben Lauf gegengeprueft).
  - **Gruppe 0 funktioniert auch fuer eine GroupScene** (`/groups/0/action` mit derselben
    Szenen-Id wurde angenommen und schaltete dieselben Lampen). LightScenes waeren darueber also
    technisch erreichbar — sie bleiben trotzdem ausgeschlossen, weil ihnen der Gruppen-Namensanker
    UND das Ziel fuers Auto-Aus fehlen. Das ist ein Produktschnitt, kein API-Hindernis.
  - **`{"scene":id,"transitiontime":20}` im selben PUT wird angenommen** (kein Fehlereintrag).
    Trotzdem wird `transitiontime` bei Szenen NICHT mitgeschickt: die Szene bestimmt ihren
    Uebergang selbst, ein zweiter Uebergangsbegriff daneben waere eine ungeprueffte Zusage.
  - **Keine der stichprobenhaft geprueften Szenen enthaelt Lampen mit `on:false`** (8 Szenen aus
    5 Raeumen, Felder je Lampe: `on` + `bri` + `ct`/`xy`). „Szene an heisst Licht an" traegt damit
    fuer den Auto-Aus-Text.

## Tageslicht-Bedingung (#140, Oktober 2026)

Eine Regel mit `hellAb != IMMER` laeuft nur, wenn die Weckzeit vor Daemmerung bzw. Sonnenaufgang
des Wecktags liegt. Bewusst die WECKZEIT, nicht der Rampenstart oder „jetzt“: die Rampe beginnt
vor dem Wecker, ein Vergleich mit ihrem Start liesse dieselbe Regel je nach Rampendauer anders
entscheiden. Ein Abendwecker (vor einem Nachtdienst) liegt nach dem Sonnenaufgang desselben Tages —
die Regel entfaellt dann, auch wenn es draussen dunkel ist; der Editor sagt das
(`tageslicht_hue_hinweis_abend`). Verworfen: „ist es JETZT dunkel“ — das haette die Rampe
mittendrin abbrechen koennen. Das Entfallen loggt `laeuftBeiTageslichtMitLog` auf WARN (ohne
Ort) — auf INFO stuende im Release-Log nichts, und „Licht ging nicht an“ waere nicht
nachvollziehbar (Runde 4 zu #140). Bekannte Restluecke: `HueConfigRepository` liest ohne
`coerceInputValues`; ein unbekannter `HellAb`-Wert aus einer neueren Version macht die
Regelliste unlesbar (dokumentiert in `HellAb`).

## Auto-Aus spaetestens bei Tageslicht (#143, Oktober 2026)

Das Licht geht zum FRUEHEREN von Auto-Aus-Dauer und Daemmerung/Sonnenaufgang des Wecktags aus.
Die drei offenen Fragen aus dem Issue, so entschieden (mit dem Eigentuemer, 15.10.2026):

- **Je Regel waehlbar, nicht an „Nur bei Dunkelheit“ gekoppelt.** Die Wahl sitzt in der
  Auto-Aus-Karte (gleiche Knopf-Mechanik wie der Dimmer-Nachlauf „Bis Daemmerung“, ein gemeinsames
  `SpaetestensBeiHellWahl`), weil sie die Dauer kuerzt und ohne Dauer nichts bedeutet.
- **Eine von Hand umgestellte Lampe geht trotzdem aus** - wie beim bisherigen Auto-Aus: der
  Bridge-Timer schickt schlicht `{"on": false}`. Eine zweite Logik („nur wenn noch im Regelzustand“)
  braeuchte einen Zustandsvergleich zur Ausloesezeit, den der Timer nicht kann.
- **Vorab-Rampe und Weckzeit-Pfad enden auf derselben Minute**, weil beide ueber `fruehAusFuer`
  rechnen; der Weckzeit-Pfad ersetzt den Vorab-Timer je Ziel wie bisher.

Weitere Entscheidungen: Ist es zur Weckzeit SCHON hell, gilt die Dauer (Licht war gewollt; wer
im Hellen keins will, nimmt „Nur bei Dunkelheit“) - anders als beim Dimmer-Nachlauf, der dann an
der Weckzeit endet. Eine Rampe ab Weckzeit wird nie mittendrin abgeschaltet (Untergrenze
Rampenende). Ein Snooze nach dem Hellwerden schaltet nach 1 min aus (Untergrenze des Timers).
Gerundet wird AUF (`aufgerundeteMinuten`), der Timer endet nie vor dem Hell-Zeitpunkt. Ein
tatsaechliches Vorziehen loggt EINE WARN-Zeile je Regel (Release-Log) - nicht schon das Tragen der
Wahl, das waere Rauschen. Downgrade: `HueConfigRepository` liest mit `ignoreUnknownKeys`, das neue
Feld faellt in aelteren Versionen still weg.
