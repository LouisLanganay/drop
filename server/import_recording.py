"""Importe un enregistrement du bouton « Enregistrer » (files/recordings/*.csv) comme une session du serveur.

Usage : python3 import_recording.py <csv> [--dur "Charger=155" ...] [--pos "173.3=8.2" ...] [--started 2026-10-01T23:13:25]
Les anciens enregistrements n'ont ni durée de morceau ni position Spotify : --dur et --pos les ajoutent pour que
l'analyse sache si un morceau a été entendu en entier.
"""
import argparse
import datetime as dt
import gzip
import hashlib
import json
import os
import urllib.request

URL = os.environ.get("DROP_URL", "http://127.0.0.1:5720")
HEAD = {"Tailscale-User-Login": "louislanganay@gmail.com"}


def post(path, data, ctype="application/gzip"):
    req = urllib.request.Request(URL + path, data=data, method="POST", headers={**HEAD, "Content-Type": ctype})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv")
    ap.add_argument("--dur", action="append", default=[], help="titre=secondes")
    ap.add_argument("--pos", action="append", default=[], help="t=position Spotify en secondes")
    ap.add_argument("--started", help="heure de Go, ISO local")
    a = ap.parse_args()
    text = open(a.csv, encoding="utf-8").read()
    sid = "rec-" + hashlib.sha1(os.path.basename(a.csv).encode()).hexdigest()[:12]
    durs = dict(x.split("=", 1) for x in a.dur)
    lines = [f"M,app,import {os.path.basename(a.csv)}", "M,device,SM-S921B", "M,zone,Salon"]
    if a.started:
        lines.append(f"M,started_at,{int(dt.datetime.fromisoformat(a.started).timestamp() * 1000)}")
    for line in text.splitlines():
        lines.append(line)
        p = line.split(",")
        if p[0] == "E" and len(p) > 3 and p[2] == "title":
            title = ",".join(p[3:]).rpartition(" - ")[2]
            for k, v in durs.items():
                if k.lower() in title.lower():
                    lines.append(f"E,{p[1]},dur,{v}")
    for x in a.pos:
        t, v = x.split("=", 1)
        lines.append(f"E,{t},pos,{v}")
    print(sid, post(f"/api/sessions/{sid}/chunks?seq=0", gzip.compress("\n".join(lines).encode())))
    print(post(f"/api/sessions/{sid}/end", b"", "text/plain"))


if __name__ == "__main__":
    main()
