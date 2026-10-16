# runner.ps1 - arbeitet die Pakete des Umbaus "Schichttyp als Zentrale" unbeaufsichtigt ab.
#
# Ablauf und Regeln: entwurf/umbau-schichttyp/ABLAUF.md (Abschnitte 6-8). Kurz:
# - Der Zustand liegt in GitHub (Labels), nicht hier. Start und Wiederaufnahme sind derselbe Befehl.
# - Ein Paket = ein frischer claude -p Lauf in einem eigenen Worktree, hoechstens 2 Laeufe.
# - Etappen-Abschluss nur, wenn kein Paket der Etappe mehr offen ist.
# - Nur emulator-5554. Das Fairphone wird nie angesprochen.
#
# Aufruf:  powershell -ExecutionPolicy Bypass -File tools\umbau\runner.ps1 [-DryRun]
#   -DryRun  zeigt nur, was als Naechstes passieren wuerde, und beendet sich (kein Agent, keine Labels).
# Notbremse: Label "umbau-pause" am Epic oder Datei ..Projektdateien\umbau\STOP.
#
# Bewusst nur ASCII: Windows PowerShell 5.1 liest UTF-8 ohne BOM falsch.

param([switch]$DryRun)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
# 'Continue': unter PS 5.1 wird jede stderr-Zeile eines externen Programms (git, gh, adb) bei
# 'Stop' zur Ausnahme. Fehler werden stattdessen ueber `$LASTEXITCODE geprueft.
$ErrorActionPreference = 'Continue'

$Root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$Proj = Join-Path $Root '..Projektdateien\umbau'
$Logs = Join-Path $Proj 'logs'
$Status = Join-Path $Logs 'STATUS.txt'
$Lock = Join-Path $Proj 'runner.lock'
$StopFile = Join-Path $Proj 'STOP'
$WtBase = Join-Path (Split-Path $Root -Parent) 'CFAlarm-umbau-wt'
$Integration = 'feature/umbau-schichttyp'
$Emu = 'emulator-5554'
$Avd = 'Medium_Phone'
$Sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$Adb = Join-Path $Sdk 'platform-tools\adb.exe'
$Caps = @{ 'groesse-S' = 40; 'groesse-M' = 60 }
$SkillEtappen = @(3, 9)   # brauchen vorher Label skills-bereit-N am Epic (ABLAUF Abschnitt 3)

New-Item -ItemType Directory -Force $Logs | Out-Null

function Log([string]$text) {
    $zeile = "$(Get-Date -Format s)  $text"
    Write-Host $zeile
    if (-not $DryRun) { Add-Content -Path $Status -Value $zeile -Encoding UTF8 }
}

function GhAufruf([string[]]$a) {
    $out = & gh @a 2>&1
    if ($LASTEXITCODE -ne 0) { throw "gh $($a -join ' '): $out" }
    return ($out | Out-String)
}

function Issues() {
    $json = GhAufruf @('issue', 'list', '--repo', 'F1rlefanz/CF-Alarm-for-TimeOffice', '--label', 'umbau',
                 '--state', 'open', '--limit', '300', '--json', 'number,title,labels,body')
    $liste = $json | ConvertFrom-Json
    foreach ($i in $liste) { $i | Add-Member -NotePropertyName L -NotePropertyValue @($i.labels | ForEach-Object { $_.name }) }
    return $liste
}

function IstZu([int]$nr) {
    $s = (GhAufruf @('issue', 'view', "$nr", '--repo', 'F1rlefanz/CF-Alarm-for-TimeOffice', '--json', 'state', '--jq', '.state')).Trim()
    return $s -eq 'CLOSED'
}

function Etappe($issue) {
    foreach ($l in $issue.L) { if ($l -match '^etappe-(\d+)$') { return [int]$Matches[1] } }
    return -1
}

function Blocker($issue) {
    if ($issue.body -match 'Blockiert von:\s*([^\r\n]*)') {
        return @([regex]::Matches($Matches[1], '#(\d+)') | ForEach-Object { [int]$_.Groups[1].Value })
    }
    return @()
}

function Label([int]$nr, [string[]]$add, [string[]]$remove) {
    if ($DryRun) { Log "DRYRUN: #$nr +[$($add -join ',')] -[$($remove -join ',')]"; return }
    $a = @('issue', 'edit', "$nr", '--repo', 'F1rlefanz/CF-Alarm-for-TimeOffice')
    foreach ($x in $add) { $a += @('--add-label', $x) }
    foreach ($x in $remove) { $a += @('--remove-label', $x) }
    GhAufruf $a | Out-Null
}

function Kommentar([int]$nr, [string]$text) {
    if ($DryRun) { Log "DRYRUN: Kommentar an #$nr"; return }
    $f = Join-Path $Logs "kommentar-$nr.md"
    Set-Content -Path $f -Value $text -Encoding UTF8
    GhAufruf @('issue', 'comment', "$nr", '--repo', 'F1rlefanz/CF-Alarm-for-TimeOffice', '--body-file', $f) | Out-Null
}

function FremdesGeraet() {
    $zeilen = & $Adb devices | Select-Object -Skip 1 | Where-Object { $_ -match '\tdevice$' }
    return @($zeilen | Where-Object { $_ -notmatch '^emulator-' }).Count -gt 0
}

function EmulatorBereit() {
    $da = (& $Adb devices) -match "^$Emu\s+device"
    if ($da) { return }
    if ($DryRun) { Log 'DRYRUN: Emulator wuerde gestartet'; return }
    Log 'Emulator laeuft nicht - starte ihn'
    Start-Process -FilePath (Join-Path $Sdk 'emulator\emulator.exe') -ArgumentList @('-avd', $Avd, '-timezone', 'Europe/Berlin', '-no-snapshot-save') -WindowStyle Minimized
    & $Adb -s $Emu wait-for-device
    for ($i = 0; $i -lt 60; $i++) {
        if ((& $Adb -s $Emu shell getprop sys.boot_completed).Trim() -eq '1') { break }
        Start-Sleep -Seconds 5
    }
    # Test-PIN 1234 (Emulator braucht eine Sperre fuer Direct-Boot-Tests, Memory env_emulator_needs_pin_for_directboot)
    & $Adb -s $Emu shell input keyevent 82 | Out-Null
    & $Adb -s $Emu shell input text 1234 | Out-Null
    & $Adb -s $Emu shell input keyevent 66 | Out-Null
}

function Worktree([string]$name, [string]$branch, [string]$basis) {
    $pfad = Join-Path $WtBase $name
    if (-not (Test-Path $pfad)) {
        git -C $Root fetch -q origin
        $remote = git -C $Root ls-remote --heads origin $branch
        if ($remote) { git -C $Root worktree add -q -B $branch $pfad "origin/$branch" }
        else { git -C $Root worktree add -q -b $branch $pfad $basis }
        if ($LASTEXITCODE -ne 0) { throw "worktree $name" }
    }
    foreach ($f in @('keystore.properties', 'local.properties')) {
        Copy-Item (Join-Path $Root $f) (Join-Path $pfad $f) -Force
    }
    return $pfad
}

function LimitWarten([string]$text) {
    # Meldung z. B. "You've hit your session limit ... resets 4pm (Europe/Berlin)". Ohne lesbare Zeit: 30 min.
    # Nur ein FEHLER-Ergebnis zaehlt: ein Agent, der in seiner Antwort ueber Limits schreibt, ist keins.
    try { $j = $text | ConvertFrom-Json; if (-not $j.is_error) { return $false }; $text = "$($j.result) $($j.subtype)" } catch {}
    if ($text -notmatch '(?i)(hit your|usage limit|session limit|weekly limit|rate limit)') { return $false }
    $warte = 30
    if ($text -match '(?i)resets?\s+(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?') {
        $h = [int]$Matches[1]; $m = if ($Matches[2]) { [int]$Matches[2] } else { 0 }
        if ($Matches[3] -eq 'pm' -and $h -lt 12) { $h += 12 }
        if ($Matches[3] -eq 'am' -and $h -eq 12) { $h = 0 }
        $ziel = (Get-Date).Date.AddHours($h).AddMinutes($m + 2)
        if ($ziel -lt (Get-Date)) { $ziel = $ziel.AddDays(1) }
        $warte = [math]::Ceiling(($ziel - (Get-Date)).TotalMinutes)
    }
    Log "Limit erreicht - warte $warte Minuten"
    if (-not $DryRun) { Start-Sleep -Seconds ($warte * 60) }
    return $true
}

function StarteClaude([string]$goal, [string]$model, [int]$cap, [string]$cwd, [string]$logName) {
    $env:ANDROID_SERIAL = $Emu
    $log = Join-Path $Logs "$logName.json"
    $a = @('-p', $goal, '--model', $model, '--effort', 'xhigh', '--max-turns', "$($cap + 5)",
           '--strict-mcp-config', '--output-format', 'json',
           '--disallowedTools', 'PowerShell', 'mcp__desktop-commander__start_process',
           'mcp__desktop-commander__interact_with_process', 'mcp__Windows-MCP__Powershell-Tool')
    Push-Location $cwd
    try { $out = & claude @a 2>&1 | Out-String } finally { Pop-Location }
    Set-Content -Path $log -Value $out -Encoding UTF8
    return $out
}

function PaketZiel($issue, [int]$lauf, [int]$cap, [string]$branch) {
    $start = if ($lauf -gt 1) { "Teilarbeit liegt vor (Branch $branch, git log, Kommentare im Issue): pruefe sie und setze fort." } else { 'Setze das Paket um.' }
    return @"
/goal Paket #$($issue.number) des Umbaus "Schichttyp als Zentrale": $($issue.title). $start
Lies zuerst entwurf/umbau-schichttyp/ABLAUF.md, SPEC.md, das Issue (gh issue view $($issue.number)) und den dort genannten Skill.
Fertig erst, wenn: (1) jede Abnahme-Zeile des Issues erfuellt ist und ihre Ausgabe im Transcript steht,
(2) ein Pruefer-Subagent mit dem jeweils ANDEREN Modell (Opus wenn du Sonnet bist, sonst Sonnet), der nur git diff, Issue, SPEC und Prototyp sieht, keine offenen belegten Befunde hat,
(3) der Branch $branch in $Integration gemergt und gepusht ist (im Worktree $WtBase\integration, Schleuse gruen),
(4) das Issue mit einem Abschlusskommentar (was, Belege, Entscheidungen) geschlossen ist.
Nach jedem gruenen Schritt committen und $branch pushen. Fasse nur die Dateien aus dem Feld Dateien an.
Funde ausserhalb des Umfangs: eigenes Issue mit Labels umbau und spaeter; Teilung des Pakets hoechstens 3 neue Issues.
Unbelegte Fehlerhypothesen nie in ein Issue, sondern nach ..Projektdateien/umbau/hypothesen.md.
Lehren fuer Skills nach entwurf/umbau-schichttyp/skill-nachtraege.md (.claude/** ist schreibgeschuetzt).
Konsultiere den Advisor genau an drei Stellen: vor dem Plan, beim zweiten gleichen Fehler, vor der Fertigmeldung.
Niemand ist anwesend: keine Rueckfragen, begruendete Entscheidungen im Issue dokumentieren.
Verboten: Force-Push, History umschreiben, main anfassen, versionCode aendern, CFALARM_SCHLEUSE, Kerntests ohne benannte Verhaltensaenderung aendern, ein anderes Geraet als emulator-5554.
Stoppe nach $cap Turns und schreibe den Stand als Kommentar ins Issue.
"@
}

function EtappeZiel([int]$n, $etIssue, [string]$art) {
    if ($art -eq 'schneiden') {
        return @"
/goal Du bist der Betreuer des Umbaus "Schichttyp als Zentrale". Schneide Etappe $n (Issue #$($etIssue.number)) in Pakete nach entwurf/umbau-schichttyp/ABLAUF.md Abschnitt 4.
Lies ABLAUF.md, SPEC.md und den heutigen Code der betroffenen Bereiche. Lege je Paket ein Issue mit Labels umbau, paket, etappe-$n, bereit, groesse-S oder groesse-M und bei Wecker/Persistenz/Migration/Erkennung risiko-hoch an. Hoechstens 15 Pakete.
Fertig, wenn alle Pakete angelegt sind, ihre Dateilisten und Blockiert-von stimmen und die Checkliste in #$($etIssue.number) sie auflistet. Konsultiere den Advisor vor dem Anlegen. Setze nichts selbst um. Keine Rueckfragen. Stoppe nach 40 Turns.
"@
    }
    return @"
/goal Du bist der Betreuer des Umbaus "Schichttyp als Zentrale". Schliesse Etappe $n (Issue #$($etIssue.number)) nach entwurf/umbau-schichttyp/ABLAUF.md Abschnitt 7 ab. Arbeite im Worktree $WtBase\integration bzw. $WtBase\main.
Use a workflow fuer die Gegenpruefung (Schritt 2): Dimensionen nach Risiko, Fable, 3er-Widerlegung, bestaetigte Befunde beheben.
Fertig erst, wenn jede Pruefung aus Schritt 3 mit Beleg im Transcript steht, main gemergt, Bump (Minor; Etappe 0 nur Patch, sie enthaelt nur Fehlerbehebungen) und Changelog gepusht sind (Schleuse gruen), #$($etIssue.number) geschlossen und ein Bericht im Epic steht.
Ist eine Pruefung rot und nicht behebbar: nichts nach main, Bericht in #$($etIssue.number), Label blockiert. Konsultiere den Advisor vor dem Merge nach main. Keine Rueckfragen. Stoppe nach 80 Turns.
"@
}

# ---------------------------------------------------------------- Start
if (Test-Path $Lock) {
    $alt = Get-Content $Lock -ErrorAction SilentlyContinue
    if ($alt -and (Get-Process -Id ([int]$alt) -ErrorAction SilentlyContinue)) { Write-Host "Runner laeuft bereits (PID $alt)."; exit 0 }
}
if (-not $DryRun) { Set-Content -Path $Lock -Value $PID }

try {
    Log "Runner gestartet$(if ($DryRun) { ' (DryRun)' })"
    while ($true) {
        if (Test-Path $StopFile) { Log 'STOP-Datei gefunden - Ende'; break }
        $alle = Issues
        $epic = $alle | Where-Object { $_.L -contains 'epic' } | Select-Object -First 1
        if (-not $epic) { Log 'Kein offenes Epic (Label epic) - Ende'; break }
        if ($epic.L -contains 'umbau-pause') { Log 'Label umbau-pause am Epic - Ende'; break }

        $etappen = @($alle | Where-Object { $_.L -contains 'etappe' } | Sort-Object { Etappe $_ })
        if ($etappen.Count -eq 0) { Log 'Keine Etappe mehr offen - Umbau fertig'; Kommentar $epic.number 'Runner: alle Etappen geschlossen.'; break }
        $et = $etappen[0]; $n = Etappe $et
        if ($SkillEtappen -contains $n -and -not ($epic.L -contains "skills-bereit-$n")) {
            Log "Etappe $n wartet auf Label skills-bereit-$n am Epic (Eigentuemer-Schritt) - Ende"; break
        }
        $pakete = @($alle | Where-Object { ($_.L -contains 'paket') -and ((Etappe $_) -eq $n) })
        if (@($pakete | Where-Object { $_.L -contains 'blockiert' }).Count -ge 2) {
            Log "Etappe $n hat zwei blockierte Pakete - die Spec ist das Problem. Ende"
            Kommentar $epic.number "Runner gestoppt: in Etappe $n sind zwei Pakete blockiert. Bitte ansehen."
            break
        }

        # Absturzreste zuerst, dann freie Pakete
        $kandidat = $pakete | Where-Object { $_.L -contains 'in-arbeit' } | Sort-Object number | Select-Object -First 1
        if (-not $kandidat) {
            $kandidat = $pakete | Where-Object { ($_.L -contains 'bereit') -and -not ($_.L -contains 'blockiert') } |
                Where-Object { $frei = $true; foreach ($b in (Blocker $_)) { if (-not (IstZu $b)) { $frei = $false } }; $frei } |
                Sort-Object number | Select-Object -First 1
        }

        if (-not $kandidat) {
            if ($pakete.Count -eq 0 -and -not ($et.L -contains 'geschnitten')) {
                Log "Etappe ${n}: noch keine Pakete - Betreuer schneidet"
                if ($DryRun) { break }
                $out = StarteClaude (EtappeZiel $n $et 'schneiden') 'claude-opus-5-5' 40 $Root "etappe-$n-schneiden"
                if (LimitWarten $out) { continue }
                Label $et.number @('geschnitten') @()
                continue
            }
            if ($pakete.Count -gt 0) {
                Log "Etappe ${n}: $($pakete.Count) Pakete offen, aber keins waehlbar - Ende"
                Kommentar $epic.number "Runner gestoppt: Etappe $n hat offene Pakete ($(($pakete | ForEach-Object { '#' + $_.number }) -join ', ')), aber keins ist waehlbar."
                break
            }
            $versuch = if ($et.L -contains 'abschluss-1') { 2 } else { 1 }
            if ($et.L -contains 'abschluss-2') { Log "Etappe ${n}: Abschluss zweimal gescheitert - Ende"; break }
            if (FremdesGeraet) { Log 'Fremdes Geraet am Kabel - Abschluss wartet 10 min'; if ($DryRun) { break }; Start-Sleep 600; continue }
            Log "Etappe ${n}: Abschluss, Versuch $versuch"
            if ($DryRun) { break }
            EmulatorBereit
            Worktree 'integration' $Integration 'origin/main' | Out-Null
            Worktree 'main' 'main' 'origin/main' | Out-Null
            Label $et.number @("abschluss-$versuch") @()
            $out = StarteClaude (EtappeZiel $n $et 'abschluss') 'claude-opus-5-5' 80 $Root "etappe-$n-abschluss-$versuch"
            if (LimitWarten $out) { Label $et.number @() @("abschluss-$versuch"); continue }
            continue
        }

        $nr = $kandidat.number
        if (-not ($kandidat.L -contains 'ohne-geraet') -and (FremdesGeraet)) {
            Log "#${nr}: fremdes Geraet am Kabel - warte 10 min"; if ($DryRun) { break }; Start-Sleep 600; continue
        }
        $lauf = if ($kandidat.L -contains 'lauf-2') { 2 } elseif ($kandidat.L -contains 'lauf-1') { if ($kandidat.L -contains 'in-arbeit') { 1 } else { 2 } } else { 1 }
        $cap = 60; foreach ($k in $Caps.Keys) { if ($kandidat.L -contains $k) { $cap = $Caps[$k] } }
        $model = if ($kandidat.L -contains 'risiko-hoch') { 'claude-opus-5-5' } else { 'claude-sonnet-5-5' }
        $slug = ($kandidat.title.ToLower() -replace '[^a-z0-9]+', '-').Trim('-'); if ($slug.Length -gt 30) { $slug = $slug.Substring(0, 30).Trim('-') }
        $branch = "feature/umbau-$nr-$slug"
        Log "#${nr} Lauf $lauf ($model, Cap $cap): $($kandidat.title)"
        if ($DryRun) { break }

        EmulatorBereit
        Worktree 'integration' $Integration 'origin/main' | Out-Null
        $wt = Worktree "$nr" $branch "origin/$Integration"
        Label $nr @('in-arbeit', "lauf-$lauf") @('bereit')
        Push-Location $wt; try { & .\gradlew.bat testDebugUnitTest lintDebug -q *> (Join-Path $Logs "vorwaermen-$nr.log") } catch {} finally { Pop-Location }

        $out = StarteClaude (PaketZiel $kandidat $lauf $cap $branch) $model $cap $wt "paket-$nr-lauf$lauf"
        if (LimitWarten $out) { continue }

        if (IstZu $nr) {
            Log "#${nr}: fertig"
            git -C $Root worktree remove --force $wt 2>$null
            continue
        }
        $commits = (git -C $wt log --oneline -5 2>$null | Out-String)
        $ende = ($out -split "`n" | Select-Object -Last 15) -join "`n"
        Kommentar $nr "Runner: Lauf $lauf endete ohne Abschluss.`n`nLetzte Commits auf ${branch}:`n``````n$commits``````n`nEnde des Logs:`n``````n$ende`n``````"
        if ($lauf -ge 2) {
            Label $nr @('blockiert') @('in-arbeit')
            Log "#${nr}: nach 2 Laeufen blockiert"
        } else {
            Label $nr @('bereit') @('in-arbeit')
            Log "#${nr}: Lauf 1 ohne Abschluss - zweiter Lauf folgt"
        }
    }
} finally {
    if (-not $DryRun -and (Test-Path $Lock) -and ((Get-Content $Lock) -eq "$PID")) { Remove-Item $Lock -Force }
    Log 'Runner beendet'
}
