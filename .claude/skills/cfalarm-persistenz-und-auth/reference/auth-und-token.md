# Auth und Token-Rotation — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen
- GMS-Token-Cache liegt ausserhalb des App-Speichers
- `auth_prefs` braucht `corruptionHandler` UND `.catch{}`
- `onResult` gehoert `OAuth2TokenManager.authorize()`
- `observeTokenLoss()` nimmt nur das NEGATIVE Signal, `signOutInProgress` nicht wegoptimieren
- Abmelden: was zurueckblieb, die Reihenfolge, das `NonCancellable`, die offene Prozesstod-Luecke
- Eine frische Neu-Autorisierung ist KEIN Kettenbruch
- `DataStoreTokenRepository.observe()`: kein Signal statt falschem Signal
- Der Rotation-Chain-Check von `refresh()`
- `repeatOnLifecycle(RESUMED)` und der Weg zurueck ueber `calendarAuthorizationValid`
- Der AuthorizationClient und die Falle „offline = Zustimmung noetig"

---

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.** Für eine Wecker-App ist
  „leer" die gefährlichste Lüge — nicht von „du hast frei" zu unterscheiden.
- **GMS-Token-Cache liegt außerhalb des App-Speichers** und überlebt die Deinstallation. Nur
  `clearToken()` der Play-Dienste räumt ihn ab (bis Oktober 2026 `GoogleAuthUtil.clearToken()`).
- **`auth_prefs` braucht `corruptionHandler` UND `.catch{}` am `authData`-Flow.** Dort liegt der
  Zustand, der die ganze App gated (`login_status`/`user_email`): eine beschädigte `preferences_pb`
  wäre dauerhaft lese- UND schreib-tot gewesen, und ein Upstream-Fehler hätte in den
  ViewModel-Collectorn die App beendet. Degradation auf „nicht angemeldet" löst einen Re-Login aus —
  das ist hier der richtige Ausgang.
- **`onResult` gehört `OAuth2TokenManager.authorize()`** — es feuert auf jedem Weg genau einmal
  (Sofort-Erfolg, Fehler, Dialog via `handlePermissionResult`). Niemand sonst ruft ihn. Ein
  zweiter Aufruf im `AuthUseCase` startete die Wartung doppelt.
- **`observeTokenLoss()` nimmt nur das NEGATIVE Signal.** `hasValidToken` heißt „`getValidToken()`
  klappt gerade" inkl. Refresh; „Token liegt im Store" ist schwächer und würde das Gate bei einem
  toten, noch nicht verworfenen Token fälschlich aufmachen. `drop(1)` ist Pflicht: die erste
  Emission ist der Ist-Zustand, kein Verlust.
- **AUSNAHME seit v1.43.5: ein Refresh, der nur am NETZ scheitert, zählt als autorisiert**
  (`AuthUseCase.hasCalendarAuthorization()`, ebenso `CalendarUseCase.hasValidAccessToken()`;
  Einstufung wie `WartungTokenFehler`). Das lokale Token gilt 45 Minuten — danach sperrte das Gate
  „Kalender-Zugriff erforderlich" beim App-Start OHNE Netz die ganze Oberfläche samt Wecker-Tab
  und Überspringen, und „Kalender-Zugriff erlauben" scheiterte ebenso (am Emulator mit 1.43.4
  nachgestellt, 30.09.2026). Ein wirklich totes Token meldet der erste Abruf mit Netz
  (401 → `invalidate()` → Auto-Re-Auth). Das Gate soll nur sperren, wenn der Zugriff
  NACHWEISLICH fehlt.
- **`signOutInProgress` nicht wegoptimieren.** Beim Abmelden verwirft die App das Token selbst;
  ohne das Flag stieße `observeTokenLoss()` direkt danach einen Zustimmungsdialog an. `isSignedIn`
  allein reicht **nicht** — die DataStore-Emission trifft asynchron ein, `observeAuthState` ist
  zusätzlich 200ms entprellt.
- **Abmelden heißt: nichts bleibt zurück — und „nichts“ schließt die gestellten Wecker ein.**
  `AuthUseCase.signOut()` verwirft Auth-Daten UND Token (inkl. GMS-Cache);
  `CredentialAuthManager.signOutLocally()` ist nur eine Log-Zeile — sich darauf zu verlassen war der
  erste Fehler. Der zweite (Prüfrunde 8, im Code als "Befund 3" zitiert): Wecker blieben im AlarmManager, im Repository und
  im Direct-Boot-Spiegel stehen, während die App nur noch den Anmeldebildschirm zeigte — also weder
  Wecker-Tab noch Master-Pause, über die sich das hätte abstellen lassen, und der `BootReceiver`
  machte sie nach jedem Neustart erneut scharf. Geräumt wird jetzt in `AuthViewModel.signOut()`
  (`stopScheduledWorkForSignOut()`: Wecker, Schichtspannen, 6h-Wartung, Dimmer-/DND-Tick,
  Hue-Planung, Pre-Alarm-Refresh), in BEIDEN Zweigen. Wer `signOut()` von einer neuen Stelle aus
  ruft, ohne dort ebenfalls aufzuräumen, stellt den Befund wieder her.
- **Die Reihenfolge ist: erst abmelden, dann aufräumen — und sie ist erprobt, nicht geraten.** Die
  umgekehrte Reihenfolge erzeugt den Zustand „angemeldet, aber alle Wecker weg“, den die App
  vollständig selbst wieder auflösen müsste. Der Versuch, ihn mit einem Rückbau zu heilen, hat in
  drei aufeinanderfolgenden Reviews je einen NEUEN Fehler produziert: der Wiederaufbau holte den
  manuellen Wecker nie zurück (er steht in keiner Terminliste); der `ShiftSpanStore` blieb leer,
  Dimmer und DND liefen also ohne Dienstzeiten weiter; und der Knopf „Erneut abmelden“ auf der
  Warnkarte löschte die Warnung selbst, weil der zweite Versuch einen Bestand von 0 vorfindet.
  Das Umdrehen der Reihenfolge ließ den ganzen Apparat ersatzlos entfallen. Merksatz: **wenn ein Fix
  ringsum nachgerüstet werden muss, ist der Schnitt falsch.**
- **Ein `Result.failure` aus `signOut()` heißt NICHT „es ist nichts passiert“**, sondern „Token weg,
  Auth-Daten noch da“: die einzige Fehlerquelle ist `clearAuthData()`, `invalidate()` lief davor.
  Der Nutzer gilt dann weiter als angemeldet, kommt aber an keinen Kalender mehr — die 6h-Wartung
  fällt in ihre fail-safe-Zweige, für neue Schichten entstehen keine Wecker. Deshalb behandelt der
  Aufrufer den Fehlerzweig genauso wie den Erfolgszweig (Prüfrunde 8, Welle 5).
- **Der gesamte Block ab dem Verwerfen des Tokens liegt in EINEM `withContext(NonCancellable)`** —
  nicht nur das Aufräumen. Der Punkt ohne Wiederkehr liegt früher als gedacht: `signOut()` ruft über
  `invalidate()` das Leeren des GMS-Caches (damals `GoogleAuthUtil.clearToken()`), einen Aufruf, der bis zum Timeout
  hängt. Vorher lag die Sperre allein um das Aufräumen, erreicht wurde sie also erst danach: ein
  Wegwischen der App genau in diesem Fenster ließ Token weg und Wecker armiert zurück.
- **Bewusst offene Restlücke: Prozesstod im Abmelde-Fenster.** `NonCancellable` schützt gegen Abbruch,
  nicht gegen Prozesstod; stirbt der Prozess zwischen dem Verwerfen der Anmeldung und dem Ende des
  Aufräumens, bleiben Wecker armiert. **Kein neuer Bug — nicht erneut melden.** Ausweg für den
  Nutzer: erneut anmelden (der nächste Sync räumt auf) oder das Abmelden wiederholen. Ein
  dauerhafter Merker dagegen war gebaut und wurde nach Messung VERWORFEN (Begründung im KDoc von
  `signOut()`): er wurde bei einer Neuanmeldung nirgends gelöscht, sperrte danach bei JEDEM Neustart
  die Wiederherstellung und ließ die Wartung alle Wecker des NEUEN Kontos löschen — gefährlicher als
  die enge Lücke, die er schließen sollte. Der Unterschied zum gleich gebauten, aber richtigen
  Räumauftrag der Kalender-Abwahl: **ein dauerhafter Auftrag braucht eine Gegenfrage.** Die Wartung
  kann die Kalenderauswahl erneut lesen und den Auftrag als hinfällig verwerfen; der Abmelde-Auftrag
  wusste nur „ein Abmelden ist unfertig“, nie „der Nutzer ist noch abgemeldet“.
- **Eine frische Neu-Autorisierung ist KEIN Kettenbruch** (`TokenData.isLegitimateSuccessorOf`, das
  vollständige Urteil von `refresh()`). Drei legitime Fälle: identisch, direkt rotiert — und ein per
  `authorize()` geholtes Token. Das rotiert nicht, sondern beginnt eine NEUE Kette
  (`previousRotationId = null`, `rotationCount = 0`) und stammt damit zwangsläufig nicht vom
  bisherigen ab. Landete dieser Write zwischen dem Lesen des alten Tokens und der Prüfung — realistisch,
  weil dieser `@Singleton` keinen Mutex hat und Wartungslauf, Pre-Alarm-Worker und UI unabhängig
  refreshen —, galt er als Diebstahl und `clear()` löschte ausgerechnet das Token, das der Nutzer
  sich soeben per „Kalender-Zugriff erneuern" geholt hatte. Die Oberfläche zeigte danach weiter
  „angemeldet", während jeder Wartungslauf ohne Token abbrach. Der Diebstahls-Zweig bleibt: ein
  fremdes Token, das ÄLTER ist als der bekannte Stand, ist weiterhin ein Bruch (`TokenDataTest`).
- **`DataStoreTokenRepository.observe()` nutzt `retryWhen`, und der Fehlerfall emittiert NICHTS.**
  Zwei Fehler in einem: Ein `.catch { emit(emptyPreferences()) }` **beendet den Flow** (fängt,
  emittiert, schließt normal ab) — der Token-Verlust-Wächter war danach für die ganze
  Prozesslaufzeit tot, ein SPÄTERER echter Verlust wurde nie bemerkt (dieselbe Fehlerklasse wie
  beim `CalendarSelectionRepository`-Collector). Und das emittierte „kein Token" ist ein FALSCHES
  NEGATIVSIGNAL: der einzige Konsument (`AuthViewModel.observeTokenLoss`) wertet ausschließlich
  dieses aus und hätte einem Nutzer mit intaktem Token nach einem einmaligen IO-Fehler eine
  Zwangs-Neuanmeldung aufgedrängt. Richtung deshalb: **kein Signal statt falsches Signal** — der
  Wecker hängt nicht an diesem Flow, die Notlage-Neuanmeldung schon.
- **`OAuth2TokenManager.refresh()`s Rotation-Chain-Check muss den NEUEN Token gegen die ID des
  ALTEN prüfen, nicht zwei „previous"-Zeiger gegeneinander.** `TokenData.validateRotation(id)` ist
  `this.previousRotationId == id` — der korrekte Aufruf ist also
  `storedToken.validateRotation(currentToken.rotationId)` („ist `storedToken` durch Rotation direkt
  aus `currentToken` entstanden?"), NICHT `currentToken.validateRotation(storedToken.
  previousRotationId)` (vergleicht zwei fremde Vorgänger-IDs miteinander — das ist nur bei
  `storedToken == currentToken` je wahr). Die falsche Variante schlägt bei JEDER legitimen
  gleichzeitigen Rotation fehl: `OAuth2TokenManager` ist ein Hilt-`@Singleton` ohne Mutex um
  `getValidToken()`/`refresh()`, und `AlarmMaintenanceService`, `CalendarPreAlarmRefreshWorker`,
  `CalendarUseCase` und `AuthUseCase.hasCalendarAuthorization()` rufen ihn alle unabhängig auf —
  zwei nahezu gleichzeitige Refreshs sind der Normalfall, kein Diebstahl. Die falsche Variante
  löste bei jedem Treffer `tokenRepository.clear()` + Zwangs-Re-Login aus, obwohl der erste Refresh
  längst erfolgreich war. `TokenDataTest` hält die Rotationsketten-Semantik jetzt fest.


- **`repeatOnLifecycle(RESUMED)`, nicht STARTED.** Ein Activity-Start aus dem Hintergrund verwirft
  Android still; CONFLATED puffert das Signal, bis die App vorne ist. Deckt den Verlust im
  Maintenance-Service mit ab. Der Auto-Dialog ist **nur für den Laufzeit-Verlust** gedacht, nicht
  für den Kaltstart — dort landet der Nutzer bewusst auf dem `CalendarAuthorizationScreen` und
  tippt selbst, statt beim Öffnen von einem Dialog überfallen zu werden (ausdrückliche
  Nutzer-Entscheidung).
- **`calendarAuthorizationValid` nie bedingungslos `true` setzen** — daran hängt der einzige Weg
  zurück („Kalender-Zugriff erneuern"). Gleiche Fehlerklasse wie `getOrElse { emptyList() }`.
  Und umgekehrt nie bei einem reinen Verbindungsausfall `false` — das ist
  `kalenderNichtErreichbar` — und nie, wenn alle Kalender nur FEHLEN (dann
  `unavailableCalendarIds`; Hergang im Kalender-Skill, `kalender-datenfluss.md`). Nach jeder
  GELUNGENEN Autorisierung lädt MainActivity die Termine neu (`kalenderZugriffErneuert`), sonst
  bleibt die Warnung nach dem Erneuern stehen.
- **Der GMS-Token-Cache meldet sich als 401 „Invalid Credentials" oder 403
  `ACCESS_TOKEN_SCOPE_INSUFFICIENT`** — für ein Token, das GMS ohne Consent-Dialog herausgibt.
  `getValidToken()` prüft nur die LOKALE Ablaufzeit und merkt davon nichts. Nur
  `GoogleAuthUtil.clearToken()` räumt den Cache ab; er liegt außerhalb des App-Speichers und
  überlebt die Deinstallation.

## Der AuthorizationClient und die Falle „offline = Zustimmung noetig" (Oktober 2026)

**Warum umgestellt.** `GoogleAuthUtil.getToken/clearToken` sind seit play-services-auth 21.3.0
(09.12.2024) abgekuendigt; Google hat im August 2026 die Sign-in-APIs bereits entfernt. Am Fairphone
loggt GMS bei jeder Anfrage `[GetToken] The requested account is not visible to ...` (CF-Alarm
steht in keiner Kontosichtbarkeit). Entschieden am 01.10.2026: Weg B, AuthorizationClient (Vorlage
in `..Projektdateien/entscheidungsvorlage-authorizationclient-2026-09-30.md`; Weg A,
Kontosichtbarkeit herstellen, verworfen als Symptombehandlung einer abgekuendigten API).

**Was der Spike gemessen hat** (Emulator API 37, 30.09.2026, in der 6h-Wartung ohne Activity):
die per GoogleAuthUtil erteilte Zustimmung wird erkannt, das Token ist IDENTISCH (ein Cache fuer
beide Wege); gesperrt und dunkel kommt ein frisches Token in ~330 ms; `tokenResponseParams` ist
immer `null` (kein Ablaufdatum - die App bucht weiter pauschal 3600 s). Und die Falle: **Flugmodus
nach `clearToken` liefert keinen Fehler, sondern Erfolg mit `hasResolution = true`, `token = null`**
- wieder online kam ohne Zutun ein Token, die Zustimmung bestand die ganze Zeit.

**Was daraus folgt** (`AutorisierungsEinstufung`, `KalenderAutorisierung`, `OAuth2TokenManager`):
- **Ohne validiertes Netz ist nichts endgueltig** - weder Resolution noch Statuscode. Wer
  `hasResolution` ungeprueft als Anmeldung liest, baut den Fehlalarm vom 09.09.2026 nach, und der
  Refresh-Pfad verwirft bei `ConsentRequired` obendrein das Token.
- **„Validiert" ist nur eine Momentaufnahme** (adversariale Review 01.10.2026, 3/3 bestaetigt):
  Android nimmt `VALIDATED` verzoegert zurueck (am 09.09. kam „kein Internet" eine Sekunde NACH
  dem Netzfehler), und ein Netzwechsel kann in die Anfrage fallen. Deshalb misst `holeEinmal()`
  vor UND nach dem Aufruf, und im Hintergrund gilt eine Resolution erst nach einem zweiten Abruf
  5 s spaeter als Zustimmungsfall. Im Vordergrund (Nutzer tippt) ohne diese Wartezeit - ein
  unnoetiger Dialog kostet nichts. Restrisiko: ein Netz, das laenger als 5 s tot und trotzdem
  „validiert" ist, kostet das Token; geheilt wird das beim naechsten Oeffnen der App (die
  Zustimmung besteht, `authorize()` liefert ohne Dialog).
- Statuscodes 14/16/19 (unterbrochen, abgebrochen, Verbindung zu den Play-Diensten verloren) sind
  voruebergehend - GoogleAuthUtil meldete genau diese als `IOException`.
- Voruebergehend wird als `IOException` in die Ursachenkette gesetzt. So blieben `WartungTokenFehler`,
  `AuthUseCase.hasCalendarAuthorization()` und `CalendarUseCase` unveraendert - sie kannten nur
  den GoogleAuthUtil-Vertrag.
- **Vor dem Refresh wird weiter der Cache geleert**, und scheitert das, bricht der Refresh
  VORUEBERGEHEND ab: sonst kaeme dasselbe Token mit Restlaufzeit zurueck, die App buchte 3600 s,
  und der fruehe 401 fuehrte ueber `invalidate()` in die Neuanmeldung.
- Ein Abbruch der GMS-AUFGABE kommt als `java.util.concurrent.CancellationException` (dieselbe
  Klasse wie ein Coroutine-Abbruch). Weitergeworfen wird nur, wenn die Coroutine wirklich
  abgebrochen ist (`ensureActive()`), sonst waere ein GMS-Aussetzer ein stilles Ende der Wartung.
- `Tasks.await` mit eigenem Deckel (20 s): ohne Frist wartet es unbegrenzt, etwa waehrend sich die
  Play-Dienste aktualisieren.
- Der Client wird erst im Aufruf geholt, nie beim Bauen (Singleton am Application-Graphen, der auch
  im Direct-Boot-Prozess entsteht).
- Im Vordergrund oeffnet der `PendingIntent` per `startIntentSenderForResult` denselben
  Request-Code wie frueher; `handlePermissionResult()` holt das Token mit einem zweiten
  `authorize()`. Offline wird KEIN Dialog gestartet - die Meldung nennt die Verbindung.

## Zero-Tap-Wiederherstellung über Restore Credentials (#55, 1.46.0)

**Warum.** Play verlangt ab April 2027 für JEDE App mit Anmeldung eine Zero-Tap-Wiederherstellung;
der Ausweg über Block Store galt nur für Integrationen bis 30.09.2026.

**Was der Schlüssel ist.** Ein WebAuthn-Restore-Schlüssel, dessen `user.id` die E-Mail trägt (beim
Lesen als `response.userHandle`). KEIN Anmeldenachweis — es gibt keinen Server, der Challenge oder
Signatur prüfen könnte, und es braucht keinen: Zugriff gibt weiter `authorize()` mit `setAccount()`.
E-Mail statt Googles `sub`, weil nur sie `setAccount()` ohne Oberfläche erlaubt. Über 64 Byte → kein
Schlüssel (nicht kürzen: eine gekürzte Adresse ist ein fremdes Konto).

**Ablauf.** Anlegen nach `signIn()` und einmal beim Start für Bestandsnutzer (Merker fehlt SICHER).
Lesen beim Start nur abgemeldet, aus `MainActivity` (Activity-Kontext), `withTimeoutOrNull(5 s)`.
Ohne Ende-zu-Ende-Backup (`E2eeUnavailableException`) wird lokal angelegt — hilft beim Kabel-Transfer.
Kein eigener `BackupAgent`. API < 28: still aus. Das Interface `AnmeldeWiederherstellung` bleibt, weil
`CredentialManager.create()` statisch ist; der Konstruktor hält nur den App-Kontext (Direct Boot).

**Die Falle beim Abmelden (Review 02.10.2026, 3:0 bestätigt).** Scheiterte das Löschen (GMS-Fehler,
Timeout, Prozessende direkt nach dem Tipp), meldete der nächste Kaltstart auf DEMSELBEN Gerät still
wieder an. Deshalb der Abmelde-Vermerk in `noBackupFilesDir` (reist nicht mit, `clearAuthData()` löscht
ihn nicht): gesetzt VOR dem Löschen, unlesbar gilt als gesetzt, geräumt erst durch eine neue Anmeldung
unter der Schlüssel-Sperre. Zweite Falle: eine HALBE Abmeldung löschte den Schlüssel, ließ den Merker
aber stehen — es entstand nie wieder einer; `vergissWiederherstellungsSchluesselAngelegt()` räumt ihn.
Eine Mutex um Anlegen und Löschen verhindert, dass ein laufendes Anlegen nach dem Löschen schreibt.

**Am Emulator belegt (02.10.2026):** Bestandsnutzer-Update und Neuanmeldung legen den Schlüssel an
(„nur lokal“, Emulator ohne E2E-Backup — damit auch gemessen: das Anlegen braucht `assetlinks.json`
nicht). Kaltstart nach `pm clear` findet keinen (wie dokumentiert gelöscht) und zeigt sofort den
Login. **Nicht belegt:** die eigentliche Wiederherstellung auf einem neuen Gerät — das geht nur mit
echtem Gerätewechsel (Google-Backup mit Bildschirmsperre). `docs/.well-known/assetlinks.json` trägt
Play-Signatur-, Upload- und Debug-Schlüssel.
