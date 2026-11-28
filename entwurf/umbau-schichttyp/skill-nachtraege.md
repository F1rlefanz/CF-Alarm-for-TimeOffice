# Nachträge für Skills

Headless ist `.claude/**` schreibgeschützt. Lehren aus Umbau-Paketen stehen hier, der Eigentümer
übernimmt sie interaktiv in den passenden Skill und streicht den Eintrag.
Format: Skill · Paket · die normative Zeile · Hergang in einem Satz.
Entfernt ein Paket ein Symbol, das ein Skill nennt, steht der Punkt mit Signalwort da („`X` entfällt …“),
sonst nimmt `tools/aufraeumen/pruefe_reste.py` das Symbol nicht aus (`entwurf/umbau-schichttyp/ABLAUF.md`).

- cfalarm-persistenz-und-auth · #246 · **Die Lichtregeln des Typs liegen roh in `ShiftDefinition.licht` (`TypLicht`, Schlüssel `licht.regeln`, Muster `TypDimmen`): Leser nehmen `TypLicht.regeln` (gedeutet zu `HueScheduleRule`), Schreiber `TypLicht.mitRegeln`; eine undeutbare Regel (unbekannter Wert in `TargetType`, `ActionType`, `HellAb`) bleibt wortgleich gespeichert und läuft nicht. Marker der Licht-Migration ist `ShiftConfig.lichtStufe`, bewusst NICHT `modellStufe = 2`, und `aufStandardZurueckgesetzt()` übernimmt beide.** Hergang: 1.59.0 kennt `modellStufe` und schreibt sie zurück, die Regeln am Typ nicht – nach einem Revert stünde die Stufe ohne Regeln, und die Migration liefe nie wieder (Test `AltDecoderKompatibilitaetTest`, eingefrorene Kopie `V1_59_0`).
- cfalarm-hue · #246 · **`shiftPattern` einer am Typ gespeicherten Regel hat keine Bedeutung (der Ort sagt den Typ; wer ausführt, setzt den Typnamen ein) und braucht deshalb KEINEN Eintrag im Umbenennungs-Nachzug; gespeichert wird es wortgleich.** Die feste Zeile „Ohne Kalendereintrag“ hat kein Licht (E8: ohne Weckzeit kein Licht). Eine Regel ohne `id` bekommt bei jedem Lesen eine neue (`generateId()`), `mitRegeln` schreibt sie fest – hingenommen.
