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

---

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.** Für eine Wecker-App ist
  „leer" die gefährlichste Lüge — nicht von „du hast frei" zu unterscheiden.
- **GMS-Token-Cache liegt außerhalb des App-Speichers** und überlebt die Deinstallation. Nur
  `GoogleAuthUtil.clearToken()` räumt ihn ab.
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
  `invalidate()` `GoogleAuthUtil.clearToken()`, einen NETZaufruf, der ohne Netz bis zum Timeout
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
- **Der GMS-Token-Cache meldet sich als 401 „Invalid Credentials" oder 403
  `ACCESS_TOKEN_SCOPE_INSUFFICIENT`** — für ein Token, das GMS ohne Consent-Dialog herausgibt.
  `getValidToken()` prüft nur die LOKALE Ablaufzeit und merkt davon nichts. Nur
  `GoogleAuthUtil.clearToken()` räumt den Cache ab; er liegt außerhalb des App-Speichers und
  überlebt die Deinstallation.
