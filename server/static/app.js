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
    } else if (h.startsWith('/direct')) await vueDirect();
    else if (h.startsWith('/morceaux')) await vueMorceaux();
    else if (h.startsWith('/reglage')) await vueReglage();
    else await vueSessions();
  } catch (e) {
    app.innerHTML = `<div class="card empty">Impossible de charger : ${esc(e.message)}</div>`;
  }
}
window.addEventListener('hashchange', route);
api('/api/apk-info').then(i => { if (i.exists) { const a = $('#apk'); a.style.display = ''; a.title = `Version du ${quand(i.mtime)}`; } }).catch(() => {});
route();

// Pastille « Direct » : allumée tant que le téléphone envoie un show en cours.
const enDirect = l => l.state && l.state.running && l.age_ms != null && l.age_ms < 5000;
async function pastille() {
  try { const l = await api('/api/live'); $('#livedot').classList.toggle('on', enDirect(l)); teinte(l); } catch (e) { /* hors ligne */ }
}
pastille(); setInterval(pastille, 3000);
// Le fond prend les couleurs de la pochette pendant le show, et revient au gris à l'arrêt.
function teinte(l) {
  const s = l && l.state, on = enDirect(l);
  const ok = c => c && c !== '#000000';
  document.body.style.setProperty('--c1', on && ok(s.lead) ? s.lead : '#3a3a48');
  document.body.style.setProperty('--c2', on && ok(s.second) ? s.second : '#26323a');
}

// ─── Direct ───────────────────────────────────────────────────────────────────

async function vueDirect() {
  let stop = false, raf = 0;
  cleanup = () => { stop = true; cancelAnimationFrame(raf); };
  let fen = 30, fige = null, key = null, sinceMark = 0;
  const tl = { t: [], level: [], bass: [], tension: [], intensity: [], mode: [], fx: [], lamps: {} };
  const marks = [];
  let st = null, age = null, recu = 0, tRecu = 0;
  app.innerHTML = `<div class="top"><h1 style="flex:1">Direct</h1>
      <div class="seg lens" id="fen"><button data-f="30" class="on">30 s</button><button data-f="120">2 min</button></div></div>
    <p class="sub" id="lsub">En attente du téléphone…</p>
    <div id="lres" class="card panel" style="margin-top:12px;flex-direction:row;flex-wrap:wrap;align-items:center;gap:8px 16px"></div>
    <div class="live">
      <div class="room" id="room"></div>
      <div class="card panel">
        <div><div class="label">Morceau</div><div id="ltrack" style="font-size:17px;font-weight:600">–</div><div class="sub" id="lartist"></div></div>
        <div class="sub" id="lmem"></div>
        <div class="kv">
          <div><span class="label">BPM</span><b id="lbpm">–</b></div>
          <div><span class="label">Mesure</span><b id="lbar">–</b></div>
          <div><span class="label">Drops</span><b id="ldrops">–</b></div>
        </div>
        <div class="beats" id="lbeats">${'<i></i>'.repeat(4)}</div>
        <div><div class="label">Montée</div><div class="meter"><i id="lten"></i></div></div>
        <div><div class="label">Niveau</div><div class="meter"><i id="llev" style="background:var(--text)"></i></div></div>
      </div>
    </div>
    <div class="card" style="margin-top:16px;overflow:hidden"><canvas id="lgraph" style="height:620px;cursor:pointer"></canvas></div>
    <p class="hint" id="lhint" style="margin-top:8px">Clique sur le graphe pour le figer.</p>`;
  document.querySelectorAll('#fen button').forEach(b => b.onclick = () => {
    fen = +b.dataset.f; document.querySelectorAll('#fen button').forEach(x => x.classList.toggle('on', x === b));
  });
  const cv = $('#lgraph');
  cv.onclick = () => { fige = fige == null ? present() : null; $('#lhint').textContent = fige == null ? 'Clique sur le graphe pour le figer.' : 'Figé. Clique pour reprendre.'; };
  let planCle = '';

  function present() {
    const n = tl.t.length; if (!n) return 0;
    const last = tl.t[n - 1];
    return st && st.running && age != null && age < 5000 ? last + Math.min(0.6, (performance.now() - tRecu) / 1000) : last;
  }

  function ajoute(l) {
    if (l.key !== key) { key = l.key; for (const c in tl) if (c !== 'lamps') tl[c].length = 0; tl.lamps = {}; marks.length = 0; sinceMark = 0; }
    const x = l.timeline || {}, ts = x.t || [];
    if (ts.length && tl.t.length && ts[0] < tl.t[tl.t.length - 1] - 5) { for (const c in tl) if (c !== 'lamps') tl[c].length = 0; tl.lamps = {}; marks.length = 0; }
    const n0 = tl.t.length;
    for (const c of ['t', 'level', 'bass', 'tension', 'intensity', 'mode', 'fx']) tl[c].push(...(x[c] || []));
    for (const [ch, a] of Object.entries(x.lamps || {})) {
      if (!tl.lamps[ch]) tl.lamps[ch] = new Array(n0).fill(null);
      tl.lamps[ch].push(...a);
    }
    for (const m of l.marks || []) { marks.push(m); sinceMark = Math.max(sinceMark, m.id); }
    // Deux minutes et demie gardées.
    const now = tl.t[tl.t.length - 1];
    let cut = 0; while (cut < tl.t.length && tl.t[cut] < now - 150) cut++;
    if (cut) { for (const c in tl) if (c !== 'lamps') tl[c].splice(0, cut); for (const a of Object.values(tl.lamps)) a.splice(0, cut); }
    while (marks.length && marks[0].t < now - 155) marks.shift();
  }

  async function boucle() {
    while (!stop) {
      try {
        const since = tl.t.length ? `since=${tl.t[tl.t.length - 1]}&` : '';
        const l = await api(`/api/live?${since}since_mark=${key ? sinceMark : 0}`);
        if (stop) break;
        if (l.key !== key && since) { for (const c in tl) if (c !== 'lamps') tl[c].length = 0; tl.lamps = {}; marks.length = 0; sinceMark = 0; key = undefined; continue; }
        st = l.state; age = l.age_ms;
        if ((l.timeline?.t || []).length) tRecu = performance.now();
        ajoute(l);
        texte();
      } catch (e) { /* réessai */ }
      await new Promise(r => setTimeout(r, 400));
    }
  }

  function texte() {
    const on = enDirect({ state: st, age_ms: age });
    teinte({ state: st, age_ms: age });
    $('#livedot').classList.toggle('on', on);
    const sub = $('#lsub');
    if (!st) { sub.textContent = 'Aucun signal du téléphone. Lance Go (Tailscale allumé, envoi activé dans Réglages).'; return; }
    sub.innerHTML = on
      ? `<span style="color:var(--live)">●</span> ${esc(st.status)} sur ${esc(st.area)}${st.session ? ` · <a href="#/s/${encodeURIComponent(st.session)}">session</a>` : ''}`
      : `Pas de show en cours. Dernier signal ${quand(Date.now() - age)}. Le graphe garde le dernier show.`;
    const fort = ['Montée', 'DROP', 'Stroboscope'].includes(st.mode);
    $('#lres').innerHTML = on ? `<b style="font-size:15px;color:${fort ? st.lead : 'var(--text)'}">${esc(st.mode === 'DROP' || st.mode === 'Stroboscope' ? 'Drop' : st.mode)}</b>
      <span class="sub" style="font-size:15px">${esc(st.figureName || st.figure || '')}</span><span class="grow"></span>
      <span class="mono" style="color:var(--dim);font-size:12px">${Math.round(st.bpm)} BPM · ${Math.min(8, Math.max(1, st.barInPhrase))}/8 · T${Math.min(4, Math.max(1, st.beatInBar))}${st.mood ? ' · ' + esc(st.mood) : ''}</span>`
      : '<span class="sub">Arrêté.</span>';
    $('#ltrack').textContent = st.title || 'Morceau inconnu';
    $('#lartist').textContent = [st.artist, st.position != null ? mmss(st.position) : null].filter(Boolean).join(' · ');
    $('#lmem').textContent = !st.title ? '' : st.memory == null ? 'Morceau pas encore connu : il le sera après une écoute en entier.'
      : !st.memory.length ? 'Morceau connu, sans drop.' : `Morceau connu : drop${st.memory.length > 1 ? 's' : ''} à ${st.memory.map(mmss).join(', ')}.`;
    $('#lbpm').textContent = st.bpm ? Math.round(st.bpm) : '–';
    $('#lbar').textContent = st.barInPhrase ? `${st.barInPhrase}/8` : '–';
    $('#ldrops').textContent = st.drops ?? '–';
    document.querySelectorAll('#lbeats i').forEach((b, i) => b.classList.toggle('on', on && i < st.beatInBar));
    $('#lten').style.width = `${Math.round(Math.min(1, st.tension || 0) * 100)}%`;
    $('#lten').style.background = st.lead || 'var(--live)';
    $('#llev').style.width = `${Math.round(Math.min(1, st.level || 0) * 100)}%`;
    const plan = st.plan || [];
    const cle = plan.map(p => p.ch + p.name).join();
    if (cle !== planCle) {
      planCle = cle;
      $('#room').innerHTML = `<div class="sofa" style="left:50%;top:50%">canapé</div>` + plan.map(p =>
        `<div class="lamp" style="left:${Math.min(84, Math.max(16, 50 + p.x * 34))}%;top:${Math.min(80, Math.max(16, 50 - p.y * 40))}%"><i id="lamp${p.ch}"></i><span>${esc(p.name)}</span></div>`).join('');
    }
    for (const [ch, c] of Object.entries(st.lamps || {})) {
      const el = $('#lamp' + ch); if (!el) continue;
      const strobe = on && st.mode === 'Stroboscope';
      el.style.backgroundColor = strobe ? '#fff' : on ? c : 'transparent';
      el.style.boxShadow = on ? `0 0 24px ${strobe ? '#fff' : c}` : 'none';
    }
  }

  const MOMENTS = ['Silence', 'Calme', 'Groove', 'Énergie', 'Montée', 'Drop'];
  const momentDe = m => m === 6 ? 5 : m;

  function dessine() {
    raf = requestAnimationFrame(dessine);
    const dpr = devicePixelRatio || 1, W = cv.clientWidth, H = cv.clientHeight;
    if (cv.width !== W * dpr || cv.height !== H * dpr) { cv.width = W * dpr; cv.height = H * dpr; }
    const g = cv.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, W, H); g.fillStyle = C.plan; g.fillRect(0, 0, W, H);
    const accent = st && st.lead && st.lead !== '#000000' ? st.lead : C.text;
    const n = tl.t.length;
    if (!n) {
      g.fillStyle = C.muted; g.font = '14px Geist'; g.textAlign = 'center';
      g.fillText('Lance Go : le graphe se construit en direct.', W / 2, H / 2); g.textAlign = 'left'; return;
    }
    const vivant = fige == null && st && st.running && age < 5000;
    const fin = fige ?? present(), debut = fin - fen;
    const gauche = W < 600 ? 92 : 110, droite = W - 12, pw = droite - gauche;
    const X = t => gauche + (t - debut) / fen * pw;
    const ordre = (st?.plan || []).slice().sort((a, b) => a.x - b.x);
    const petit = '11px Geist', mono = '500 10px GeistMono';
    const txt = (s, x, y, coul = C.dim, font = petit) => { g.font = font; g.fillStyle = coul; g.textBaseline = 'top'; g.fillText(s, x, y); return g.measureText(s).width; };
    const larg = (s, font = petit) => { g.font = font; return g.measureText(s).width; };
    const rr = (x, y, w, h, r, c) => { g.fillStyle = c; g.beginPath(); g.roundRect(x, y, Math.max(0, w), h, r); g.fill(); };

    let y = 12; const titreEffets = y; y += 20; const bandeEffet = y; const hB = 22; y += hB + 6;
    const hL = 20, lampes0 = y; y += ordre.length * (hL + 4) + 12;
    const titreMusique = y; y += 20; const onde0 = y;
    const analyse0 = H - 192, onde1 = analyse0 - 14, grille = analyse0 + 20, bandeMoment = grille + 18;
    const courbes0 = bandeMoment + hB + 6, hC = 60, reperes0 = courbes0 + hC + 6, axe = reperes0 + 34;

    // Index de la fenêtre.
    let lo = 0, hi = n; while (lo < hi) { const m = (lo + hi) >> 1; if (tl.t[m] < debut) lo = m + 1; else hi = m; }
    const i0 = Math.max(0, lo - 1);
    let i1 = n; while (i1 > i0 && tl.t[i1 - 1] > fin) i1--;

    // Mesures en fond.
    for (const r of marks) {
      if (r.k !== 'BEAT' || r.v % 4) continue;
      const xb = X(r.t); if (xb < gauche || xb > droite) continue;
      g.fillStyle = r.v === 0 ? 'rgba(74,74,82,.55)' : C.line2; g.fillRect(xb, bandeEffet, 1, reperes0 - bandeEffet);
    }
    txt('EFFETS', 12, titreEffets, C.dim, mono); txt('MUSIQUE', 12, titreMusique, C.dim, mono); txt('ANALYSE', 12, analyse0, C.dim, mono);

    // Segments (moment + effet).
    const segs = [];
    for (let i = i0; i < i1; i++) {
      const t = tl.t[i], m = tl.mode[i], f = tl.fx[i], l = segs[segs.length - 1];
      if (l && l.m === m && l.f === f) l.t1 = t; else segs.push({ t0: Math.max(t, debut), t1: t, m, f });
    }
    txt('Figure', 12, bandeEffet + 4);
    for (const s of segs) {
      const x0 = Math.max(gauche, X(s.t0)), x1 = Math.min(droite, X(s.t1)); if (x1 - x0 < 1) continue;
      const [fond, encre] = s.m >= 5 ? [accent, C.bg] : s.m === 4 ? [accent + '66', C.text] : s.m === 0 ? ['rgba(255,255,255,.03)', C.dim] : ['#232329', C.text];
      rr(x0, bandeEffet, x1 - x0 - 1, hB, 5, fond);
      if (x1 - x0 > larg(s.f) + 12) txt(s.f, x0 + 6, bandeEffet + 5, encre);
    }

    // Lampes.
    ordre.forEach((lp, j) => {
      const ly = lampes0 + j * (hL + 4);
      g.save(); g.beginPath(); g.rect(0, 0, gauche - 8, H); g.clip(); txt(lp.name, 12, ly + 4, C.dim); g.restore();
      rr(gauche, ly, pw, hL, 3, 'rgba(255,255,255,.03)');
      const a = tl.lamps[lp.ch]; if (!a) return;
      for (let i = i0; i < i1; i++) {
        const x0 = X(tl.t[i]), x1 = i + 1 < n ? X(tl.t[i + 1]) : x0 + 2;
        const strobe = tl.mode[i] === 6, c = strobe ? 'rgba(255,255,255,.85)' : a[i];
        if (!c || c === '#000000') continue;
        g.fillStyle = c; g.fillRect(Math.max(gauche, x0), ly, Math.min(droite, x1 + .5) - Math.max(gauche, x0), hL);
      }
    });

    // Musique.
    const mil = (onde0 + onde1) / 2, demi = (onde1 - onde0) / 2;
    txt('Son', 12, mil - demi / 2 - 8); txt('Basses', 12, mil + demi / 2 - 8);
    g.fillStyle = C.line2; g.fillRect(gauche, mil, pw, 1);
    const cols = Math.max(1, Math.floor(pw / 2)), cw = pw / cols;
    const nv = new Float32Array(cols), bs = new Float32Array(cols), te = new Float32Array(cols).fill(-1), it = new Float32Array(cols).fill(-1);
    for (let i = i0; i < i1; i++) {
      const c = Math.floor((tl.t[i] - debut) / fen * cols); if (c < 0 || c >= cols) continue;
      nv[c] = Math.max(nv[c], tl.level[i]); bs[c] = Math.max(bs[c], tl.bass[i]); te[c] = tl.tension[i]; it[c] = tl.intensity[i];
    }
    for (let c = 0; c < cols; c++) {
      const xc = gauche + c * cw, hn = Math.min(1, nv[c]) * demi, hb = Math.min(1, bs[c]) * demi;
      if (hn > .5) { g.fillStyle = 'rgba(161,161,170,.75)'; g.fillRect(xc, mil - hn, cw * .8, hn); }
      if (hb > .5) { g.globalAlpha = .9; g.fillStyle = accent; g.fillRect(xc, mil + 1, cw * .8, hb); g.globalAlpha = 1; }
    }

    // Temps et mesures.
    txt('Mesure', 12, grille + 1);
    let finNum = -1;
    for (const r of marks) {
      if (r.k !== 'BEAT') continue;
      const xb = X(r.t); if (xb < gauche || xb > droite) continue;
      const mes = r.v % 4 === 0, ph = r.v === 0, h = ph ? 16 : mes ? 10 : 4;
      g.fillStyle = ph ? C.text : mes ? C.muted : C.neutre; g.fillRect(xb, grille + 16 - h, ph ? 2 : 1.5, h);
      if (mes && (fen <= 30 || ph) && xb > finNum) finNum = xb + txt(String((r.v >> 2) + 1), xb + 3, grille, ph ? C.text : C.dim, mono) + 6;
    }

    // Moment.
    txt('Moment', 12, bandeMoment + 4);
    const gris = ['rgba(255,255,255,.03)', '#1F1F24', '#2B2B32', '#3C3C45'];
    const fus = [];
    for (const s of segs) { const p = fus[fus.length - 1]; if (p && momentDe(p.m) === momentDe(s.m)) p.t1 = s.t1; else fus.push({ ...s }); }
    for (const s of fus) {
      const x0 = Math.max(gauche, X(s.t0)), x1 = Math.min(droite, X(s.t1)); if (x1 - x0 < 1) continue;
      const mo = momentDe(s.m);
      const [fond, encre] = mo === 5 ? [accent, C.bg] : mo === 4 ? [accent + '66', C.text] : [gris[mo], mo === 0 ? C.dim : C.text];
      rr(x0, bandeMoment, x1 - x0 - 1, hB, 5, fond);
      if (x1 - x0 > larg(MOMENTS[mo]) + 12) txt(MOMENTS[mo], x0 + 6, bandeMoment + 5, encre);
    }

    // Tension et intensité.
    txt('Tension', 12, courbes0 + 2, accent); txt('Intensité', 12, courbes0 + hC - 16);
    rr(gauche, courbes0, pw, hC, 4, 'rgba(255,255,255,.03)');
    const courbe = (v, coul, ep, remplir) => {
      g.beginPath(); let ouvert = false, dx = gauche;
      for (let c = 0; c < cols; c++) {
        if (v[c] < 0) continue;
        const xc = gauche + (c + .5) * cw, yc = courbes0 + hC - 2 - Math.min(1, Math.max(0, v[c])) * (hC - 4);
        if (!ouvert) { g.moveTo(xc, yc); ouvert = true; } else g.lineTo(xc, yc); dx = xc;
      }
      if (!ouvert) return;
      g.strokeStyle = coul; g.lineWidth = ep; g.stroke();
      if (remplir) { g.lineTo(dx, courbes0 + hC); g.lineTo(gauche, courbes0 + hC); g.closePath(); g.globalAlpha = .16; g.fillStyle = coul; g.fill(); g.globalAlpha = 1; }
    };
    courbe(it, C.muted, 1.5, false); courbe(te, accent, 2, true);

    // Repères.
    txt('Repères', 12, reperes0 + 2);
    const finL = [-1, -1];
    for (const r of marks) {
      if (r.k === 'BEAT') continue;
      const xr = X(r.t); if (xr < gauche - 2 || xr > droite + 2) continue;
      const coul = r.k === 'DROP' ? accent : r.k === 'TRACK' ? C.text : r.k === 'NOT_DROP' ? C.muted : C.dim;
      if (r.k === 'DROP') { g.fillStyle = accent; g.fillRect(xr - 1, bandeEffet, 2, reperes0 + 30 - bandeEffet); }
      else if (r.k === 'TRACK') { g.setLineDash([6, 4]); g.strokeStyle = C.muted; g.lineWidth = 1; g.beginPath(); g.moveTo(xr, bandeEffet); g.lineTo(xr, reperes0 + 30); g.stroke(); g.setLineDash([]); }
      else if (r.k === 'NOT_DROP') { g.strokeStyle = C.muted; g.lineWidth = 1.5; g.beginPath(); g.arc(xr, reperes0 + 8, 3.5, 0, 7); g.stroke(); }
      else { g.fillStyle = coul; g.fillRect(xr, reperes0, 1.5, 12); }
      const font = r.k === 'DROP' ? '500 11px Geist' : petit, w = larg(r.l, font), lx = Math.min(xr + 5, droite - w);
      const li = lx > finL[0] ? 0 : lx > finL[1] ? 1 : -1; if (li < 0) continue;
      txt(r.l, lx, reperes0 + li * 15, coul, font); finL[li] = lx + w + 6;
    }

    // Axe.
    const mt = vivant ? 'MAINTENANT' : 'FIGÉ', dm = droite - larg(mt, mono);
    txt(mt, dm, axe + 5, C.muted, mono);
    const pas = fen <= 30 ? 5 : 30;
    for (let s = pas; s < fen - .1; s += pas) {
      const xs = X(fin - s); g.fillStyle = C.repere; g.fillRect(xs, axe, 1, 4);
      const lib = `-${s} s`, w = larg(lib, mono); if (xs + w / 2 < dm - 8) txt(lib, xs - w / 2, axe + 5, C.dim, mono);
    }
    if (vivant) { g.fillStyle = 'rgba(244,244,245,.35)'; g.fillRect(droite, bandeEffet, 1, reperes0 + 30 - bandeEffet); }
  }

  dessine();
  boucle();
}

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
          <span class="hint">${matchMedia('(pointer: coarse)').matches ? 'Glisse pour te déplacer, touche pour choisir un moment.' : 'Molette pour zoomer, glisser pour se déplacer, cliquer pour choisir un moment.'}</span></div>
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
