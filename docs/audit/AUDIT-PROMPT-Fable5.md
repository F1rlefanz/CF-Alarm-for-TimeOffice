# Release-Audit „CF Alarm“ — Senior Lead Briefing

**Ziel:** Eine vollständige, schonungslose Release-Reife-Prüfung der App `CFAlarmforTimeOffice` vor dem öffentlichen Test. 

Du agierst ab sofort als **technischer Projektleiter und Senior-Android-Engineer**. Deine Aufgabe ist es, diese Codebase ganzheitlich zu durchdringen, Schwachstellen zu identifizieren und die App auf ein professionelles Release-Niveau zu heben. Du hast die volle strategische und operative Freiheit, wie du dieses Audit durchführst.

## 1. Deine Verantwortungsbereiche

Bitte analysiere, bewerte und erarbeite konkrete Verbesserungen für folgende Aspekte:

* **UI & UX:** Die App muss modern, intuitiv und übersichtlich sein. Prüfe das Design-System, die Nutzerführung und das visuelle Feedback (Ladezustände, Fehler, Bestätigungen). Wo können wir ansetzen, um das Erlebnis massiv aufzuwerten?
* **Onboarding & Automatisierung:** Der aktuelle Berechtigungs-Parcours ist lang. Finde Wege, um Prozesse zu automatisieren, klüger zusammenzufassen oder dem Nutzer Entscheidungen im Hintergrund abzunehmen.
* **Kompromisslose Stabilität & Nebenläufigkeit:** Dies ist eine Wecker-App für Schichtarbeiter. Ein Ausfall ist absolut inakzeptabel, gerade wenn man sich für den Frühdienst auf der Intensivstation darauf verlassen muss. Prüfe Doze-Mode, OEM-Killer, WakeLocks und das generelle Alarm-Scheduling auf Herz und Nieren.
* **Architektur & Code-Gesundheit:** Identifiziere Tech-Debt, God-Objects und unsaubere Schichtentrennung.
* **Sicherheit & Datenschutz:** Analysiere die Token-Speicherung (Tink), OAuth-Flows und Berechtigungen.
* **Infrastruktur & Release:** Bewerte die aktuelle Testabdeckung, konzipiere eine sinnvolle GitHub-CI-Pipeline, prüfe die Play Console Vorbedingungen und gib eine fundierte Empfehlung zum Thema Firebase/Crashlytics (unter Berücksichtigung des Datenschutzes).

## 2. Strategische Entscheidung: Refactoring vs. Rewrite

Der Code ist gewachsen. Tritt einen Schritt zurück und bewerte das große Ganze: Lohnt sich ein inkrementelles Refactoring (Gezieltes Aufräumen und Splitten großer Dateien) oder rätst du zu einem kompletten oder partiellen Rewrite ("Rebasing"), um fundamentale Altlasten loszuwerden? 
*Liefere mir dazu eine klare, fundierte Entscheidungsvorlage mit Pro/Contra-Abwägung.*

## 3. Arbeitsweise & Autonomie (Deine Leitplanken)

Du bist intelligent genug, um deine Werkzeuge effizient zu nutzen. Daher gelten nur diese wenigen, aber absoluten Grundregeln:

1.  **CLAUDE.md beachten:** Lies die `CLAUDE.md`. Die dortigen Constraints (z.B. Blockierung von `AD_ID`, Beibehaltung der DataStore-Trennung) sind dein Gesetz.
2.  **Ressourcen- und Zeitmanagement:** Du steuerst deine Subagenten/Worker selbst. Vermeide zwingend endlos blockierende Prozesse (wie interaktive Shells oder laufende adb-Streams). Setze dir eigene Timeouts für Tool-Aufrufe. Wenn ein Worker hängt, kille ihn und wechsle die Strategie.
3.  **Erst Audit, dann Code:** Phase 1 ist rein diagnostisch (read-only). Verschaffe dir einen Überblick und erstatte Bericht. **Ändere keinen Code, erstelle keine Commits und pushe nichts**, bevor ich deinen Audit-Bericht abgenickt habe.

**Dein erster Schritt:** Lies dich in das Projekt ein, strukturiere deine Vorgehensweise und gib mir einen kurzen Ping, wie dein Schlachtplan aussieht und welche Agenten du wie einsetzen willst. Starte das eigentliche Audit erst nach meinem "Go".