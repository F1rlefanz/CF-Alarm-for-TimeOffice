# Pflichtregeln aus der CLAUDE.md, mit Begründung

Die CLAUDE.md nennt je Regel nur den Kernsatz, damit sie immer geladen bleibt. Hier steht der
volle Wortlaut, wie er bis zur Kürzung dort stand. Gilt immer; Änderungen an einer Regel
betreffen beide Stellen.

## Navigation

- **Zurück gehört dem `BackHandler` in `MainScreen`** — es gibt keinen Backstack, ohne Handler
  beendet jeder Druck die App. Wer einen neuen `NavigationState` ergänzt, muss ihn dort mitbedenken.
- **Die vier Onboarding-Gates sind nicht optional**, und „Später" beim Akku-Gate heißt ERLEDIGT,
  nicht abgebrochen — sonst wird dem Nutzer der nächste Schritt NIE angeboten.
