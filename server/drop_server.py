"""API et dashboard de Drop.

Reçoit les sessions du téléphone par tranches, garde tout en base (SQLite) et sur disque, analyse chaque morceau
entendu en entier pour mémoriser ses drops, enregistre les verdicts de Louis et sert le dashboard d'admin.

Accès réservé au tailnet : le service écoute sur 127.0.0.1 et n'est exposé que par `tailscale serve` (jamais
Funnel). Chaque requête doit porter l'en-tête Tailscale-User-Login d'un compte autorisé, que `tailscale serve`
pose lui-même (et retire s'il vient du client).
"""
import gzip
import json
import os
import re
import sqlite3
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlparse

import numpy as np

import analysis as A

DATA = Path(os.environ.get("DROP_DATA", str(Path.home() / "drop-data")))
STATIC = Path(__file__).parent / "static"
PORT = int(os.environ.get("DROP_PORT", "5720"))
ALLOWED = {x.strip() for x in os.environ.get("DROP_ALLOWED_LOGINS", "louislanganay@gmail.com").split(",") if x.strip()}
MATCH_S = 1.5  # deux drops à moins de 1,5 s l'un de l'autre sont le même drop (la position Spotify flotte d'environ 1 s)

DATA.mkdir(parents=True, exist_ok=True)
(DATA / "sessions").mkdir(exist_ok=True)
(DATA / "apk").mkdir(exist_ok=True)
DB_PATH = DATA / "drop.db"
lock = threading.RLock()


def db():
    c = sqlite3.connect(DB_PATH, timeout=30, check_same_thread=False)
    c.row_factory = sqlite3.Row
    return c


def init_db():
    with lock, db() as c:
        c.executescript(
            """
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS sessions(
              id TEXT PRIMARY KEY, started_at INTEGER, ended_at INTEGER, last_t REAL DEFAULT 0, device TEXT, app TEXT,
              zone TEXT, created_at INTEGER, analysed_at INTEGER, note TEXT);
            CREATE TABLE IF NOT EXISTS chunks(
              session_id TEXT, seq INTEGER, t0 REAL, t1 REAL, frames INTEGER, events INTEGER, bytes INTEGER,
              received_at INTEGER, PRIMARY KEY(session_id, seq));
            CREATE TABLE IF NOT EXISTS events(session_id TEXT, t REAL, kind TEXT, detail TEXT);
            CREATE INDEX IF NOT EXISTS events_s ON events(session_id, t);
            CREATE TABLE IF NOT EXISTS tracks(
              key TEXT PRIMARY KEY, artist TEXT, title TEXT, duration REAL, first_seen INTEGER, forgotten_at INTEGER);
            CREATE TABLE IF NOT EXISTS plays(
              id INTEGER PRIMARY KEY, session_id TEXT, track_key TEXT, t0 REAL, t1 REAL, offset REAL, pos0 REAL,
              pos1 REAL, complete INTEGER, loud REAL, drops_json TEXT, live_json TEXT, played_at INTEGER);
            CREATE INDEX IF NOT EXISTS plays_k ON plays(track_key);
            CREATE TABLE IF NOT EXISTS verdicts(
              id INTEGER PRIMARY KEY, session_id TEXT, t REAL, track_key TEXT, pos REAL, verdict TEXT, note TEXT,
              created_at INTEGER);
            """
        )


# ─── Réception des tranches ────────────────────────────────────────────────────

def ingest_chunk(sid, seq, raw):
    if not re.fullmatch(r"[A-Za-z0-9_-]{6,64}", sid):
        raise ValueError("identifiant de session invalide")
    text = gzip.decompress(raw).decode("utf-8") if raw[:2] == b"\x1f\x8b" else raw.decode("utf-8")
    meta, frames, events = A.parse_csv(text)
    folder = DATA / "sessions" / sid
    folder.mkdir(exist_ok=True)
    path = folder / f"{seq:05d}.csv.gz"
    with lock, db() as c:
        if c.execute("SELECT 1 FROM chunks WHERE session_id=? AND seq=?", (sid, seq)).fetchone():
            return {"ok": True, "duplicate": True}
        path.write_bytes(gzip.compress(text.encode("utf-8")))
        now = int(time.time() * 1000)
        c.execute("INSERT OR IGNORE INTO sessions(id, created_at) VALUES(?, ?)", (sid, now))
        for k, col in (("started_at", "started_at"), ("device", "device"), ("app", "app"), ("zone", "zone")):
            if k in meta:
                c.execute(f"UPDATE sessions SET {col}=? WHERE id=?", (meta[k], sid))
        t0 = float(frames[0, 0]) if len(frames) else None
        t1 = float(frames[-1, 0]) if len(frames) else None
        c.execute("INSERT INTO chunks VALUES(?,?,?,?,?,?,?,?)", (sid, seq, t0, t1, len(frames), len(events), len(raw), now))
        c.executemany("INSERT INTO events VALUES(?,?,?,?)", [(sid, t, k, d) for t, k, d in events if k != "beat"])
        if t1 is not None:
            c.execute("UPDATE sessions SET last_t=MAX(last_t, ?) WHERE id=?", (t1, sid))
    cache.pop(sid, None)
    return {"ok": True, "frames": len(frames), "events": len(events)}


# ─── Lecture d'une session (avec cache) ────────────────────────────────────────

cache = {}


def load_session(sid):
    paths = sorted((DATA / "sessions" / sid).glob("*.csv.gz"))
    stamp = (len(paths), sum(p.stat().st_size for p in paths))
    hit = cache.get(sid)
    if hit and hit[0] == stamp:
        return hit[1]
    data = A.read_chunks(paths)
    cache[sid] = (stamp, data)
    while len(cache) > 4:
        cache.pop(next(iter(cache)))
    return data


# ─── Analyse et mémoire ────────────────────────────────────────────────────────

def analyse_session(sid):
    meta, frames, events = load_session(sid)
    if not len(frames):
        return {"plays": 0}
    plays = A.plays_from_events(events, float(frames[-1, 0]))
    live = [(t, k, d) for t, k, d in events if k in ("drop", "drop_cancel", "mem_drop", "mem_miss")]
    keys = set()
    with lock, db() as c:
        c.execute("DELETE FROM plays WHERE session_id=?", (sid,))
        started = c.execute("SELECT started_at FROM sessions WHERE id=?", (sid,)).fetchone()
        started = int(started[0]) if started and started[0] else None
        for p in plays:
            drops, loud = A.analyse_play(frames, events, p)
            lv = [{"t": t, "kind": k, "detail": d, "pos": round(t + p["offset"], 2)} for t, k, d in live if p["t0"] <= t < p["t1"]]
            c.execute(
                "INSERT INTO tracks(key, artist, title, duration, first_seen) VALUES(?,?,?,?,?) "
                "ON CONFLICT(key) DO UPDATE SET duration=COALESCE(excluded.duration, tracks.duration)",
                (p["key"], p["artist"], p["title"], p["duration"], int(time.time() * 1000)),
            )
            played_at = int(started + p["t0"] * 1000) if started else int(time.time() * 1000)
            c.execute(
                "INSERT INTO plays(session_id, track_key, t0, t1, offset, pos0, pos1, complete, loud, drops_json, live_json, played_at) "
                "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                (sid, p["key"], p["t0"], p["t1"], p["offset"], p["pos0"], p["pos1"], int(p["complete"]), loud,
                 json.dumps(drops), json.dumps(lv), played_at),
            )
            keys.add(p["key"])
        c.execute("UPDATE sessions SET analysed_at=? WHERE id=?", (int(time.time() * 1000), sid))
    return {"plays": len(plays), "tracks": sorted(keys)}


def track_memory(c, key):
    """Drops mémorisés d'un morceau, recalculés à chaque fois depuis ses écoutes complètes et les verdicts.

    Un drop trouvé dans une écoute complète compte un « vu », absent d'une autre un « manqué » : il reste en
    mémoire tant qu'il est vu au moins autant que manqué. Un verdict de Louis l'emporte toujours (le plus récent
    à cette position). « Oublier ce morceau » ignore tout ce qui précède.
    """
    tr = c.execute("SELECT * FROM tracks WHERE key=?", (key,)).fetchone()
    since = (tr["forgotten_at"] or 0) if tr else 0
    # Les enregistrements importés à la main ont des positions approximatives : ils servent au réglage, pas à la mémoire.
    plays = c.execute(
        "SELECT p.* FROM plays p JOIN sessions s ON s.id=p.session_id WHERE p.track_key=? AND p.complete=1 AND p.played_at>=? "
        "AND COALESCE(s.app, '') NOT LIKE 'import%' ORDER BY p.played_at", (key, since)).fetchall()
    clusters = []
    for p in plays:
        for d in json.loads(p["drops_json"] or "[]"):
            hit = next((cl for cl in clusters if abs(cl["pos"] - d["pos"]) <= MATCH_S), None)
            if hit:
                hit["seen"].add(p["id"])
                hit["positions"].append(d["pos"])
                hit["pos"] = float(np.median(hit["positions"]))
            else:
                clusters.append({"pos": d["pos"], "positions": [d["pos"]], "seen": {p["id"]}, "level": d["level"]})
    total = len(plays)
    verdicts = c.execute("SELECT * FROM verdicts WHERE track_key=? AND created_at>=? ORDER BY created_at", (key, since)).fetchall()
    out = []
    for cl in clusters:
        seen = len(cl["seen"])
        missed = total - seen
        v = [x for x in verdicts if x["pos"] is not None and abs(x["pos"] - cl["pos"]) <= MATCH_S]
        verdict = v[-1]["verdict"] if v else None
        status = "drop" if verdict == "drop" else "rejeté" if verdict == "not_drop" else ("drop" if seen >= missed else "écarté")
        out.append({"pos": round(cl["pos"], 2), "seen": seen, "missed": missed, "verdict": verdict, "status": status, "source": "analyse"})
    for x in verdicts:
        if x["verdict"] == "drop" and x["pos"] is not None and not any(abs(o["pos"] - x["pos"]) <= MATCH_S for o in out):
            out.append({"pos": round(x["pos"], 2), "seen": 0, "missed": 0, "verdict": "drop", "status": "drop", "source": "verdict"})
    out.sort(key=lambda o: o["pos"])
    return {"complete_plays": total, "drops": out}


def memory_for_phone():
    with lock, db() as c:
        res = {}
        for tr in c.execute("SELECT key, duration FROM tracks").fetchall():
            m = track_memory(c, tr["key"])
            drops = [d["pos"] for d in m["drops"] if d["status"] == "drop"]
            # Un morceau n'est « connu » qu'après une écoute complète (ou un drop posé à la main par Louis).
            if m["complete_plays"] >= 1 or drops:
                res[tr["key"]] = {"drops": drops, "plays": m["complete_plays"], "duration": tr["duration"]}
        return {"version": int(time.time()), "tracks": res}


def add_verdict(body):
    sid = body["session_id"]
    t = float(body["t"])
    verdict = body["verdict"]
    if verdict not in ("drop", "not_drop"):
        raise ValueError("verdict : drop ou not_drop")
    with lock, db() as c:
        p = c.execute("SELECT * FROM plays WHERE session_id=? AND t0<=? AND t1>? ORDER BY t0 DESC LIMIT 1", (sid, t, t)).fetchone()
        key = p["track_key"] if p else None
        pos = round(t + p["offset"], 2) if p else None
        cur = c.execute(
            "INSERT INTO verdicts(session_id, t, track_key, pos, verdict, note, created_at) VALUES(?,?,?,?,?,?,?)",
            (sid, t, key, pos, verdict, body.get("note", ""), int(time.time() * 1000)),
        )
        mem = track_memory(c, key) if key else None
        return {"ok": True, "id": cur.lastrowid, "track": key, "pos": pos, "memory": mem}


def calibration():
    """Les verdicts de Louis rejoués contre la règle actuelle (analyse a posteriori) et contre le direct."""
    rows = []
    with lock, db() as c:
        for v in c.execute("SELECT * FROM verdicts ORDER BY created_at").fetchall():
            p = c.execute("SELECT * FROM plays WHERE session_id=? AND t0<=? AND t1>? LIMIT 1", (v["session_id"], v["t"], v["t"])).fetchone()
            retro = live = None
            if p:
                drops = json.loads(p["drops_json"] or "[]")
                retro = any(abs(d["t"] - v["t"]) <= MATCH_S for d in drops)
                lv = json.loads(p["live_json"] or "[]")
                live = any(x["kind"] in ("drop", "mem_drop") and abs(x["t"] - v["t"]) <= MATCH_S for x in lv)
            want = v["verdict"] == "drop"
            tr = c.execute("SELECT title FROM tracks WHERE key=?", (v["track_key"],)).fetchone()
            rows.append({"id": v["id"], "session_id": v["session_id"], "t": v["t"], "pos": v["pos"], "title": tr["title"] if tr else None,
                         "verdict": v["verdict"], "retro": retro, "live": live,
                         "retro_ok": None if retro is None else retro == want, "live_ok": None if live is None else live == want})
    ok = sum(1 for r in rows if r["retro_ok"])
    return {"rows": rows, "retro_ok": ok, "total": len(rows)}


# ─── Vues pour le dashboard ────────────────────────────────────────────────────

def sessions_list():
    with lock, db() as c:
        out = []
        for s in c.execute("SELECT * FROM sessions ORDER BY COALESCE(started_at, created_at) DESC").fetchall():
            plays = c.execute("SELECT p.track_key, t.title, t.artist, p.complete FROM plays p LEFT JOIN tracks t ON t.key=p.track_key WHERE session_id=? ORDER BY t0", (s["id"],)).fetchall()
            n_drops = c.execute("SELECT COUNT(*) FROM events WHERE session_id=? AND kind IN ('drop','mem_drop')", (s["id"],)).fetchone()[0]
            n_chunks = c.execute("SELECT COUNT(*) FROM chunks WHERE session_id=?", (s["id"],)).fetchone()[0]
            out.append({**dict(s), "duration": s["last_t"], "chunks": n_chunks, "drops": n_drops,
                        "tracks": [{"key": p["track_key"], "title": p["title"], "artist": p["artist"], "complete": bool(p["complete"])} for p in plays]})
        return out


def session_detail(sid):
    with lock, db() as c:
        s = c.execute("SELECT * FROM sessions WHERE id=?", (sid,)).fetchone()
        if not s:
            return None
        plays = []
        for p in c.execute("SELECT p.*, t.title, t.artist, t.duration FROM plays p LEFT JOIN tracks t ON t.key=p.track_key WHERE session_id=? ORDER BY t0", (sid,)).fetchall():
            plays.append({**{k: p[k] for k in p.keys() if k not in ("drops_json", "live_json")},
                          "drops": json.loads(p["drops_json"] or "[]"), "live": json.loads(p["live_json"] or "[]")})
        events = [dict(e) for e in c.execute("SELECT t, kind, detail FROM events WHERE session_id=? AND kind NOT IN ('pos') ORDER BY t", (sid,)).fetchall()]
        verdicts = [dict(v) for v in c.execute("SELECT * FROM verdicts WHERE session_id=? ORDER BY t", (sid,)).fetchall()]
        return {"session": dict(s), "plays": plays, "events": events, "verdicts": verdicts}


def session_series(sid, t_from, t_to, n):
    meta, frames, events = load_session(sid)
    if not len(frames):
        return {"series": A.downsample(frames, 0, 1, 1), "beats": []}
    t_from = max(t_from, float(frames[0, 0]))
    t_to = min(t_to, float(frames[-1, 0]))
    beats = [(t, d) for t, k, d in events if k == "beat" and t_from <= t <= t_to]
    step = max(1, len(beats) // 4000)
    return {"from": t_from, "to": t_to, "series": A.downsample(frames, t_from, t_to, n),
            "beats": [[round(t, 3), int(d.split()[1]) if len(d.split()) > 1 else 0] for t, d in beats[::step]]}


def tracks_list():
    with lock, db() as c:
        out = []
        for tr in c.execute("SELECT * FROM tracks ORDER BY title").fetchall():
            m = track_memory(c, tr["key"])
            n = c.execute("SELECT COUNT(*) FROM plays WHERE track_key=?", (tr["key"],)).fetchone()[0]
            plays = [{"session_id": p["session_id"], "t0": p["t0"], "t1": p["t1"], "pos0": p["pos0"], "pos1": p["pos1"],
                      "complete": bool(p["complete"]), "loud": p["loud"], "drops": json.loads(p["drops_json"] or "[]"),
                      "played_at": p["played_at"]}
                     for p in c.execute("SELECT * FROM plays WHERE track_key=? ORDER BY played_at DESC", (tr["key"],)).fetchall()]
            out.append({**dict(tr), "plays": n, "memory": m, "history": plays})
        return out


def forget_track(key):
    with lock, db() as c:
        c.execute("UPDATE tracks SET forgotten_at=? WHERE key=?", (int(time.time() * 1000), key))
    return {"ok": True}


# ─── HTTP ──────────────────────────────────────────────────────────────────────

class Handler(BaseHTTPRequestHandler):
    server_version = "drop/1"

    def log_message(self, fmt, *args):
        pass

    def _auth(self):
        return self.headers.get("Tailscale-User-Login", "") in ALLOWED

    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        data = body if isinstance(body, bytes) else json.dumps(body, ensure_ascii=False).encode("utf-8")
        if "json" in ctype and len(data) > 2048 and "gzip" in self.headers.get("Accept-Encoding", ""):
            data = gzip.compress(data, 5)
            extra = {**(extra or {}), "Content-Encoding": "gzip"}
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length", "0") or 0)
        return self.rfile.read(n) if n else b""

    def _route(self, method):
        if not self._auth():
            return self._send(403, {"error": "accès réservé"})
        u = urlparse(self.path)
        q = {k: v[-1] for k, v in parse_qs(u.query).items()}
        path = u.path
        try:
            if method == "GET":
                if path in ("/", "/index.html"):
                    return self._file(STATIC / "index.html")
                if path.startswith("/static/"):
                    return self._file(STATIC / path[len("/static/"):])
                if path == "/apk":
                    apk = DATA / "apk" / "drop.apk"
                    if not apk.exists():
                        return self._send(404, {"error": "pas d'APK"})
                    return self._send(200, apk.read_bytes(), "application/vnd.android.package-archive",
                                      {"Content-Disposition": 'attachment; filename="drop.apk"'})
                if path == "/api/apk-info":
                    apk = DATA / "apk" / "drop.apk"
                    return self._send(200, {"exists": apk.exists(), "size": apk.stat().st_size if apk.exists() else 0,
                                            "mtime": int(apk.stat().st_mtime * 1000) if apk.exists() else None})
                if path == "/api/health":
                    return self._send(200, {"ok": True})
                if path == "/api/memory":
                    return self._send(200, memory_for_phone())
                if path == "/api/sessions":
                    return self._send(200, sessions_list())
                m = re.fullmatch(r"/api/sessions/([^/]+)", path)
                if m:
                    d = session_detail(m.group(1))
                    return self._send(200 if d else 404, d or {"error": "session inconnue"})
                m = re.fullmatch(r"/api/sessions/([^/]+)/series", path)
                if m:
                    return self._send(200, session_series(m.group(1), float(q.get("from", 0)), float(q.get("to", 1e9)), min(4000, int(q.get("n", 1500)))))
                if path == "/api/tracks":
                    return self._send(200, tracks_list())
                if path == "/api/calibration":
                    return self._send(200, calibration())
            if method == "POST":
                m = re.fullmatch(r"/api/sessions/([^/]+)/chunks", path)
                if m:
                    return self._send(200, ingest_chunk(m.group(1), int(q.get("seq", 0)), self._body()))
                m = re.fullmatch(r"/api/sessions/([^/]+)/end", path)
                if m:
                    sid = m.group(1)
                    with lock, db() as c:
                        c.execute("UPDATE sessions SET ended_at=? WHERE id=?", (int(time.time() * 1000), sid))
                    return self._send(200, {"ok": True, **analyse_session(sid)})
                m = re.fullmatch(r"/api/sessions/([^/]+)/analyse", path)
                if m:
                    return self._send(200, analyse_session(m.group(1)))
                if path == "/api/reanalyse":
                    with lock, db() as c:
                        ids = [r[0] for r in c.execute("SELECT id FROM sessions").fetchall()]
                    return self._send(200, {"sessions": [analyse_session(i) for i in ids]})
                if path == "/api/verdicts":
                    return self._send(200, add_verdict(json.loads(self._body() or b"{}")))
                m = re.fullmatch(r"/api/tracks/(.+)/forget", path)
                if m:
                    return self._send(200, forget_track(unquote(m.group(1))))
            if method == "DELETE":
                m = re.fullmatch(r"/api/sessions/([A-Za-z0-9_-]{6,64})", path)
                if m:
                    sid = m.group(1)
                    with lock, db() as c:
                        for table in ("sessions", "chunks", "events", "plays", "verdicts"):
                            c.execute(f"DELETE FROM {table} WHERE {'id' if table == 'sessions' else 'session_id'}=?", (sid,))
                    folder = DATA / "sessions" / sid
                    for f in folder.glob("*"):
                        f.unlink()
                    if folder.exists():
                        folder.rmdir()
                    cache.pop(sid, None)
                    return self._send(200, {"ok": True})
                m = re.fullmatch(r"/api/verdicts/(\d+)", path)
                if m:
                    with lock, db() as c:
                        c.execute("DELETE FROM verdicts WHERE id=?", (int(m.group(1)),))
                    return self._send(200, {"ok": True})
            return self._send(404, {"error": "introuvable"})
        except Exception as e:  # noqa: BLE001
            traceback.print_exc()
            return self._send(500, {"error": str(e)})

    def _file(self, p):
        p = p.resolve()
        if STATIC.resolve() not in p.parents and p != STATIC.resolve() or not p.is_file():
            return self._send(404, {"error": "introuvable"})
        ctype = {".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".css": "text/css; charset=utf-8",
                 ".woff2": "font/woff2", ".ttf": "font/ttf", ".svg": "image/svg+xml"}.get(p.suffix, "application/octet-stream")
        return self._send(200, p.read_bytes(), ctype)

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_DELETE(self):
        self._route("DELETE")


def watcher():
    """Analyse les sessions restées sans fin (téléphone éteint, appli tuée) dix minutes après leur dernière tranche."""
    while True:
        time.sleep(300)
        try:
            limit = int(time.time() * 1000) - 10 * 60 * 1000
            with lock, db() as c:
                todo = [r[0] for r in c.execute(
                    "SELECT s.id FROM sessions s WHERE s.ended_at IS NULL AND "
                    "(SELECT MAX(received_at) FROM chunks k WHERE k.session_id=s.id) < ? AND "
                    "COALESCE(s.analysed_at, 0) < (SELECT MAX(received_at) FROM chunks k WHERE k.session_id=s.id)", (limit,)).fetchall()]
            for sid in todo:
                print(f"session {sid} sans fin, analyse", flush=True)
                analyse_session(sid)
        except Exception:  # noqa: BLE001
            traceback.print_exc()


def main():
    init_db()
    threading.Thread(target=watcher, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"drop : http://127.0.0.1:{PORT} (données dans {DATA})", flush=True)
    srv.serve_forever()


if __name__ == "__main__":
    main()
