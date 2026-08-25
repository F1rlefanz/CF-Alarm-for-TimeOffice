@echo off
setlocal enabledelayedexpansion

rem ===============================================================================================
rem  Eine Aufraeum-Runde, unbeaufsichtigt.
rem
rem  WAS DAS HIER IST: Der Ausloeser fuer genau EINE Runde aus der Warteschlange
rem  (GitHub-Issues mit Label `aufraeumen`). Die Arbeit selbst steckt nicht hier, sondern im Skill
rem  `cfalarm-altlasten-abtragen` - deshalb ist dieses Skript kurz und muss nie angefasst werden,
rem  wenn sich das Verfahren aendert. Es ist der Wecker, nicht der Handwerker.
rem
rem  WARUM DER NAME: "naechste_runde" hiess es zuerst - ein schlechter Name, denn JEDER Aufruf ist
rem  die naechste Runde. Der Name sagt jetzt das Verfahren, nicht den Zeitpunkt.
rem
rem  AUFRUF von Hand:      tools\aufraeumen\rundenweise_aufraeumen.cmd
rem  NUR PRUEFEN:          tools\aufraeumen\rundenweise_aufraeumen.cmd pruefen
rem                        Laesst alle Vorbedingungen laufen und haelt VOR der Runde an. So laesst
rem                        sich der Aufbau testen, ohne eine echte Sitzung zu starten - und ohne
rem                        sie zu bezahlen. Ein Ausloeser, den man nie gefahrlos ausprobieren
rem                        kann, wird nie ausprobiert.
rem  AUFRUF geplant:       dieselbe Zeile als Aktion in der Windows-Aufgabenplanung, Start in
rem                        (Arbeitsverzeichnis) = Projektwurzel. Bewusst noch NICHT eingerichtet.
rem
rem  KOSTEN: laeuft ueber die angemeldete Claude-Sitzung dieses Rechners, nicht ueber einen
rem  API-Schluessel. Der Rechner muss also laufen. Die Alternative waere GitHub Actions - die
rem  laeuft auch bei ausgeschaltetem PC, braucht aber ANTHROPIC_API_KEY und kostet extra.
rem ===============================================================================================

cd /d "%~dp0..\.."
if errorlevel 1 (
    echo [FEHLER] Projektwurzel nicht gefunden.
    exit /b 2
)

rem --- Die Vorbedingungen. Jede einzelne hat einen Grund. ------------------------------------

rem  1. SAUBERER ARBEITSBAUM. An diesem Repo arbeiten mehrere Sitzungen parallel (lokal und in
rem     der Cloud). Liefe die Runde ueber fremde, nicht committete Aenderungen, zoege sie diese
rem     in ihren Pull Request - und der Eigentuemer bekaeme einen Diff, den niemand geschrieben
rem     hat. Lieber gar nicht laufen.
for /f "delims=" %%i in ('git status --porcelain') do (
    echo [ABBRUCH] Der Arbeitsbaum ist nicht sauber - hier arbeitet gerade jemand.
    exit /b 0
)

rem  2. AUF main STARTEN. Steht ein Branch offen, ist eine Arbeit im Gang; eine zweite daneben
rem     zu legen erzeugt nur Konflikte.
for /f "delims=" %%b in ('git rev-parse --abbrev-ref HEAD') do set BRANCH=%%b
if not "%BRANCH%"=="main" (
    echo [ABBRUCH] Nicht auf main, sondern auf %BRANCH% - da laeuft schon etwas.
    exit /b 0
)

rem  3. AKTUELLER STAND. Sonst arbeitet die Runde gegen einen veralteten Baum und meldet
rem     Altlasten, die eine andere Sitzung laengst entfernt hat.
git fetch --quiet
for /f "delims=" %%c in ('git rev-list --count HEAD..origin/main') do set HINTEN=%%c
if not "%HINTEN%"=="0" (
    echo [ABBRUCH] %HINTEN% Commit^(s^) hinter origin/main - erst mergen.
    exit /b 0
)

rem  4. GIBT ES UEBERHAUPT ARBEIT? Eine leere Warteschlange ist das ZIEL, kein Fehler. Ohne diese
rem     Pruefung startet jede Nacht eine Sitzung, die nichts zu tun hat - und faengt womoegens an,
rem     sich selbst Aufgaben auszudenken. Genau das soll sie nicht.
rem     `gh` zaehlt selbst. Die naheliegende Fassung mit `^| find /c /v ""` blieb innerhalb von
rem     `for /f` haengen - eine Pipe in einer Pipe, bei der `find` auf eine Eingabe wartet, die
rem     nie kommt. Eine geplante Aufgabe, die stillsteht statt abzubrechen, ist das Schlimmste:
rem     sie meldet nie einen Fehler und tut trotzdem nichts.
for /f "delims=" %%n in ('gh issue list --label aufraeumen --state open --json number --jq "length"') do set OFFEN=%%n
if "%OFFEN%"=="0" (
    echo [FERTIG] Die Aufraeum-Warteschlange ist leer. Nichts zu tun.
    exit /b 0
)
echo [START] %OFFEN% offene(r) Blickwinkel in der Warteschlange.

if /i "%~1"=="pruefen" (
    echo [PRUEFUNG] Alle Vorbedingungen erfuellt. Es wuerde jetzt eine Runde starten.
    exit /b 0
)

rem --- Protokoll in den gitignorierten Ordner, nicht ins Repo ---------------------------------
set LOGDIR=..Projektdateien\aufraeum-protokolle
if not exist "%LOGDIR%" mkdir "%LOGDIR%"
rem  Zeitstempel ueber PowerShell, NICHT ueber wmic: wmic ist unter Windows 11 abgekuendigt und
rem  verschwindet mit irgendeinem Update - dann waere der Dateiname leer und jede Runde
rem  ueberschriebe das Protokoll der vorigen.
for /f "delims=" %%t in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set STAMP=%%t
set LOG=%LOGDIR%\%STAMP%.log

rem --- Die Runde -----------------------------------------------------------------------------
rem
rem  `dontAsk`, weil eine unbeaufsichtigte Sitzung keine Rueckfrage beantworten kann - sie bliebe
rem  sonst bis zum Zeitlimit stehen.
rem
rem  `git merge` ist VERBOTEN, und das ist die eigentliche Sicherung: Die Runde soll einen Pull
rem  Request oeffnen, nicht selbst auf main landen. Der Grund steht im Skill - das Gatter faengt
rem  Reste, aber kein Zuviel-Wegschneiden; genau diese Fehlerklasse sieht nur ein Mensch im Diff.
rem
rem  Der Auftrag ist absichtlich EIN Satz: Was zu tun ist, steht im Skill, und der wird ueber
rem  seine Beschreibung von allein geladen. Stuende das Verfahren hier, gaebe es zwei Wahrheiten.

echo [LAUF ] Protokoll: %LOG%
claude -p "Raeum weiter auf: arbeite genau EINEN Blickwinkel aus der Warteschlange ab und oeffne einen Pull Request. Du laeufst unbeaufsichtigt - im Zweifel nichts entfernen und den Zweifel ins Issue schreiben." ^
    --permission-mode dontAsk ^
    --disallowedTools "Bash(git merge*)" > "%LOG%" 2>&1

set ERG=%errorlevel%
if not "%ERG%"=="0" (
    echo [FEHLER] Die Runde endete mit Code %ERG%. Siehe %LOG%.
    exit /b %ERG%
)

echo [ENDE ] Runde beendet. Offene Pull Requests:
gh pr list --limit 5
exit /b 0
