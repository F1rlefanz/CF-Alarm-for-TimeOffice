#!/usr/bin/env bash
#
# Rauchtest: startet die App auf einem Emulator und sieht nach, ob sie oben bleibt.
#
# WARUM ES DAS GIBT
# Das ist der einzige Schritt in diesem Repo, der die App AUSFUEHRT. `ci.yml` baut, testet und
# lintet - startet sie aber nie. Genau dieser blinde Fleck hat am 05.08.2026 zugeschlagen: ein
# Absturz beim Start (Property nach `init{}`) kam an gruenen Unit-Tests und einem gruenen Build
# vorbei bis in eine Version. Solange ein Mensch von Hand veroeffentlicht, faengt er so etwas beim
# Installieren. Der Sammel-Release (`.github/workflows/sammel-release.yml`) veroeffentlicht
# unbeaufsichtigt - dort ist dieses Skript die einzige Instanz, die es noch faengt.
#
# WARUM ALS DATEI UND NICHT INLINE IM WORKFLOW
# `reactivecircus/android-emulator-runner` fuehrt sein `script:` ZEILENWEISE aus - jede Zeile in
# einer eigenen Shell. Variablen ueberleben das nicht, und ein mehrzeiliges `if` zerfaellt in
# Bruchstuecke. Am 02.09.2026 zweimal gemessen: erst starb `set -o pipefail` an dash (Lauf
# 33609480271), dann kam `Activity class {/.MainActivity} does not exist` - `$PAKET` war leer -
# gefolgt von `Syntax error: end of file unexpected (expecting "fi")` (Lauf 33609854635). Als
# Datei aufgerufen laeuft das Skript am Stueck und unter bash.
set -euo pipefail

PAKET=com.github.f1rlefanz.cf_alarmfortimeoffice
APK=app/build/outputs/apk/debug/app-debug.apk
# Wie lange die App nach dem Start Zeit bekommt. Der Absturz von 2026 passierte beim Aufbau des
# ViewModels, also unmittelbar nach dem Start - 15 s sind reichlich und kosten nichts.
WARTEN=${RAUCHTEST_WARTEN:-15}

echo "--- installieren ---"
test -f "$APK" || { echo "::error::$APK fehlt - der Build hat keine APK erzeugt."; exit 1; }
adb install -r "$APK"

adb logcat -c
echo "--- starten ---"
adb shell am start -W -n "$PAKET/.MainActivity"
sleep "$WARTEN"

echo "--- laeuft der Prozess noch? ---"
PID=$(adb shell pidof "$PAKET" | tr -d '\r' || true)
if [ -z "$PID" ]; then
  echo "::error::Die App laeuft nach dem Start nicht mehr - Absturz beim Start."
  adb logcat -d -b crash,main | tail -100
  exit 1
fi
echo "PID $PID"

echo "--- steht ein Absturz im Log? ---"
# Der crash-Puffer allein reicht nicht: ein Prozess, der sich nach dem Absturz sofort neu startet,
# sieht bei der Pruefung oben lebendig aus. Deshalb zusaetzlich der Hauptpuffer.
if adb logcat -d -b crash,main | grep -E "FATAL EXCEPTION|AndroidRuntime: Process: $PAKET"; then
  echo "::error::Absturz im Log, obwohl ein Prozess laeuft - Neustart nach Crash."
  exit 1
fi

echo "--- ist unsere Activity im Vordergrund? ---"
# Ohne diese Pruefung genuegte ein Prozess, der lebt, aber nichts anzeigt - etwa wenn der
# Compose-Baum nicht hochkommt und das System zum Startbildschirm zurueckfaellt.
if ! adb shell dumpsys activity activities | grep -q "$PAKET/.MainActivity"; then
  echo "::error::MainActivity ist nicht im Vordergrund - die App ist nicht hochgekommen."
  adb logcat -d -b main | tail -100
  exit 1
fi

echo "Rauchtest bestanden: die App startet, laeuft weiter und steht im Vordergrund."
