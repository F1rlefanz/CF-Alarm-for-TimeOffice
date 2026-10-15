# Pflichtregeln aus der CLAUDE.md, mit Begründung

Die CLAUDE.md nennt je Regel nur den Kernsatz, damit sie immer geladen bleibt. Hier steht der
volle Wortlaut, wie er bis zur Kürzung dort stand. Gilt immer; Änderungen an einer Regel
betreffen beide Stellen.

## Dimmer, DND, Hue

- **Die V1-Hue-API antwortet auch bei ABLEHNUNG mit HTTP 200** — das Urteil steht im Body.
- **Ein Fehlschlag der Bridge darf nicht zur leeren Liste degradieren**, und die Subnetz-Prüfung ist
  ein HINWEIS auf das Timeout, **niemals ein Veto**.
- **`cleanup()` auf Prozess-Singletons cancelt NUR Kinder** (`cancelChildren()`), nie den Scope.
- **`healthCheckScope` braucht einen `CoroutineExceptionHandler`** — ein `SupervisorJob` allein
  lässt die Exception den PROZESS beenden.
