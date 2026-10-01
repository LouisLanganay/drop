"""Analyse a posteriori d'une écoute : temps, énergie des basses par temps, drops d'un morceau entendu en entier.

Même règle que la détection en direct (Analyzer.kt), mais avec le niveau des passages forts du morceau ENTIER,
connu après coup : une montée d'énergie avant le drop ne peut plus passer pour un drop. C'est ce qui alimente la
mémoire des morceaux.
"""
import gzip
import math
from urllib.parse import unquote

import numpy as np

COLS = ["t", "rms2", "bass", "high", "bflux", "mflux", "onset", "level", "bassmid", "bpm", "conf"]

# Règle de drop, réglée le 01/10/2026 sur les verdicts de Louis (« Charger », « Lunettes »).
RISE_DB = 6.0        # au-dessus de l'énergie des quatre mesures d'avant
LOUD_DB = 3.0        # au plus sous le niveau des passages forts (80e centile des temps du morceau)
COLLAPSE_DB = 12.0   # aucun temps de la première mesure ne s'effondre
SPACING_S = 15.0     # entre deux drops, sauf drop nettement plus fort (+6 dB)


def db(v):
    return 10.0 * math.log10(v + 1e-12)


def parse_csv(text):
    """Rend (meta, frames ndarray, events [(t, kind, detail)]) d'un enregistrement ou d'une tranche."""
    meta, frames, events = {}, [], []
    for line in text.splitlines():
        if not line:
            continue
        p = line.split(",")
        tag = p[0]
        if tag == "F":
            if p[1] == "t":
                continue
            try:
                frames.append([float(x) for x in p[1:12]])
            except ValueError:
                continue
        elif tag == "E":
            if p[1] == "t":
                continue
            try:
                t = float(p[1])
            except ValueError:
                continue
            events.append((t, p[2] if len(p) > 2 else "", unquote(",".join(p[3:])) if len(p) > 3 else ""))
        elif tag == "M" and len(p) >= 3:
            meta[p[1]] = unquote(",".join(p[2:]))
    arr = np.array(frames, dtype=np.float64) if frames else np.zeros((0, len(COLS)))
    if arr.ndim == 2 and arr.shape[1] < len(COLS):
        arr = np.pad(arr, ((0, 0), (0, len(COLS) - arr.shape[1])))
    return meta, arr, events


def read_chunks(paths):
    metas, arrays, events = {}, [], []
    for path in paths:
        with gzip.open(path, "rt", encoding="utf-8") as f:
            m, a, e = parse_csv(f.read())
        metas.update(m)
        if len(a):
            arrays.append(a)
        events.extend(e)
    frames = np.concatenate(arrays) if arrays else np.zeros((0, len(COLS)))
    if len(frames):
        frames = frames[np.argsort(frames[:, 0], kind="stable")]
    events.sort(key=lambda e: e[0])
    return metas, frames, events


def beat_times(events, t0=-1e9, t1=1e9):
    """Temps émis par le suivi de tempo, sans les doublons d'un recalage (moins de 0,15 s d'écart)."""
    out = []
    for t, kind, _ in events:
        if kind == "beat" and t0 <= t <= t1:
            if not out or t - out[-1] > 0.15:
                out.append(t)
    return out


def beat_bass(frames, beats):
    """Énergie des basses de chaque temps (du temps au suivant), en dB ; NaN si aucun bloc."""
    t = frames[:, 0]
    bass = frames[:, 2]
    idx = np.searchsorted(t, beats)
    out = np.full(max(len(beats) - 1, 0), np.nan)
    for i in range(len(beats) - 1):
        a, b = idx[i], idx[i + 1]
        if b > a:
            out[i] = db(float(bass[a:b].mean()))
    return out


def norm_key(artist, title):
    """Clé d'un morceau, la même que sur le téléphone : titre et artistes triés, en minuscules."""
    arts = sorted(a.strip().lower() for a in artist.replace(";", ",").split(",") if a.strip())
    return title.strip().lower() + "|" + ", ".join(arts)


def plays_from_events(events, t_end):
    """Écoutes successives : un morceau par événement « title », avec sa durée et les positions Spotify relevées."""
    plays = []
    cur = None
    for t, kind, detail in events:
        if kind == "title":
            if cur:
                cur["t1"] = t
                plays.append(cur)
            artist, _, title = detail.rpartition(" - ")
            artist = artist.replace(";", ",")
            cur = {"t0": t, "t1": t_end, "detail": detail, "key": norm_key(artist, title), "artist": artist,
                   "title": title, "duration": None, "pos": []}
        elif cur is not None and kind == "track_key":
            cur["key"] = detail
        elif cur is not None and kind == "dur":
            try:
                cur["duration"] = float(detail)
            except ValueError:
                pass
        elif cur is not None and kind == "pos":
            try:
                cur["pos"].append((t, float(detail.split()[0])))
            except (ValueError, IndexError):
                pass
    if cur:
        plays.append(cur)
    for p in plays:
        if p["pos"]:
            p["offset"] = float(np.median([pos - t for t, pos in p["pos"]]))
        else:
            # Ancien enregistrement sans position : le morceau commence à l'arrivée du titre (approximatif).
            p["offset"] = -p["t0"]
        p["pos0"] = p["t0"] + p["offset"]
        p["pos1"] = p["t1"] + p["offset"]
        d = p["duration"]
        p["complete"] = bool(d and p["pos0"] <= 20.0 and p["pos1"] >= min(d - 20.0, d * 0.85))
    return plays


def find_drops(per, beats, loud=None):
    """Drops d'une suite de temps : rend [(index du temps, niveau, montée)]."""
    vals = per[~np.isnan(per)]
    if len(vals) < 16:
        return []
    if loud is None:
        loud = float(np.percentile(vals, 80))
    drops = []
    last_t, last_level = -1e9, -999.0
    i = 4
    n = len(per)
    while i + 4 <= n:
        win = per[i:i + 4]
        ctx = per[max(0, i - 16):i]
        ctx = ctx[~np.isnan(ctx)]
        if np.isnan(win).any() or len(ctx) < 4:
            i += 1
            continue
        level = float(win.mean())
        weakest = float(win.min())
        rise = level - db(float(np.mean(10 ** (ctx / 10))))
        t = beats[i]
        ok = (level >= loud - LOUD_DB and rise >= RISE_DB and weakest >= max(loud - COLLAPSE_DB, level - COLLAPSE_DB)
              and (t - last_t > SPACING_S or level >= last_level + 6))
        if ok:
            drops.append((i, level, rise))
            last_t, last_level = t, level
            i += 4
        else:
            i += 1
    return drops


def analyse_play(frames, events, play):
    """Drops d'une écoute d'après tout le morceau : [{t, pos, level, rise}] et le niveau des passages forts."""
    beats = beat_times(events, play["t0"], play["t1"])
    if len(beats) < 24 or not len(frames):
        return [], None
    per = beat_bass(frames, beats)
    vals = per[~np.isnan(per)]
    if len(vals) < 16:
        return [], None
    loud = float(np.percentile(vals, 80))
    out = []
    for i, level, rise in find_drops(per, beats, loud):
        t = beats[i]
        out.append({"t": round(t, 3), "pos": round(t + play["offset"], 2), "level": round(level, 1), "rise": round(rise, 1)})
    return out, round(loud, 1)


def downsample(frames, t_from, t_to, n):
    """Courbes pour l'écran : n colonnes entre t_from et t_to (niveau max, basses et basses lissées en dB, tempo)."""
    if not len(frames):
        return {"t": [], "level": [], "bass": [], "bassmid": [], "bpm": []}
    t = frames[:, 0]
    a, b = np.searchsorted(t, [t_from, t_to])
    seg = frames[a:b]
    if not len(seg):
        return {"t": [], "level": [], "bass": [], "bassmid": [], "bpm": []}
    edges = np.linspace(t_from, t_to, n + 1)
    idx = np.clip(np.searchsorted(edges, seg[:, 0], side="right") - 1, 0, n - 1)
    out = {"t": [], "level": [], "bass": [], "bassmid": [], "bpm": []}
    counts = np.bincount(idx, minlength=n)
    lvl_max = np.full(n, -1.0)
    np.maximum.at(lvl_max, idx, seg[:, 7])
    bass_sum = np.bincount(idx, weights=seg[:, 2], minlength=n)
    mid_sum = np.bincount(idx, weights=seg[:, 8], minlength=n)
    bpm_sum = np.bincount(idx, weights=seg[:, 9], minlength=n)
    for c in range(n):
        if counts[c] == 0:
            continue
        out["t"].append(round(float((edges[c] + edges[c + 1]) / 2), 3))
        out["level"].append(round(float(lvl_max[c]), 3))
        out["bass"].append(round(db(bass_sum[c] / counts[c]), 1))
        out["bassmid"].append(round(db(mid_sum[c] / counts[c]), 1))
        out["bpm"].append(round(float(bpm_sum[c] / counts[c]), 1))
    return out
