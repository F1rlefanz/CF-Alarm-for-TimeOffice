# TimeOffice-Abhaengigkeit — Hergang

> Hergang zu den Kurzregeln in `CLAUDE.md` und in der `SKILL.md` daneben: welcher Bug die
> Regel erzwungen hat, welche Messung sie belegt, welche Alternative verworfen wurde.
> Jede Zeile hier hat einmal echten Schaden verhindert — im Zweifel gilt sie, nicht die Intuition.

## Inhalt

- CFAlarms Funktion hängt an einer Kette außerhalb dieser App
- Die Health-Karte: nur die Akku-Ausnahme ist für ein fremdes Paket prüfbar
- Kein Akku-Dialog für TimeOffice, `<queries>` ist Pflicht, kein Unit-Test für den Helper
- Das Onboarding-Gate hängt am automatischen Weg
- TimeOffice trägt erst beim Öffnen ein (12.10.2026) — und „TimeOffice öffnen"
- Keine offizielle Schnittstelle zum eigenen Dienstplan (11.10.2026)

---

- **CFAlarms gesamte Funktion hängt an einer Kette außerhalb dieser App**: TimeOffice
  (`de.pradtke.timeoffice`) schreibt den Dienstplan (inkl. Krankschreibungen) lokal in einen
  eigenen Google-Kalender ("Timeoffice Dienstplanfeed", ein besessener Sekundärkalender im
  Google-Konto, keine URL-Subscription — verifiziert per Sharing-/Zugriffsberechtigungs-Optionen
  in dessen Kalender-Einstellungen). CFAlarm liest von dort. **Live am 30.07.2026 nachgewiesen**:
  TimeOffice selbst war von "App bei Nichtnutzung pausieren" (aktiv) UND Akku-Optimierung
  "Optimiert" betroffen — der Sync blieb ~4 Tage stehen, obwohl TimeOffice die Krankschreibung
  längst intern kannte (🤒-Symbol im eigenen Dienstplan sichtbar). CFAlarms eigene Alarme liefen
  in der Zwischenzeit einwandfrei — das Problem war unsichtbar, bis man gezielt danach sucht.
- **`TimeOfficeHealthHelper`/`TimeOfficeHealthCard`/`TimeOfficeHealthOnboardingScreen`** (seit
  v1.19.0) spiegeln dieses Muster aus `BatteryOptimizationHelper`/`UnusedAppRestrictionsHelper`
  auf die externe Abhängigkeit. **Wichtige Einschränkung, absichtlich so gelassen**: Es gibt
  **keine öffentliche API**, um den "Nicht verwendete Apps"/Hibernation-Status einer ANDEREN App
  abzufragen (`PackageManagerCompat.getUnusedAppRestrictionsStatus()` funktioniert nur für die
  aufrufende App selbst) — nur die Akku-Optimierungs-Ausnahme ist für fremde Pakete prüfbar
  (`PowerManager.isIgnoringBatteryOptimizations(anderesPackage)` akzeptiert jeden Package-Namen,
  siehe `BatteryOptimizationHelper.isExempted(context, packageName)`). Die Karte zeigt deshalb für
  Akku-Optimierung echtes Grün/Rot, für "Nicht verwendete Apps" bewusst **keinen** vorgetäuschten
  Status. Wer hier versucht, einen Hibernation-Status für TimeOffice "irgendwie" zu ermitteln,
  jagt einer API hinterher, die nicht existiert.
- **Kein `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`-Dialog für TimeOffice.** Anders als beim
  eigenen Akku-Onboarding (das einen Ein-Klick-System-Dialog auslöst) führt der Aktions-Button
  hier IMMER auf TimeOffices eigene App-Info-Seite (`ACTION_APPLICATION_DETAILS_SETTINGS`) — nicht
  dokumentiert/getestet, ob Android den Bestätigungsdialog für ein fremdes Package überhaupt
  zulässt. Der App-Info-Weg ist der einzige, der am 30.07.2026 live nachweislich funktioniert hat.
- **`<queries>` in AndroidManifest.xml ist Pflicht** für `TimeOfficeHealthHelper.isInstalled()`
  (Android 11+ Package-Visibility) — ohne die Deklaration liefert `getPackageInfo()` für
  `de.pradtke.timeoffice` immer `NameNotFoundException`, unabhängig davon ob installiert.
- **Kein Unit-Test für `TimeOfficeHealthHelper`** — bewusst, gleiche Konvention wie
  `BatteryOptimizationHelper`/`UnusedAppRestrictionsHelper` (siehe deren fehlende/minimale Tests):
  dünne Android-Wrapper ohne eigene Logik werden hier nicht getestet, nur reine Funktionen wie
  `UnusedAppRestrictionsHelper.needsPrompt()`.
- **Das automatische Onboarding-Gate für den TimeOffice-Health-Prompt hängt an
  `NavigationViewModel.handleAuthenticationSuccess()`, NICHT nur an `setzeGateKetteFort()` (bis 1.45: `proceedPastGates()`).**
  `setzeGateKetteFort()` (bis 1.45: `proceedPastGates()`) (MainScreen) verkettet die Gates nur, wenn der Nutzer die vorherigen Screens
  gerade durchläuft. `handleAuthenticationSuccess()` ist der EINZIGE Pfad, der bei jedem
  App-Vordergrund/Auth-Erfolg automatisch prüft, ob noch ein Gate offen ist — ohne einen eigenen
  Zweig dafür sehen Bestandsnutzer, die Kalender/Akku/Unused-App-Gates schon vor diesem Feature
  durchlaufen hatten, den TimeOffice-Prompt NIE automatisch (nur noch über die permanente
  Status-Tab-Karte). Genau der Fall, für den das Feature gebaut wurde. Fix seit v1.22.0: vierter
  `else if`-Zweig in `handleAuthenticationSuccess()`, gleiche Gate-Reihenfolge wie
  `setzeGateKetteFort()` (bis 1.45: `proceedPastGates()`).

- **TimeOffice trägt eine Änderung erst in den Kalender ein, wenn man es ÖFFNET — auch mit allen
  Einstellungen in Ordnung** (Befund 12.10.2026, Fairphone, CF-Alarm-Debug-Log plus Kalender-Sync
  des Geräts). Meldung „Dienstplanänderung" um 09:24; der Nutzer öffnete TimeOffice um 15:50:28,
  Android lud 15:51:06 hoch, Google hatte den Termin 15:52. Bis zum Öffnen stand nichts im
  Kalender — Akku-Ausnahme und „App bei Nichtnutzung pausieren" waren korrekt gesetzt. Damit gilt:
  so oft CF-Alarm auch nachsieht, vor dem Öffnen von TimeOffice gibt es nichts zu sehen.
  - **Warum das so ist (Einordnung, nicht vom Hersteller bestätigt):** TimeOffice 2.7.2 ist eine
    Capacitor-Web-App; das Schreiben in den Kalender läuft offenbar im Web-Teil, also nur bei
    geöffneter App. Die Meldung kommt per Push aus der Pradtke-Cloud, ohne die App zu starten.
  - **Von außen anstoßen geht nicht:** exportiert ist nur die Startseite (`MainActivity`, dazu
    `https://timeoffice.app`-Links), kein Dienst, kein Receiver. Eine fremde App aus dem
    Hintergrund zu öffnen verbietet Android ab 10. Beim Öffnen fragt TimeOffice die Biometrie ab —
    ein unbemerktes Öffnen gäbe es also auch dann nicht.
  - **Was CF-Alarm tut (1.48.0):** Die Karte „TimeOffice-Zuverlässigkeit" sagt es dem Nutzer und
    bietet „TimeOffice öffnen" an (`TimeOfficeHealthHelper.createLaunchIntent`,
    `getLaunchIntentForPackage`); danach zwei Abgleiche nach 3 und 10 Minuten
    (`TimeOfficeNachfassAbgleich`, Regeln im Wecker-Skill). Die Problemhilfe der Website
    (`troubleshooting.html#timeoffice`) sagt dasselbe. `EinstiegIntentTest` lässt den
    Start-Intent zu genau diesem fremden Paket zu — sein Zweck sind Intents an die EIGENE App.
  - **Der eigentliche Hebel läge beim Hersteller** (Kalender im Hintergrund schreiben, wenn die
    Push-Meldung kommt). Ein Funktionswunsch an Pradtke ist bisher nicht gestellt.

- **Eine offizielle Schnittstelle zum EIGENEN Dienstplan gibt es nicht — der Kalender-Export der
  App ist der einzige Weg nach außen.** Recherchiert am 11.10.2026 (lokale Sitzung mit Browser;
  aus der Cloud-Umgebung sind pradtke.de, help.pradtke.de, gracehelp.pradtke.de und die Stores
  per Proxy-Richtlinie gesperrt): help.pradtke.de vollständig (253 Seiten des Inhaltsverzeichnisses),
  gracehelp.pradtke.de (130 verlinkte Seiten, davon 27 aus der Web-Hilfe 3.1.0), die Produktseiten
  auf pradtke.de, Play Store und App Store. Gesucht nach iCal, ICS, CalDAV, webcal, Abo, Outlook,
  Kalender, Export, Selfservice, Mitarbeiterportal — **kein Treffer** für einen Kalender-Feed.
  - **TIMEOFFICE Core** stellt laut Doku eine REST-API „für andere Softwaremodule innerhalb der
    Kundeninfrastruktur" bereit (`TechDok_IT-Admins/bes_TechnDokuUebersicht.htm`) — ein Werkzeug
    der Klinik-IT für eigene Drittsystem-Anbindungen, Endpunkte nicht öffentlich. **Verworfen als
    Datenquelle für CFAlarm**: Zugriff einer öffentlichen Play-Store-App auf Kliniksysteme hieße
    Zugangsdaten auf dem Handy, Fremddaten (Kollegen, Krankschreibungen = Art. 9 DSGVO),
    Mitbestimmung und je Klinik ein eigenes Genehmigungsprojekt.
  - **Selfservice/Mitarbeiterportal** (Intranet): Stundennachweis und Zeitnachtrag; die
    Konfiguration kennt keine Kalender-/Feed-Einstellung (`anl_TOPersKonfigurieren.htm`). Pradtke
    lenkt seit 10.1.1 vom Portal zur App.
  - **CSG.Schnittstelle / Autoexporte**: Lohn-Stammdaten und eAU, serverseitig, nur in Abstimmung
    mit Pradtke änderbar.
  - **TIMEOFFICE Mobile**: Daten fließen Klinik → Pradtke-Cloud (Azure) → App, Anmeldung per
    QR-Code + PIN (`Datenschutz/bes_DatenschutzSicherheit.htm`). Das Mobile Center hat keinen
    dokumentierten Kalender-Schalter. Play Store (2.7.2, 30.10.2025): Berechtigung Kalender
    lesen/schreiben — das ist der „Timeoffice Dienstplanfeed"-Mechanismus, den CFAlarm nutzt. iOS
    nennt ihn in Release 1.4.1 (Nov. 2022: Dienstplan „im Kalender speichern"); **öffentlich
    dokumentiert ist er nirgends** — eine Nebenfunktion, keine zugesicherte Schnittstelle. Ein
    weiterer Grund für die Health-Karte: ändert Pradtke sie, kündigt das niemand an.
  - **Einziger offener Hebel**: die Klinik-IT nach einem persönlichen ICS-Abo-Link fragen. Er
    müsste nicht das Kliniknetz öffnen (die Pradtke-Cloud existiert schon), ist aber nicht
    Standard. Lücke der Recherche: gracehelp steht auf dem Stand Ende 2024, nur ein Teil der
    Web-Hilfe ist frei verlinkt — endgültig verneinen kann es nur Klinik-IT oder Pradtke-Support.
