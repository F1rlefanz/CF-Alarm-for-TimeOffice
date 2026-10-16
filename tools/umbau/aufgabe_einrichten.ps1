# aufgabe_einrichten.ps1 - legt die Windows-Aufgabe an, die den Runner stuendlich startet, falls er
# nicht laeuft (die Sperrdatei verhindert einen zweiten). Einmalig vom Eigentuemer auszufuehren:
#
#   powershell -ExecutionPolicy Bypass -File tools\umbau\aufgabe_einrichten.ps1
#   powershell -ExecutionPolicy Bypass -File tools\umbau\aufgabe_einrichten.ps1 -Entfernen
#
# Die Aufgabe laeuft nur, wenn du angemeldet bist (Emulator und Claude brauchen deine Sitzung).
# Bewusst nur ASCII (Windows PowerShell 5.1).

param([switch]$Entfernen)

$Name = 'CFAlarm Umbau-Runner'
if ($Entfernen) {
    Unregister-ScheduledTask -TaskName $Name -Confirm:$false
    Write-Host "Aufgabe '$Name' entfernt."
    exit 0
}

$runner = (Resolve-Path (Join-Path $PSScriptRoot 'runner.ps1')).Path
$aktion = New-ScheduledTaskAction -Execute 'powershell.exe' `
    -Argument "-NoProfile -ExecutionPolicy Bypass -WindowStyle Minimized -File `"$runner`""
$ausloeser = New-ScheduledTaskTrigger -Once -At (Get-Date).AddMinutes(1) `
    -RepetitionInterval (New-TimeSpan -Hours 1)
$einstellungen = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Days 14)
Register-ScheduledTask -TaskName $Name -Action $aktion -Trigger $ausloeser -Settings $einstellungen `
    -Description 'Startet tools/umbau/runner.ps1, falls er nicht laeuft. Notbremse: Label umbau-pause am Epic.' -Force | Out-Null
Write-Host "Aufgabe '$Name' angelegt: stuendlich, erster Start in 1 Minute."
