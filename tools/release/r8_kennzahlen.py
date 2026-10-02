#!/usr/bin/env python3
"""R8-Kennzahlen eines Release-Bundles: was Play ab Februar 2027 prueft - gemessen, bevor Play es tut.

## Warum es das gibt (Issue #54)

Play verlangt fuer Apps mit **mehr als 10 MB DEX** (unkomprimiert), dass Obfuskation, Optimierung
UND Shrinking jeweils mindestens 25 % erreichen. Die Zahlen stehen nicht in Android Vitals, sondern
pro hochgeladenem Bundle im App-Bundle-Explorer - und dorthin schaut niemand, bevor es zu spaet ist.
Quelle der Zahlen ist bei AGP >= 8.10 die Datei `BUNDLE-METADATA/com.android.tools/r8.json` im
Bundle, die R8 bei jedem Release-Build schreibt (lokal liegt dieselbe Datei als
`app/build/intermediates/r8_metadata/release/minifyReleaseWithR8/r8-metadata.dat`).

Gemessen am 01.10.2026, noch mit `-dontobfuscate`: DEX 10 096 720 Byte, Obfuskation 0,01 %,
Optimierung 77,9 %, Shrinking 78,4 % - die App lag knapp ueber der Schwelle und waere an der
Obfuskation durchgefallen. Dieses Werkzeug macht die Zahlen in jedem Lauf sichtbar.

## Was es prueft

- Die drei Prozentwerte. R8 schreibt die NEGATIVE Sicht (`noObfuscationPercentage` = Anteil, der
  NICHT umbenannt wurde); Play zeigt den Gegenwert. Hier wird umgerechnet: `100 - no...`.
- DEX-Groesse = Summe aller `dexFiles[].sizeInBytes`, auch die der Feature-Splits.
- **Warnung** (`::warning::`), wenn das DEX ueber 10 MB liegt UND ein Wert unter 25 % faellt.
  Bewusst kein Abbruch (Entscheidung 02.10.2026): bis zur Durchsetzung ist das ein Hinweis.
  10 MB heisst hier 10 000 000 Byte. Google sagt nicht, ob dezimal oder binaer gemeint ist; die
  dezimale Lesart ist die strengere und warnt frueher - die richtige Fehlerrichtung.
- **Fehler** (Exit 1), wenn die eingebettete Mapping-Datei
  `BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map` fehlt. Seit die App
  obfuskiert, ist sie die Kopie, mit der Play Absturzberichte zurueckuebersetzt - ohne sie
  stuende in jedem Bericht nur `a.b.c(SourceFile:412)`. Deshalb wird sie auch NICHT zusaetzlich
  per `mappingFile:` hochgeladen: Play lehnt eine zweite Mapping-Datei ab, wenn das Bundle schon
  eine eingebettet hat.
- Fehlt `r8.json`, ist das nur eine Warnung: Play schaetzt dann selbst (aus der Mapping-Datei
  oder dem DEX), und ein fehlender Messwert soll keine Auslieferung anhalten.

## Aufruf

    python3 tools/release/r8_kennzahlen.py app/build/outputs/bundle/release/app-release.aab
    python3 tools/release/r8_kennzahlen.py app/build/intermediates/r8_metadata/release/minifyReleaseWithR8/r8-metadata.dat

Ein Bundle (Zip) wird vollstaendig geprueft; eine einzelne JSON-Datei liefert nur die Kennzahlen
(fuer den lokalen Blick ohne `bundleRelease`). Ist `GITHUB_STEP_SUMMARY` gesetzt, landet eine
Tabelle im Job-Summary.
"""
from __future__ import annotations

import json
import os
import sys
import zipfile
from dataclasses import dataclass
from pathlib import Path

R8_JSON_PFAD = "BUNDLE-METADATA/com.android.tools/r8.json"
MAPPING_PFAD = "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map"

# Play: "more than 10 MB of DEX" - dezimal gelesen, siehe Modulkopf.
DEX_SCHWELLE_BYTE = 10_000_000
MINDEST_PROZENT = 25.0


@dataclass(frozen=True)
class Kennzahlen:
    dex_bytes: int
    obfuskation: float
    optimierung: float
    shrinking: float
    obfuskation_an: bool | None
    r8_version: str | None

    def werte(self) -> dict[str, float]:
        return {
            "Obfuskation": self.obfuskation,
            "Optimierung": self.optimierung,
            "Shrinking": self.shrinking,
        }

    def zu_niedrig(self) -> list[str]:
        """Die Namen der Werte unter der Play-Schwelle."""
        return [name for name, wert in self.werte().items() if wert < MINDEST_PROZENT]

    def ueber_dex_schwelle(self) -> bool:
        return self.dex_bytes > DEX_SCHWELLE_BYTE

    def verfehlt_play_vorgabe(self) -> bool:
        """Nur wenn BEIDES zutrifft, verlangt Play die 25 % - unter 10 MB DEX gilt nichts."""
        return self.ueber_dex_schwelle() and bool(self.zu_niedrig())


class KennzahlenFehler(ValueError):
    """r8.json hat nicht die erwartete Form - lieber laut als eine erfundene Zahl."""


def _prozent(stats: dict, schluessel: str) -> float:
    if schluessel not in stats:
        raise KennzahlenFehler(f"stats.{schluessel} fehlt in r8.json")
    try:
        nicht = float(stats[schluessel])
    except (TypeError, ValueError) as e:
        raise KennzahlenFehler(f"stats.{schluessel} ist keine Zahl: {stats[schluessel]!r}") from e
    # R8 meldet den Anteil, der NICHT bearbeitet wurde; Play zeigt den Gegenwert.
    return round(100.0 - nicht, 2)


def _dex_summe(dateien) -> int:
    summe = 0
    for eintrag in dateien or []:
        summe += int(eintrag.get("sizeInBytes", 0))
    return summe


def lies_kennzahlen(roh: str | bytes) -> Kennzahlen:
    """Wertet den Inhalt von r8.json bzw. r8-metadata.dat aus (Feldnamen wie R8 9.4.14 sie schreibt)."""
    try:
        daten = json.loads(roh)
    except json.JSONDecodeError as e:
        raise KennzahlenFehler(f"r8.json ist kein JSON: {e}") from e
    if not isinstance(daten, dict):
        raise KennzahlenFehler("r8.json ist kein JSON-Objekt")

    stats = daten.get("stats")
    if not isinstance(stats, dict):
        raise KennzahlenFehler("Abschnitt 'stats' fehlt in r8.json")

    dex = _dex_summe(daten.get("dexFiles"))
    splits = (daten.get("featureSplits") or {}).get("featureSplits") or []
    for split in splits:
        dex += _dex_summe(split.get("dexFiles"))

    optionen = daten.get("options") or {}
    return Kennzahlen(
        dex_bytes=dex,
        obfuskation=_prozent(stats, "noObfuscationPercentage"),
        optimierung=_prozent(stats, "noOptimizationPercentage"),
        shrinking=_prozent(stats, "noShrinkingPercentage"),
        obfuskation_an=optionen.get("isObfuscationEnabled"),
        r8_version=daten.get("version"),
    )


def _mb(byte: int) -> str:
    return f"{byte / 1_000_000:.2f} MB ({byte / 1_048_576:.2f} MiB)"


def summary_markdown(k: Kennzahlen, quelle: str, mapping_eingebettet: bool | None) -> str:
    zeilen = [
        "### R8-Kennzahlen (Play-Vorgabe ab Februar 2027)",
        "",
        f"Quelle: `{quelle}`" + (f" - R8 {k.r8_version}" if k.r8_version else ""),
        "",
        "| Kennzahl | Wert | Play verlangt |",
        "|---|---|---|",
        f"| DEX-Groesse | {_mb(k.dex_bytes)} | Pruefung erst ueber 10 MB |",
    ]
    for name, wert in k.werte().items():
        markierung = " **(zu niedrig)**" if wert < MINDEST_PROZENT else ""
        zeilen.append(f"| {name} | {wert:.2f} %{markierung} | >= {MINDEST_PROZENT:.0f} % |")
    zeilen.append("")
    if mapping_eingebettet is not None:
        zeilen.append(
            "Mapping-Datei im Bundle eingebettet: " + ("ja" if mapping_eingebettet else "**NEIN**")
        )
    if k.obfuskation_an is False:
        zeilen.append("")
        zeilen.append("R8 meldet `isObfuscationEnabled: false` - steht `-dontobfuscate` wieder in den Regeln?")
    return "\n".join(zeilen) + "\n"


def pruefe(pfad: Path) -> tuple[int, list[str], str | None]:
    """Liefert (Exit-Code, Zeilen fuer die Konsole, Summary-Markdown oder None)."""
    ausgabe: list[str] = []
    exit_code = 0

    if zipfile.is_zipfile(pfad):
        with zipfile.ZipFile(pfad) as bundle:
            namen = set(bundle.namelist())
            mapping_da = MAPPING_PFAD in namen
            roh = bundle.read(R8_JSON_PFAD) if R8_JSON_PFAD in namen else None
        if not mapping_da:
            ausgabe.append(
                f"::error::{MAPPING_PFAD} fehlt im Bundle - Play koennte Absturzberichte nicht "
                "zurueckuebersetzen. Ist Minify aus oder hat AGP das Einbetten geaendert?"
            )
            exit_code = 1
        else:
            ausgabe.append(f"Mapping-Datei eingebettet: {MAPPING_PFAD}")
        if roh is None:
            ausgabe.append(
                f"::warning::{R8_JSON_PFAD} fehlt im Bundle - Play schaetzt die Kennzahlen dann "
                "selbst. Keine Messung in diesem Lauf."
            )
            return exit_code, ausgabe, None
        quelle = f"{pfad.name}!{R8_JSON_PFAD}"
    else:
        mapping_da = None
        roh = pfad.read_bytes()
        quelle = str(pfad)

    try:
        k = lies_kennzahlen(roh)
    except KennzahlenFehler as e:
        # Ein unlesbares r8.json ist kein Grund, die Auslieferung anzuhalten - aber auch keiner,
        # still eine Zahl zu erfinden. Der Mapping-Befund von oben bleibt dabei bestehen.
        ausgabe.append(f"::warning::R8-Kennzahlen nicht auswertbar: {e}")
        return exit_code, ausgabe, None
    ausgabe.append(f"DEX: {_mb(k.dex_bytes)}")
    for name, wert in k.werte().items():
        ausgabe.append(f"{name}: {wert:.2f} %")

    if k.verfehlt_play_vorgabe():
        ausgabe.append(
            "::warning::DEX ueber 10 MB und unter 25 %: " + ", ".join(k.zu_niedrig())
            + " - ab Februar 2027 lehnt Play so ein Bundle ab."
        )
    elif k.zu_niedrig():
        ausgabe.append(
            "Unter 25 %: " + ", ".join(k.zu_niedrig())
            + " - zaehlt erst, wenn das DEX ueber 10 MB waechst."
        )

    return exit_code, ausgabe, summary_markdown(k, quelle, mapping_da)


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"Aufruf: {argv[0]} <app-release.aab | r8.json | r8-metadata.dat>", file=sys.stderr)
        return 2
    pfad = Path(argv[1])
    if not pfad.is_file():
        print(f"::error::{pfad} nicht gefunden", file=sys.stderr)
        return 1
    exit_code, ausgabe, summary = pruefe(pfad)
    for zeile in ausgabe:
        print(zeile)
    summary_ziel = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary and summary_ziel:
        with open(summary_ziel, "a", encoding="utf-8") as f:
            f.write(summary)
    return exit_code


if __name__ == "__main__":
    sys.exit(main(sys.argv))
