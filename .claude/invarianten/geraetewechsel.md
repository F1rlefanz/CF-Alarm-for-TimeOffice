# Gerätewechsel & Konfigurations-Datei

> Ausgelagert aus `CLAUDE.md` (17.08.2026). Dort steht die Kurzregel, hier der Hergang:
> warum die Regel existiert, welcher Bug sie erzwungen hat, welche Messung sie belegt.
> **Vor Änderungen in diesem Bereich lesen.**

---

### Gerätewechsel & Konfigurations-Datei (seit v1.23.0)

- **`DeviceLocalFlagsGuard` (erster Schritt in `initializeApp()`, best-effort) setzt beim erkannten
  Gerätewechsel gerätelokale Flags zurück.** Der `settings`-Store liegt richtigerweise im
  Android-Backup, enthält aber auch vier „schon abgelehnt"-Markierungen
  (`battery_prompt_dismissed`, `unused_app_restrictions_dismissed`,
  `timeoffice_health_prompt_dismissed`, `oem_hint_shown_<OEM>`). Nach einem Restore fragte die App auf dem neuen
  Gerät nie wieder nach Akku-Ausnahme und „Pause bei Nichtnutzung" — genau die zwei Einstellungen,
  die in diesem Projekt nachweislich Wecker verschluckt haben. **Ein selektiver Ausschluss einzelner
  Schlüssel ist unmöglich: ein Preferences-Store ist EINE Datei** — deshalb ein Wächter über
  `Build.FINGERPRINT` statt einer Backup-Regel. Zurücksetzen ist harmlos, die Hinweise erscheinen nur,
  wenn die Einstellung real fehlt; ein unerwartet klingelnder Wecker ist deutlich harmloser als ein
  unerwartet stummer. **Bewusste Grenze:** fehlt der Marker (Erstinstallation oder Bestandsinstall
  von vor dieser Version), wird NICHT zurückgesetzt — sonst verliert ein laufender Install seine
  Abweisungen. Die beiden Backup-Regel-Dateien müssen inhaltlich identisch bleiben, sonst sichert
  dasselbe Gerät je nach Android-Version Unterschiedliches.
- **Eine mitgesicherte Master-Pause wird über `MasterPauseUseCase.resume()` aufgehoben, NICHT indem
  `DeviceLocalFlagsGuard` den Schlüssel löscht** — deshalb steht `master_pause_enabled` bewusst
  NICHT in `DEVICE_LOCAL_KEY_PATTERNS`, und `resetIfDeviceChanged()` gibt stattdessen `Boolean`
  zurück, damit `initializeApp()` `resume()` rufen kann. Eine Pause ist mehr als das
  DataStore-Flag: `pause()` schreibt zusätzlich den Device-Protected-Spiegel (den der
  `BootReceiver` VOR der ersten Entsperrung liest), löscht die Alarme und reißt 6h-Wartung,
  Dimmer-Tick, DND-Tick, Hue-Planung und Pre-Alarm-Refresh ab. Wer nur den Schlüssel entfernt,
  hinterlässt eine App, die „nicht pausiert" ANZEIGT, deren Boot-Wiederherstellung aber dauerhaft
  gesperrt bleibt und deren Hintergrundketten nie wieder anlaufen — die gefährlichere Variante des
  Bugs, den der Wächter beheben soll. (Der erste Wurf machte genau das; `master_pause_until`
  existiert im Code überhaupt nicht — ein erfundener Schlüssel, der in zwei Produktivdateien, zwei
  Tests und dieser Datei stand. `MasterPausePrefs` kennt nur `master_pause_enabled`.)
- **Der Konfigurations-Export (Settings-Tab → „Konfiguration" → „Exportieren"/„Importieren")
  entscheidet durch AUSSCHLUSS, nicht durch Aufzählen.** Die Stores werden generisch exportiert,
  `ConfigBackupFilter` nimmt heraus, was nicht mit darf — damit ist eine neue Einstellung automatisch
  dabei statt beim nächsten Feature stillschweigend zu fehlen. Drei Ausschlussgründe:
  **Laufzeitzustand** (`active_alarms`, Skip-Marker, Dimmer-Render- und -Korrekturzustand,
  Wartungs-Zeitstempel und vor allem `master_pause_enabled` — ein importierter Pausenzustand lässt
  den Wecker STUMM, und niemand sucht die Ursache in einer Importdatei), **Gerätebezug/Zugangsdaten**
  (Hue-Bridge-Username und -IP, die von diesem Gerät registrierte Zen-Regel-ID, Tokens, Anmeldung,
  die kontogebundene Kalenderauswahl, der Marker des `DeviceLocalFlagsGuard`, `shift_config` — das
  geht bewusst über das typisierte Repository) und **gerätelokale Onboarding-Markierungen** (dieselbe
  Liste wie der Wächter, eine Quelle statt zweier Kataloge). **Der Filter gilt in BEIDE Richtungen:**
  beim Import wird jeder Schlüssel erneut geprüft, eine handbearbeitete oder ältere Datei kann nichts
  einschleusen; abgelehnte Schlüssel werden dem Nutzer BENANNT. `exclusionReason()` ist der EINE Ort
  der Entscheidung, `isExportable()` leitet sich davon ab. **Die Ausschlussliste ist aus einer
  Inventur ALLER `*PreferencesKey("…")` im Baum abgeleitet, nicht aus den Schlüsseln einiger Pakete:**
  der erste Wurf war lückenhaft, der erste echte Export enthielt genau drei Schlüssel und ALLE DREI
  gehörten nicht hinein — darunter `active_alarms`. Wer eine neue Laufzeitgröße einführt, trägt sie
  hier ein; ein Test hält jede Kategorie fest.
- **Der Import lehnt eine LEERE Definitionsliste ab.** kotlinx.serialization füllt ein fehlendes
  `definitions`-Feld stillschweigend mit `emptyList()`; aus „Datei unvollständig oder von Hand
  verstümmelt" würde lautlos „keine Schichten" — und das ist der dokumentierte Weg zu NULL ALARMEN
  (Save → Cache-Invalidierung → `observeExternalConfigChanges()` → `syncAlarms()` erkennt nichts →
  kalenderbasierte Alarme weg), während der Import „Erfolg: 0 Schichtdefinitionen" meldet. Dieselbe
  Überlegung wie `structuralRejection` für die beiden JSON-Regelwerke, nur für den wertvollsten Teil.
- **Der erwartete TYP eines importierten Wertes kommt vom SCHLÜSSEL, nicht aus der Datei**
  (`ConfigBackupUseCase.typeMismatch`). Vorher prüfte `applyValue` nur, ob sich der Wert in den
  BEHAUPTETEN Typ parsen lässt — damit entschied eine fremde Datei über den DataStore-Typ. Ein
  falsch typisierter Wert ist schlimmer als ein fehlender: er liegt reboot-fest in der
  `preferences_pb`, und der nächste Lesezugriff scheitert mit einer ClassCastException, BEVOR ein
  `?:`-Default oder `coerceIn` greifen kann (`snooze_minutes` als String → `AlarmPrefs` wirft bei
  jedem Alarm-Feuern, der `AlarmReceiver` verschluckt es, der Wecker bleibt stumm). Erwartung aus
  dem lokalen Bestand, sonst aus einer kleinen Liste bekannter Zahlen-Schlüssel; ein lokal
  unbekannter Schlüssel behält den Typ der Datei (bei einem Schlüssel aus einer neueren Version ist
  das die einzige Information, und er kann keinen bestehenden Leser beschädigen).
- **Der Schlüssel-Filter sagt nichts über den WERT.** Eine Exportdatei ist Text: von Hand
  bearbeitbar, aus einer älteren Version, unterwegs beschädigt. Zwei Zahlen sind deshalb
  zusätzlich bereichsgeprüft (`ConfigBackupFilter.rangeRejection`, bewusst nur diese zwei):
  `snooze_minutes` ≤ 0 legt den Schlummer-Alarm in die VERGANGENHEIT — er feuert sofort wieder und
  der Wecker lässt sich nicht mehr wegdrücken; `dnd_oncall_cutoff_min` außerhalb `0..1439` lässt
  `DndOnCallCutoffResolver`s `LocalTime.ofSecondOfDay()` werfen und tötet den DND-Tick bei jedem
  Lauf. **Beide sind zusätzlich im LESEPFAD geklemmt** (`AlarmPrefs`, `DndPrefs`) — genau wie
  `DimOverlayPrefs` es überall tut: das Android-Backup ist ein zweiter Weg, auf dem so ein Wert
  ankommt, und den sieht der Import nie.
- **Unlesbare Regelwerke werden beim Import BENANNT abgelehnt**
  (`ConfigBackupUseCase.structuralRejection` für `dim_rules`/`hue_schedule_rules`) — obwohl beide
  Leser einen kaputten Wert bereits abfangen. Genau dieser Rückfall auf „leere Liste" ist das
  Problem: der Import meldete Erfolg, und der Nutzer sah eine leere Regelliste ohne Grund. Der
  Import ist der letzte Ort, an dem das noch sagbar ist.
- **`ShiftConfig.withCodeAssignedTo()` (Kürzel-Vorschlagskarte) macht DREI Dinge zusammen, weil
  jedes einzeln wirkungslos wäre:** Muster ergänzen, **Zieldefinition aktivieren** (die
  `ShiftRecognitionEngine` beachtet seit v1.23.0 nur aktivierte Definitionen — eine Zuordnung an
  eine ausgeschaltete Schicht wäre ein garantierter Nichts-passiert-Klick, und die Karte bietet
  genau solche Kürzel an, weil sie von keiner aktivierten Definition getroffen werden) und das
  Kürzel **bei allen anderen Definitionen entfernen** (zwei Besitzer hieße: `findDefinitionFor`
  nimmt den ersten Treffer, und die LISTENREIHENFOLGE entscheidet still über die Weckzeit). Reine
  Funktion im Modell, damit alle drei Fallen testbar festgehalten sind.

