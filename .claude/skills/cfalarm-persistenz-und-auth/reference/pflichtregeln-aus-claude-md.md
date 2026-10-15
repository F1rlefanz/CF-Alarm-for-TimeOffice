# Pflichtregeln aus der CLAUDE.md, mit Begründung

Die CLAUDE.md nennt je Regel nur den Kernsatz, damit sie immer geladen bleibt. Hier steht der
volle Wortlaut, wie er bis zur Kürzung dort stand. Gilt immer; Änderungen an einer Regel
betreffen beide Stellen.

## Persistenz

- **`isPersistenceBlocked()` heisst „der Bestand ist unlesbar", NICHT „der letzte Schreibvorgang
  ging schief".** Verodert raeumte die Master-Pause nach einem einzigen fehlgeschlagenen Write den
  Bestand und liesse jeden Systemalarm scharf zurueck (`clearInternalAlarms()` ueberspringt bei
  „unlesbar" die `cancelSystemAlarm()`-Schleife). Ein Schreibfehler hat einen eigenen Weg zu
  seinem einzigen Konsumenten.
- **Stille Degradierung darf nie zur Schreibwahrheit werden.** DataStore liest vor jedem Write
  erneut; wer einen Lesefehler auf „leer"/„Default" degradiert, speist die Notlage-Leere in den
  nächsten Read-Modify-Write und überschreibt echte Nutzerdaten.
- **Ein CE-DataStore-Read VOR der ersten Entsperrung wirft NICHT — er liefert still leere
  Preferences** und meldet Erfolg. Deshalb fragt `AlarmRepository` vorher den `UserManager` und lädt
  nach dem Entsperren nach. **DataStore hält dieses Leer für den GANZEN Store im Prozess** — EIN
  fremder Leser im gesperrten Prozess genügt, und danach liest jeder „leer“ (bis 1.45.0 der
  Hue-Manager: Alarm-Bestand weg). Wer beim Prozessstart einen CE-Store liest, fragt vorher
  `NutzerEntsperrung.istEntsperrt()`.
- **Der Direct-Boot-Spiegel wird bei JEDEM erfolgreichen Load abgeglichen.**
- **Die REIHENFOLGE von `.catch` und `.map` in einem Preferences-Flow ist tragend** — `.catch`
  gehört **hinter** das `.map` und muss den Cache invalidieren.
- **Die RICHTUNG der Degradation ist jeweils bewusst gewählt**: Master-Pause → `false` (nicht
  pausiert), Dimmer → aus, Onboarding-Hinweise → werden gezeigt. Im Zweifel klingeln und hell.

## Auth

- **Kein `getOrElse { emptyList() }` auf Auth-behafteten Ergebnissen.**
- **`refresh()` prüft den NEUEN Token gegen die ID des ALTEN**
  (`storedToken.validateRotation(currentToken.rotationId)`) — vertauscht erzwingt jede legitime
  gleichzeitige Rotation einen Re-Login.
- **Eine frische Neu-Autorisierung ist KEIN Kettenbruch.**
- **`DataStoreTokenRepository.observe()` nutzt `retryWhen`, und der Fehlerfall emittiert NICHTS** —
  kein Signal statt falschem Signal.

## Key Constraints

- **Es gibt bewusst keinen `@TokenDataStore`-Qualifier.** `DataModule` stellte bis v1.11.2 einen
  bereit (`oauth_tokens`), den **niemand** injizierte — am Gerät verifiziert: die Datei existierte
  gar nicht. **Die Falle ist die Namensgleichheit:** `DataStoreTokenRepository` hat ein privates
  Feld `tokenDataStore` (den verschlüsselten Store) — wer nur den Namen sieht, hält den Provider
  für benutzt und holt ihn zurück. Dann gibt es eine zweite Wahrheit, und ein Klartext-Store für
  Tokens ist genau das, was Tink verhindern soll.
