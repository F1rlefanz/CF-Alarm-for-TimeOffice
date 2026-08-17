# Auth und Token-Rotation — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

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
- **Abmelden heißt: nichts bleibt zurück.** `AuthUseCase.signOut()` verwirft Auth-Daten UND Token
  (inkl. GMS-Cache). `CredentialAuthManager.signOutLocally()` ist nur eine Log-Zeile — sich darauf
  zu verlassen war der Fehler.
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
