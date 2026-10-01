// Dashboard d'admin de Drop : sessions reçues du téléphone, frise détaillée avec les drops (direct, analyse,
// verdicts), mémoire des morceaux, et réglage (la règle actuelle rejouée contre les verdicts de Louis).
'use strict';

const $ = (s, el = document) => el.querySelector(s);
const app = $('#app');
const tip = $('#tip');
const css = getComputedStyle(document.documentElement);
const C = Object.fromEntries(['bg', 'surface', 'plan', 'line', 'line2', 'repere', 'neutre', 'text', 'muted', 'dim', 'live', 'yes', 'no']
  .map(k => [k, css.getPropertyValue('--' + k).trim()]));

async function api(path, opts = {}) {
  const r = await fetch(path, opts);
  const body = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(body.error || `erreur ${r.status}`);
  return body;
}
const post = (path, body) => api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
const mmss = s => {
  if (s == null || isNaN(s)) return '–';
  const neg = s < 0; s = Math.abs(s);
  return (neg ? '-' : '') + Math.floor(s / 60) + ':' + String(Math.floor(s % 60)).padStart(2, '0');
};
const duree = s => !s ? '–' : s >= 3600 ? `${Math.floor(s / 3600)} h ${String(Math.floor(s % 3600 / 60)).padStart(2, '0')}`
  : s >= 60 ? `${Math.floor(s / 60)} min ${String(Math.floor(s % 60)).padStart(2, '0')}` : `${Math.round(s)} s`;
const quand = ms => ms ? new Date(+ms).toLocaleString('fr-FR', { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' }) : '–';
const esc = s => String(s ?? '').replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
let toastTimer;
function toast(msg) {
  const t = $('#toast'); t.textContent = msg; t.style.display = 'block';
  clearTimeout(toastTimer); toastTimer = setTimeout(() => (t.style.display = 'none'), 3500);
}

const KIND = {
  drop: 'Drop en direct', drop_start: 'Explosion', drop_cancel: 'Pas confirmé', mem_drop: 'Drop mémorisé, entendu',
  mem_miss: 'Drop mémorisé, pas entendu', mem_skip: 'Retour des basses hors mémoire, ignoré', memory: 'Mémoire du morceau',
  seek: 'Saut dans le morceau', realign: 'Recalage de la phrase', title: 'Morceau',
};

// ─── Routeur ──────────────────────────────────────────────────────────────────

let cleanup = null;
async function route() {
  if (cleanup) { cleanup(); cleanup = null; }
  const h = location.hash.replace(/^#/, '') || '/';
  document.querySelectorAll('nav a').forEach(a => a.classList.toggle('on',
    (a.dataset.v === 'sessions' && (h === '/' || h.startsWith('/s/'))) || h.startsWith('/' + a.dataset.v)));
  app.innerHTML = '<p class="sub">Chargement…</p>';
  try {
    if (h.startsWith('/s/')) {
      const [id, at] = h.slice(3).split('@');
      await vueSession(decodeURIComponent(id), at ? parseFloat(at) : null);
    } else if (h.startsWith('/morceaux')) await vueMorceaux();
    else if (h.startsWith('/reglage')) await vueReglage();
    else await vueSessions();
  } catch (e) {
    app.innerHTML = `<div class="card empty">Impossible de charger : ${esc(e.message)}</div>`;
  }
}
window.addEventListener('hashchange', route);
api('/api/apk-info').then(i => { if (i.exists) { const a = $('#apk'); a.style.display = ''; a.title = `Version du ${quand(i.mtime)}`; } }).catch(() => {});
route();

// ─── Sessions ─────────────────────────────────────────────────────────────────

async function vueSessions() {
  const list = await api('/api/sessions');
  if (!list.length) {
    app.innerHTML = `<h1>Sessions</h1><div class="card empty">Aucune session pour l'instant.<br>Elles arrivent ici toutes seules pendant le show, tranche par tranche.</div>`;
    return;
  }
  app.innerHTML = `<h1>Sessions</h1><p class="sub">Chaque show envoyé par le téléphone. Un morceau en blanc a été entendu en entier : il alimente la mémoire.</p>
    <div class="list">${list.map(s => `
      <a class="card row" href="#/s/${encodeURIComponent(s.id)}">
        <div><div>${quand(s.started_at || s.created_at)}</div><div class="sub mono" style="font-size:12px">${esc(s.app || '')}</div></div>
        <div class="mono">${duree(s.duration)}</div>
        <div class="chips">${s.tracks.map(t => `<span class="chip ${t.complete ? 'full' : ''}" title="${t.complete ? 'Entendu en entier' : 'Écoute partielle'}">${esc(t.title || t.key)}</span>`).join('') || '<span class="sub">Aucun morceau reconnu</span>'}</div>
        <div class="sub">${s.drops} drop${s.drops > 1 ? 's' : ''} en direct${s.ended_at ? '' : ' · <span style="color:var(--live)">en cours</span>'}</div>
      </a>`).join('')}</div>`;
}

// ─── Une session ──────────────────────────────────────────────────────────────

async function vueSession(id, focus) {
  const d = await api(`/api/sessions/${encodeURIComponent(id)}`);
  const s = d.session;
  const T = { t0: 0, t1: Math.max(1, s.last_t || 1) };
  const st = { d, view: { from: T.t0, to: T.t1 }, series: null, over: null, sel: null, hover: null };
  if (focus != null) { st.view = { from: Math.max(T.t0, focus - 15), to: Math.min(T.t1, focus + 15) }; st.sel = focus; }
  else if (T.t1 > 600) st.view = { from: T.t0, to: T.t0 + 120 };

  app.innerHTML = `
    <a class="back" href="#/">← Sessions</a>
    <div class="top" style="margin-top:8px"><div class="grow"><h1>${quand(s.started_at || s.created_at)}</h1>
      <div class="sub">${duree(s.last_t)} · ${esc(s.device || 'téléphone')} · ${esc(s.app || '')} · ${d.plays.length} morceau${d.plays.length > 1 ? 'x' : ''}</div></div>
      <button class="btn" id="reanalyse">Réanalyser</button><button class="btn no" id="suppr">Supprimer</button></div>
    <div class="grid2">
      <div class="card tl">
        <div class="tlhead"><div class="seg" id="zoom"><button data-z="30">30 s</button><button data-z="120">2 min</button><button data-z="track">Morceau</button><button data-z="all">Tout</button></div>
          <span class="hint">Molette pour zoomer, glisser pour se déplacer, toucher pour choisir un moment.</span></div>
        <canvas id="overview"></canvas>
        <canvas id="detail"></canvas>
      </div>
      <div class="card panel" id="side"></div>
    </div>
    <h2 style="margin:24px 0 8px">Événements</h2>
    <div class="card" id="events"></div>`;

  const ov = $('#overview'), dt = $('#detail');
  const markers = buildMarkers(d);

  // Courbe de toute la session pour la vue d'ensemble, une fois.
  api(`/api/sessions/${encodeURIComponent(id)}/series?from=${T.t0}&to=${T.t1}&n=600`).then(r => { st.over = r.series; draw(); });
  let timer;
  function fetchView() {
    clearTimeout(timer);
    timer = setTimeout(async () => {
      const n = Math.max(200, Math.round(dt.clientWidth / 2));
      const v = { ...st.view };
      const r = await api(`/api/sessions/${encodeURIComponent(id)}/series?from=${v.from}&to=${v.to}&n=${n}`);
      if (v.from === st.view.from && v.to === st.view.to) { st.series = r; draw(); }
    }, 90);
  }
  function setView(from, to) {
    const span = Math.max(4, Math.min(T.t1 - T.t0, to - from));
    from = Math.max(T.t0, Math.min(from, T.t1 - span));
    st.view = { from, to: from + span };
    draw(); fetchView();
  }
  const playAt = t => d.plays.find(p => t >= p.t0 && t < p.t1);

  $('#zoom').addEventListener('click', e => {
    const z = e.target.dataset.z; if (!z) return;
    const c = st.sel ?? (st.view.from + st.view.to) / 2;
    if (z === 'all') setView(T.t0, T.t1);
    else if (z === 'track') { const p = playAt(c); if (p) setView(p.t0, p.t1); }
    else setView(c - z / 2, c + z / 2);
  });
  $('#reanalyse').onclick = async () => { await post(`/api/sessions/${encodeURIComponent(id)}/analyse`); toast('Session réanalysée'); route(); };
  $('#suppr').onclick = async () => {
    if (!confirm('Supprimer cette session et ses verdicts ? C\'est définitif.')) return;
    await api(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' }); location.hash = '#/';
  };

  // ─ Dessin
  const L = { left: 70, right: 12, marks: [0, 58], band: [62, 84], bass: [92, 268], level: [276, 334], beats: [342, 372], axis: [378, 398], pos: [402, 420] };
  function setup(cv) {
    const r = window.devicePixelRatio || 1;
    const w = cv.clientWidth, h = cv.clientHeight;
    if (cv.width !== Math.round(w * r) || cv.height !== Math.round(h * r)) { cv.width = Math.round(w * r); cv.height = Math.round(h * r); }
    const g = cv.getContext('2d'); g.setTransform(r, 0, 0, r, 0, 0); g.clearRect(0, 0, w, h);
    return [g, w, h];
  }
  const X = (t, w) => L.left + (t - st.view.from) / (st.view.to - st.view.from) * (w - L.left - L.right);
  const tAt = (x, w) => st.view.from + (x - L.left) / (w - L.left - L.right) * (st.view.to - st.view.from);

  function draw() { drawOverview(); drawDetail(); side(); }

  function drawOverview() {
    const [g, w, h] = setup(ov);
    const x = t => 16 + (t - T.t0) / (T.t1 - T.t0) * (w - 32);
    g.fillStyle = C.plan; g.fillRect(16, 4, w - 32, h - 8);
    const sr = st.over;
    if (sr && sr.t.length) {
      const lo = Math.min(...sr.bass), hi = Math.max(...sr.bass);
      g.fillStyle = C.live + 'aa';
      sr.t.forEach((t, i) => { const v = (sr.bass[i] - lo) / Math.max(1, hi - lo); g.fillRect(x(t), h - 4 - v * (h - 10), 1.5, v * (h - 10)); });
    }
    for (const p of d.plays) { g.fillStyle = C.line2; g.fillRect(x(p.t0), 4, 1, h - 8); }
    g.strokeStyle = C.text; g.lineWidth = 1.5;
    g.strokeRect(x(st.view.from), 2, Math.max(3, x(st.view.to) - x(st.view.from)), h - 4);
  }

  function drawDetail() {
    const [g, w, h] = setup(dt);
    const sr = st.series && st.series.series;
    g.font = '11px Geist'; g.textBaseline = 'top';
    // Gouttière
    g.fillStyle = C.dim;
    [['Repères', L.marks[0] + 4], ['Morceau', L.band[0] + 5], ['Basses', L.bass[0]], ['Niveau', L.level[0]], ['Temps', L.beats[0] + 8]]
      .forEach(([s, y]) => g.fillText(s, 8, y));
    // Fonds
    g.fillStyle = C.plan;
    [L.bass, L.level, L.beats].forEach(([a, b]) => g.fillRect(L.left, a, w - L.left - L.right, b - a));
    // Morceaux
    for (const p of d.plays) {
      const a = Math.max(L.left, X(p.t0, w)), b = Math.min(w - L.right, X(p.t1, w));
      if (b <= a) continue;
      g.fillStyle = p.complete ? '#26262b' : '#18181b'; g.fillRect(a, L.band[0], b - a - 1, L.band[1] - L.band[0]);
      g.save(); g.beginPath(); g.rect(a, L.band[0], b - a - 4, 22); g.clip();
      g.fillStyle = p.complete ? C.text : C.muted; g.fillText(`${p.title || p.track_key}${p.complete ? '' : ' (partiel)'}`, a + 6, L.band[0] + 5);
      g.restore();
    }
    // Mesures et temps
    const beats = (st.series && st.series.beats) || [];
    const span = st.view.to - st.view.from;
    for (const [t, pos] of beats) {
      const x = X(t, w); if (x < L.left || x > w - L.right) continue;
      const bar = pos % 4 === 0, phrase = pos === 0;
      if (span <= 90 || bar) {
        g.fillStyle = phrase ? C.text : bar ? C.muted : C.neutre;
        const hh = phrase ? 18 : bar ? 11 : 5;
        g.fillRect(x, L.beats[1] - hh, phrase ? 2 : 1, hh);
        if (bar && span <= 45) { g.fillStyle = phrase ? C.text : C.dim; g.font = '10px GeistMono'; g.fillText(String(pos / 4 + 1), x + 3, L.beats[0] + 1); g.font = '11px Geist'; }
      }
      if (bar && span <= 120) { g.fillStyle = phrase ? '#3a3a40' : '#1d1d21'; g.fillRect(x, L.bass[0], 1, L.level[1] - L.bass[0]); }
    }
    // Basses et niveau
    if (sr && sr.t.length) {
      const vals = sr.bass.slice().sort((a, b) => a - b);
      const lo = vals[Math.floor(vals.length * 0.02)] - 2, hi = vals[vals.length - 1] + 2;
      const y = v => L.bass[1] - Math.max(0, Math.min(1, (v - lo) / (hi - lo))) * (L.bass[1] - L.bass[0] - 4);
      const cw = Math.max(1, (w - L.left - L.right) / sr.t.length);
      g.fillStyle = C.live + '99';
      sr.t.forEach((t, i) => { const x = X(t, w); const yy = y(sr.bass[i]); g.fillRect(x - cw / 2, yy, Math.max(1, cw - 0.5), L.bass[1] - yy); });
      g.strokeStyle = C.text; g.lineWidth = 1.2; g.beginPath();
      sr.t.forEach((t, i) => { const x = X(t, w), yy = y(sr.bassmid[i]); i ? g.lineTo(x, yy) : g.moveTo(x, yy); }); g.stroke();
      g.fillStyle = C.dim; g.font = '10px GeistMono';
      g.fillText(`${Math.round(hi)} dB`, L.left + 4, L.bass[0] + 2); g.fillText(`${Math.round(lo)} dB`, L.left + 4, L.bass[1] - 13); g.font = '11px Geist';
      g.fillStyle = '#8e8e96aa';
      sr.t.forEach((t, i) => { const x = X(t, w); const hh = Math.max(0, Math.min(1, sr.level[i])) * (L.level[1] - L.level[0] - 2); g.fillRect(x - cw / 2, L.level[1] - hh, Math.max(1, cw - 0.5), hh); });
    }
    // Repères : traits sur les courbes, étiquettes sur trois lignes sans chevauchement.
    const ends = [-1e9, -1e9, -1e9];
    for (const m of markers) {
      const x = X(m.t, w); if (x < L.left - 1 || x > w - L.right + 1) continue;
      g.strokeStyle = m.color; g.lineWidth = m.width; g.setLineDash(m.dash || []);
      g.beginPath(); g.moveTo(x, L.marks[1] - 6); g.lineTo(x, L.level[1]); g.stroke(); g.setLineDash([]);
      const tw = g.measureText(m.short).width;
      const row = ends.findIndex(e => x > e + 6);
      if (row >= 0) { g.fillStyle = m.color; g.fillText(m.short, Math.min(x + 3, w - L.right - tw), L.marks[0] + 2 + row * 17); ends[row] = x + 3 + tw; }
    }
    // Choix et survol
    if (st.sel != null) { const x = X(st.sel, w); g.fillStyle = C.text; g.fillRect(x - 0.5, L.band[0], 1.5, L.pos[1] - L.band[0]); }
    if (st.hover != null) { const x = X(st.hover, w); g.fillStyle = '#ffffff40'; g.fillRect(x, L.band[0], 1, L.level[1] - L.band[0]); }
    // Axes : temps de la session, et position dans le morceau
    g.font = '10px GeistMono'; g.fillStyle = C.dim;
    const step = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600].find(s => span / s <= 10) || 1200;
    for (let t = Math.ceil(st.view.from / step) * step; t <= st.view.to; t += step) {
      const x = X(t, w); g.fillRect(x, L.axis[0], 1, 4); g.fillText(mmss(t), x - 12, L.axis[0] + 6);
    }
    g.fillText('session', 8, L.axis[0] + 6); g.fillText('morceau', 8, L.pos[0] + 4);
    for (const p of d.plays) {
      for (let pos = Math.ceil((st.view.from + p.offset) / step) * step; pos - p.offset <= st.view.to; pos += step) {
        const t = pos - p.offset; if (t < p.t0 || t >= p.t1) continue;
        const x = X(t, w); g.fillStyle = C.muted; g.fillText(mmss(pos), x - 12, L.pos[0] + 4);
      }
    }
  }

  // ─ Panneau : moment choisi, verdicts, légende
  function side() {
    const el = $('#side');
    const legend = `<div class="label">Légende</div>
      <div class="chips"><span class="chip drop">│ drop en direct</span><span class="chip">┊ drop d'après l'analyse</span><span class="chip yes">│ ton verdict : drop</span><span class="chip no">│ ton verdict : pas un drop</span></div>
      <div class="hint">Barres orangées : énergie des basses. Trait blanc : basses lissées (ce que voit la détection). Gris : niveau du son.</div>`;
    let selHtml = '<div class="hint">Touche la frise pour choisir un moment, puis dis si c\'est un drop.</div>';
    if (st.sel != null) {
      const p = playAt(st.sel);
      const near = markers.filter(m => Math.abs(m.t - st.sel) <= 2).sort((a, b) => Math.abs(a.t - st.sel) - Math.abs(b.t - st.sel));
      selHtml = `<div class="sel"><div><div class="label">Moment choisi</div>
          <div style="font-size:18px;font-weight:600">${p ? `${esc(p.title)} · ${mmss(st.sel + p.offset)}` : mmss(st.sel)}</div>
          <div class="sub mono" style="font-size:12px">session ${mmss(st.sel)}</div></div>
        ${near.length ? `<div class="chips">${near.map(m => `<span class="chip" style="color:${m.color}">${esc(m.label)} · ${mmss(p ? m.t + p.offset : m.t)}</span>`).join('')}</div>` : ''}
        <div style="display:flex;gap:8px;flex-wrap:wrap"><button class="btn yes" id="vyes">C'est un drop</button><button class="btn no" id="vno">Pas un drop</button></div>
        <div class="hint">Le verdict se cale sur le temps le plus proche et s'applique au morceau : la mémoire et le réglage en tiennent compte.</div></div>`;
    }
    const vs = d.verdicts.map(v => `<tr><td class="k mono">${mmss(v.pos ?? v.t)}</td><td class="${v.verdict === 'drop' ? 'ok' : 'ko'}">${v.verdict === 'drop' ? 'Drop' : 'Pas un drop'}</td>
      <td style="text-align:right"><a href="#" data-del="${v.id}" class="back">retirer</a></td></tr>`).join('');
    el.innerHTML = `${selHtml}<div class="sel"><div class="label">Tes verdicts dans cette session</div>${vs ? `<table>${vs}</table>` : '<div class="hint">Aucun pour l\'instant.</div>'}</div><div class="sel">${legend}</div>`;
    const snap = () => {
      // Cale le verdict sur un drop proche (direct ou analyse), sinon sur le temps le plus proche.
      const m = markers.filter(m => m.kind !== 'verdict' && Math.abs(m.t - st.sel) <= 1.5).sort((a, b) => Math.abs(a.t - st.sel) - Math.abs(b.t - st.sel))[0];
      if (m) return m.t;
      const bs = (st.series && st.series.beats) || [];
      let best = st.sel, bd = 1e9;
      for (const [t] of bs) if (Math.abs(t - st.sel) < bd) { bd = Math.abs(t - st.sel); best = t; }
      return bd < 0.6 ? best : st.sel;
    };
    const vote = async verdict => {
      const t = snap();
      const r = await post('/api/verdicts', { session_id: id, t, verdict });
      toast(`${verdict === 'drop' ? 'Drop' : 'Pas un drop'} noté${r.pos != null ? ` à ${mmss(r.pos)}` : ''}`);
      d.verdicts = (await api(`/api/sessions/${encodeURIComponent(id)}`)).verdicts;
      markers.splice(0, markers.length, ...buildMarkers(d)); draw();
    };
    if ($('#vyes')) { $('#vyes').onclick = () => vote('drop'); $('#vno').onclick = () => vote('not_drop'); }
    el.querySelectorAll('[data-del]').forEach(a => a.onclick = async e => {
      e.preventDefault(); await api(`/api/verdicts/${a.dataset.del}`, { method: 'DELETE' });
      d.verdicts = d.verdicts.filter(v => String(v.id) !== a.dataset.del); markers.splice(0, markers.length, ...buildMarkers(d)); draw();
    });
  }

  // ─ Interactions
  let drag = null;
  dt.addEventListener('pointerdown', e => { drag = { x: e.clientX, from: st.view.from, to: st.view.to, moved: false }; dt.setPointerCapture(e.pointerId); });
  dt.addEventListener('pointermove', e => {
    const r = dt.getBoundingClientRect(); const x = e.clientX - r.left;
    if (drag) {
      const dx = e.clientX - drag.x;
      if (Math.abs(dx) > 4) drag.moved = true;
      if (drag.moved) { const dtSec = -dx / (r.width - L.left - L.right) * (drag.to - drag.from); setView(drag.from + dtSec, drag.to + dtSec); }
      return;
    }
    if (x < L.left) { tip.style.display = 'none'; st.hover = null; drawDetail(); return; }
    const t = tAt(x, r.width); st.hover = t; drawDetail();
    const p = playAt(t); const sr = st.series && st.series.series;
    let info = '';
    if (sr && sr.t.length) { let i = 0, b = 1e9; sr.t.forEach((tt, k) => { if (Math.abs(tt - t) < b) { b = Math.abs(tt - t); i = k; } }); info = ` · basses ${sr.bass[i]} dB · ${Math.round(sr.bpm[i])} BPM`; }
    tip.innerHTML = `${p ? `${esc(p.title)} <b>${mmss(t + p.offset)}</b> · ` : ''}session ${mmss(t)}${info}`;
    tip.style.display = 'block'; tip.style.left = Math.min(e.clientX + 12, innerWidth - tip.offsetWidth - 8) + 'px'; tip.style.top = (e.clientY + 14) + 'px';
  });
  dt.addEventListener('pointerleave', () => { tip.style.display = 'none'; st.hover = null; drawDetail(); });
  dt.addEventListener('pointerup', e => {
    const r = dt.getBoundingClientRect();
    if (drag && !drag.moved && e.clientX - r.left >= L.left) { st.sel = tAt(e.clientX - r.left, r.width); draw(); }
    drag = null;
  });
  dt.addEventListener('wheel', e => {
    e.preventDefault();
    const r = dt.getBoundingClientRect(); const t = tAt(e.clientX - r.left, r.width);
    const k = Math.exp(e.deltaY * 0.0015);
    setView(t - (t - st.view.from) * k, t + (st.view.to - t) * k);
  }, { passive: false });
  let odrag = false;
  const ovMove = e => { const r = ov.getBoundingClientRect(); const t = T.t0 + (e.clientX - r.left - 16) / (r.width - 32) * (T.t1 - T.t0); const span = st.view.to - st.view.from; setView(t - span / 2, t + span / 2); };
  ov.addEventListener('pointerdown', e => { odrag = true; ov.setPointerCapture(e.pointerId); ovMove(e); });
  ov.addEventListener('pointermove', e => { if (odrag) ovMove(e); });
  ov.addEventListener('pointerup', () => (odrag = false));
  const onResize = () => draw();
  window.addEventListener('resize', onResize);
  cleanup = () => window.removeEventListener('resize', onResize);

  // ─ Événements
  const evs = d.events.filter(e => KIND[e.kind]);
  $('#events').innerHTML = evs.length ? `<table><tr><th>Session</th><th>Morceau</th><th>Événement</th><th>Détail</th></tr>${evs.map(e => {
    const p = playAt(e.t);
    return `<tr><td class="k mono"><a href="#/s/${encodeURIComponent(id)}@${e.t.toFixed(2)}" class="back">${mmss(e.t)}</a></td>
      <td class="k">${p ? `${esc(p.title)} ${mmss(e.t + p.offset)}` : '–'}</td><td>${KIND[e.kind]}</td><td class="sub">${esc(e.detail)}</td></tr>`;
  }).join('')}</table>` : '<div class="empty">Aucun événement.</div>';

  draw(); fetchView();
}

function buildMarkers(d) {
  const out = [];
  for (const e of d.events) {
    if (e.kind === 'drop') out.push({ t: e.t, kind: 'live', color: C.live, width: 2, short: 'Drop', label: 'Drop en direct' });
    else if (e.kind === 'mem_drop') out.push({ t: e.t, kind: 'live', color: C.live, width: 2, short: 'Drop (mémoire)', label: 'Drop mémorisé, entendu' });
    else if (e.kind === 'drop_cancel') out.push({ t: e.t, kind: 'cancel', color: C.muted, width: 1, dash: [2, 3], short: 'Pas confirmé', label: 'Explosion non confirmée' });
    else if (e.kind === 'mem_miss') out.push({ t: e.t, kind: 'cancel', color: C.no, width: 1, dash: [2, 3], short: 'Mémoire non entendue', label: 'Drop mémorisé pas entendu' });
    else if (e.kind === 'mem_skip') out.push({ t: e.t, kind: 'cancel', color: C.dim, width: 1, dash: [1, 3], short: 'Ignoré (hors mémoire)', label: 'Retour des basses hors mémoire, ignoré' });
  }
  for (const p of d.plays) for (const x of p.drops) out.push({ t: x.t, kind: 'retro', color: C.text, width: 1.2, dash: [4, 3], short: 'Analyse', label: `Drop d'après l'analyse (+${x.rise} dB)` });
  for (const v of d.verdicts) out.push({ t: v.t, kind: 'verdict', color: v.verdict === 'drop' ? C.yes : C.no, width: 2.5, short: v.verdict === 'drop' ? 'Drop ✓' : 'Pas un drop ✗', label: v.verdict === 'drop' ? 'Ton verdict : drop' : 'Ton verdict : pas un drop' });
  return out.sort((a, b) => a.t - b.t);
}

// ─── Morceaux et mémoire ──────────────────────────────────────────────────────

async function vueMorceaux() {
  const list = await api('/api/tracks');
  if (!list.length) { app.innerHTML = '<h1>Morceaux</h1><div class="card empty">Aucun morceau encore.</div>'; return; }
  list.sort((a, b) => (b.memory.complete_plays - a.memory.complete_plays) || String(a.title).localeCompare(b.title));
  app.innerHTML = `<h1>Morceaux</h1>
    <p class="sub">Ce que Drop retient de chaque morceau entendu en entier. Aux écoutes suivantes, un drop mémorisé n'est joué que si le son le confirme ; ailleurs, plus de faux drop.</p>
    <div class="list">${list.map(t => {
      const m = t.memory, du = t.duration || Math.max(60, ...m.drops.map(x => x.pos + 10));
      const known = m.complete_plays >= 1 || m.drops.some(x => x.source === 'verdict');
      return `<div class="card track">
        <div class="trackhead"><div class="grow"><h2>${esc(t.title)}</h2><div class="sub">${esc(t.artist)} · ${t.duration ? mmss(t.duration) : 'durée inconnue'}</div></div>
          <span class="chip ${known ? 'full' : ''}">${known ? 'Connu' : 'Pas encore connu'} · ${m.complete_plays} écoute${m.complete_plays > 1 ? 's' : ''} complète${m.complete_plays > 1 ? 's' : ''} sur ${t.plays}</span>
          <button class="btn" data-forget="${esc(t.key)}">Oublier ce morceau</button></div>
        <div class="mini">${m.drops.map(x => `<i class="${x.status === 'drop' ? '' : x.status === 'rejeté' ? 'no' : 'off'}" style="left:${x.pos / du * 100}%"></i><b style="left:${x.pos / du * 100}%">${mmss(x.pos)}</b>`).join('')}</div>
        <div class="chips">${m.drops.length ? m.drops.map(x => `<span class="chip ${x.status === 'drop' ? 'drop' : x.status === 'rejeté' ? 'no' : ''}">${mmss(x.pos)} · ${x.status === 'drop' ? 'drop' : x.status}${x.source === 'verdict' ? ' (ton verdict)' : ` · vu ${x.seen}/${x.seen + x.missed}`}${x.verdict && x.source !== 'verdict' ? ` · verdict ${x.verdict === 'drop' ? '✓' : '✗'}` : ''}</span>`).join('') : '<span class="sub">Aucun drop retenu.</span>'}</div>
        <details><summary class="sub" style="cursor:pointer">Écoutes</summary><table style="margin-top:8px"><tr><th>Quand</th><th>Écoute</th><th>Drops trouvés par l'analyse</th></tr>
          ${t.history.map(p => `<tr><td class="k"><a class="back" href="#/s/${encodeURIComponent(p.session_id)}@${p.t0.toFixed(1)}">${quand(p.played_at)}</a></td>
            <td class="k">${p.complete ? 'Complète' : `Partielle (${mmss(p.pos0)} → ${mmss(p.pos1)})`}</td>
            <td>${p.drops.map(x => `<a class="chip" style="text-decoration:none" href="#/s/${encodeURIComponent(p.session_id)}@${x.t.toFixed(2)}">${mmss(x.pos)}</a>`).join(' ') || '<span class="sub">aucun</span>'}</td></tr>`).join('')}
        </table></details></div>`;
    }).join('')}</div>`;
  app.querySelectorAll('[data-forget]').forEach(b => b.onclick = async () => {
    if (!confirm('Oublier ce morceau ? Ses écoutes passées et tes verdicts ne compteront plus, Drop le réapprendra à la prochaine écoute complète.')) return;
    await post(`/api/tracks/${encodeURIComponent(b.dataset.forget)}/forget`); toast('Morceau oublié'); route();
  });
}

// ─── Réglage : la règle actuelle contre les verdicts ──────────────────────────

async function vueReglage() {
  const r = await api('/api/calibration');
  const mark = v => v == null ? '<span class="na">–</span>' : v ? '<span class="ok">✓</span>' : '<span class="ko">✗</span>';
  app.innerHTML = `<h1>Réglage des drops</h1>
    <p class="sub">Chaque verdict que tu donnes devient un test. « Analyse » : la règle actuelle appliquée au morceau entier (ce qui nourrit la mémoire). « Direct » : ce que le show a fait sur le moment.</p>
    <div class="top" style="margin:12px 0"><div class="grow" style="font-size:18px;font-weight:600">${r.total ? `${r.retro_ok} verdict${r.retro_ok > 1 ? 's' : ''} sur ${r.total} respecté${r.retro_ok > 1 ? 's' : ''} par l'analyse` : 'Aucun verdict pour l\'instant'}</div>
      <button class="btn" id="all">Réanalyser toutes les sessions</button></div>
    <div class="card">${r.rows.length ? `<table><tr><th>Morceau</th><th>Position</th><th>Ton verdict</th><th>Analyse</th><th>Direct</th></tr>
      ${r.rows.map(x => `<tr><td><a class="back" href="#/s/${encodeURIComponent(x.session_id)}@${x.t.toFixed(2)}">${esc(x.title || '?')}</a></td><td class="mono">${mmss(x.pos)}</td>
        <td class="${x.verdict === 'drop' ? 'ok' : 'ko'}">${x.verdict === 'drop' ? 'Drop' : 'Pas un drop'}</td><td>${mark(x.retro_ok)}</td><td>${mark(x.live_ok)}</td></tr>`).join('')}</table>`
      : '<div class="empty">Ouvre une session, touche un moment de la frise et dis si c\'est un drop.</div>'}</div>`;
  $('#all').onclick = async () => { $('#all').disabled = true; await post('/api/reanalyse'); toast('Toutes les sessions sont réanalysées'); route(); };
}
