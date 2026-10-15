# Pflichtregeln aus der CLAUDE.md, mit Begründung

Die CLAUDE.md nennt je Regel nur den Kernsatz, damit sie immer geladen bleibt. Hier steht der
volle Wortlaut, wie er bis zur Kürzung dort stand. Gilt immer; Änderungen an einer Regel
betreffen beide Stellen.

## Dimmer, DND, Hue

- **Es gibt GENAU EINE Dimm-Fenster-Quelle: die Regeln** (seit v1.34.0, ein Schalter `dim_enabled`).
  Wer eine zweite, „eingebaute" Quelle daneben stellt, koppelt Schalter aneinander (Hergang im
  Dimmer-Skill).
- **Leere Fensterliste = Unterdrückung dieser Nacht**, NICHT „keine Regel". Nicht wegoptimieren.
- **Die Tick-Kette darf nicht abreißen** — Keep-alive (6 h) plus Retry (15 min) nach Lesefehler.
- **Das Aufräumen der Dimm-VORSCHAU darf nicht am `viewModelScope` hängen** — sonst bleibt der
  Bildschirm systemweit verdunkelt, wenn der Nutzer die App verlässt.
- **Jeder `DimOverlayPrefs`-Setter MUSS direkt danach `DimScheduleUseCase.enable()` rufen**, unentprellt.
- **Die Dienstzeit-Fenster kommen aus `ShiftSpanStore`, nicht aus dem Alarm-Bestand** — ein Alarm
  überlebt die Weckzeit nicht.
