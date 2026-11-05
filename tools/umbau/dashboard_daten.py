"""Sammelt den Stand des Umbaus fuer das Fortschritts-Dashboard (claude.ai-Artifact).

Quellen: GitHub-Issues (Labels umbau/etappe/paket), das Runner-Protokoll STATUS.txt und die
Lauf-Logs (*.jsonl, fuer das Kontingent). Schreibt vier JSON-Dateien (Zeilenlisten) nach
..Projektdateien/umbau/dashboard/ - die laedt eine Claude-Sitzung ins Dashboard hoch.

Aufruf: python tools/umbau/dashboard_daten.py
"""
import datetime as dt
import glob
import io
import json
import os
import re
import subprocess

WURZEL = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
PROJ = os.path.join(WURZEL, "..Projektdateien", "umbau")
LOGS = os.path.join(PROJ, "logs")
ZIEL = os.path.join(PROJ, "dashboard")
REPO = "F1rlefanz/CF-Alarm-for-TimeOffice"
ETAPPEN_GESAMT = 10


def gh_issues():
    out = subprocess.run(
        ["gh", "issue", "list", "--repo", REPO, "--label", "umbau", "--state", "all", "--limit", "400",
         "--json", "number,title,state,labels,closedAt,createdAt"],
        capture_output=True, text=True, encoding="utf-8", check=True).stdout
    issues = json.loads(out)
    for i in issues:
        i["L"] = [l["name"] for l in i["labels"]]
    return issues


def etappe_von(i):
    for l in i["L"]:
        m = re.match(r"^etappe-(\d+)$", l)
        if m:
            return int(m.group(1))
    return None


def status_zeilen():
    pfad = os.path.join(LOGS, "STATUS.txt")
    if not os.path.exists(pfad):
        return []
    zeilen = []
    for z in io.open(pfad, encoding="utf-8-sig", errors="replace"):
        m = re.match(r"^(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)\s+(.*)$", z.strip())
        if m:
            zeilen.append((m.group(1), m.group(2)))
    return zeilen


def kontingent():
    """Letztes rate_limit_event aus den neuesten Lauf-Logs."""
    for pfad in sorted(glob.glob(os.path.join(LOGS, "*.jsonl")), key=os.path.getmtime, reverse=True)[:3]:
        letzte = None
        for z in io.open(pfad, encoding="utf-8", errors="replace"):
            if '"rate_limit_event"' in z:
                letzte = z
        if letzte:
            try:
                w = json.loads(letzte)["rate_limit_info"]["unifiedWindows"]
                return w.get("five_hour", {}), w.get("seven_day", {})
            except (ValueError, KeyError):
                pass
    return {}, {}


def zeit(epoch):
    return dt.datetime.fromtimestamp(epoch).strftime("%Y-%m-%dT%H:%M") if epoch else None


def main():
    issues = gh_issues()
    status = status_zeilen()
    jetzt = dt.datetime.now().strftime("%Y-%m-%dT%H:%M")

    etappen_issues = {etappe_von(i): i for i in issues if "etappe" in i["L"] and etappe_von(i) is not None}
    pakete = [i for i in issues if "paket" in i["L"]]
    versionen = {}
    # Ausgelieferte Versionen aus den Release-Commits der Etappen-Abschluesse.
    log = subprocess.run(["git", "-C", WURZEL, "log", "origin/main", "--format=%s", "-400"],
                         capture_output=True, text=True, encoding="utf-8").stdout
    for z in log.splitlines():
        m = re.search(r"v?(\d+\.\d+\.\d+).*Umbau Etappe (\d+)", z)
        if m and int(m.group(2)) not in versionen:
            versionen[int(m.group(2))] = m.group(1)

    laufend_text = status[-1][1] if status else ""
    m = re.match(r"#(\d+) Lauf (\d+) \(([^,]+)", laufend_text)
    laufendes_paket = int(m.group(1)) if m else None

    etappen_zeilen = []
    for n in range(ETAPPEN_GESAMT):
        e = etappen_issues.get(n)
        pk = [p for p in pakete if etappe_von(p) == n]
        fertig = sum(1 for p in pk if p["state"] == "CLOSED")
        if e is None:
            zustand = "offen"
        elif e["state"] == "CLOSED":
            zustand = "fertig"
        elif pk or "geschnitten" in e["L"]:
            zustand = "läuft"
        else:
            zustand = "offen"
        titel = re.sub(r"^Umbau Etappe \d+: ", "", e["title"]) if e else ""
        etappen_zeilen.append({
            "etappe": n, "titel": titel, "zustand": zustand,
            "pakete_fertig": fertig, "pakete_gesamt": len(pk),
            "version": versionen.get(n, ""),
            "issue": e["number"] if e else None,
        })

    aktuelle = next((e for e in etappen_zeilen if e["zustand"] != "fertig"), None)
    paket_zeilen = []
    if aktuelle:
        for p in sorted((p for p in pakete if etappe_von(p) == aktuelle["etappe"]), key=lambda p: p["number"]):
            if p["state"] == "CLOSED":
                z = "fertig"
            elif "blockiert" in p["L"]:
                z = "blockiert"
            elif p["number"] == laufendes_paket or "in-arbeit" in p["L"]:
                z = "läuft"
            else:
                z = "wartet"
            paket_zeilen.append({
                "nr": p["number"], "titel": re.sub(r"^Etappe \d+: ", "", p["title"]), "zustand": z,
                "modell": "Opus" if "risiko-hoch" in p["L"] else "Sonnet",
                "laeufe": 2 if "lauf-2" in p["L"] else (1 if "lauf-1" in p["L"] else 0),
            })

    fertige_etappen = sum(1 for e in etappen_zeilen if e["zustand"] == "fertig")
    anteil_laufend = 0.0
    if aktuelle and aktuelle["pakete_gesamt"]:
        anteil_laufend = aktuelle["pakete_fertig"] / aktuelle["pakete_gesamt"] * 0.85  # Abschluss = Rest
    fortschritt = (fertige_etappen + anteil_laufend) / ETAPPEN_GESAMT

    # Tempo: fertige Pakete je Stunde aktiver Laufzeit (aus STATUS: Lauf-Start bis "fertig").
    fertig_zeiten = [dt.datetime.fromisoformat(t) for t, s in status if s.endswith(": fertig")]
    pakete_fertig_gesamt = sum(1 for p in pakete if p["state"] == "CLOSED")
    fuenf, sieben = kontingent()

    pausiert = bool(status) and ("warte bis" in status[-1][1] or "Pause bis" in status[-1][1])
    beendet = bool(status) and status[-1][1] in ("Runner beendet",) or (status and "Ende" in status[-1][1])
    if beendet:
        lage = "Runner angehalten"
    elif pausiert:
        lage = "Runner wartet aufs Kontingent"
    else:
        lage = "Runner arbeitet"

    kenn = [{
        "stand": jetzt,
        "lage": lage,
        "aktuell": laufend_text,
        "letzte_meldung": status[-1][0].replace("T", " ")[:16] if status else "",
        "fortschritt": round(fortschritt, 3),
        "etappen_fertig": fertige_etappen,
        "etappen_gesamt": ETAPPEN_GESAMT,
        "pakete_fertig": pakete_fertig_gesamt,
        "pakete_bekannt": len(pakete),
        "spaeter": sum(1 for i in issues if "spaeter" in i["L"] and i["state"] == "OPEN"),
        "woche_prozent": round(sieben.get("utilization", 0) * 100) if sieben else None,
        "woche_reset": zeit(sieben.get("resetsAt")),
        "fenster_prozent": round(fuenf.get("utilization", 0) * 100) if fuenf else None,
        "fenster_reset": zeit(fuenf.get("resetsAt")),
        "pakete_letzte_24h": sum(1 for t in fertig_zeiten if t > dt.datetime.now() - dt.timedelta(hours=24)),
        "aktuelle_etappe": aktuelle["etappe"] if aktuelle else None,
    }]

    verlauf = [{"zeit": t.replace("T", " ")[:16], "meldung": s} for t, s in status[-25:]][::-1]

    os.makedirs(ZIEL, exist_ok=True)
    for name, daten in (("kennzahlen", kenn), ("etappen", etappen_zeilen), ("pakete", paket_zeilen),
                        ("verlauf", verlauf)):
        with io.open(os.path.join(ZIEL, name + ".json"), "w", encoding="utf-8") as f:
            json.dump(daten, f, ensure_ascii=False, indent=1)
    print(json.dumps(kenn[0], ensure_ascii=False))


if __name__ == "__main__":
    main()
