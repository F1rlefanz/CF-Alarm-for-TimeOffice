# Nachträge für Skills

Headless ist `.claude/**` schreibgeschützt. Lehren aus Umbau-Paketen stehen hier, der Eigentümer
übernimmt sie interaktiv in den passenden Skill und streicht den Eintrag.
Format: Skill · Paket · die normative Zeile · Hergang in einem Satz.
Entfernt ein Paket ein Symbol, das ein Skill nennt, steht der Punkt mit Signalwort da („`X` entfällt …“),
sonst nimmt `tools/aufraeumen/pruefe_reste.py` das Symbol nicht aus (`entwurf/umbau-schichttyp/ABLAUF.md`).

- cfalarm-persistenz-und-auth · #213 · **Die Dimm-Fenster am Typ liegen roh (`TypDimmen.fensterGespeichert: List<JsonElement>`) und werden beim Lesen gedeutet: Leser nehmen `fenster`, Schreiber `mitFenster(...)`, nie `copy(fensterGespeichert = ...)`.** `DimWindow` trägt kotlinx-Enums, `ShiftConfigRepository.json` hat kein `coerceInputValues` – ein unbekannter Anker (spätere Version, dann Revert) machte sonst die ganze Schicht-Konfiguration `Broken` und mit ihr den Wecker-Abgleich; so bleibt das undeutbare Fenster wortgleich gespeichert und dimmt nur nicht (Test `TypEinstellungenModellTest`).
- cfalarm-persistenz-und-auth · #213 · **`ShiftConfig.modellStufe` ist der Migrations-Marker; wer eine `ShiftConfig` neu baut statt per `copy()`, übernimmt sie.** Der Marker liegt bewusst im JSON und nicht als eigener Schlüssel, damit ein Revert ihn mit den neuen Feldern verliert; das „Zurücksetzen auf Standard“ lebt in `ShiftConfigScreen.resetToDefaultsPreservingAutoAlarm`, nicht in `ShiftConfig.kt` – dort fiel die Stufe beim Bau von #213 auf 0 (Betreuer-Frage in #213).
