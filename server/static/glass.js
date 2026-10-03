// Verre liquide du dashboard : la recette validée pour Fynex (30/09, tranche adoucie le 01/10), reprise telle
// quelle. Chaque élément `.lens` reçoit un filtre SVG taillé à sa forme : une lentille claire, presque nette, dont
// la déviation croît vers le contour (t^2.2), une seule passe (pas d'irisation), un léger gain de lumière.
// Chromium seulement pour `backdrop-filter: url()` ; ailleurs, le flou de repli du CSS.
'use strict';

(() => {
  const NS = 'http://www.w3.org/2000/svg';

  /** Carte de déplacement d'un rectangle arrondi w x h de rayon r, en data URL. */
  function carte(w, h, r) {
    const c = document.createElement('canvas');
    c.width = w; c.height = h;
    const g = c.getContext('2d');
    const img = g.createImageData(w, h);
    const hx = w / 2 - r, hy = h / 2 - r;
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const px = x + 0.5 - w / 2, py = y + 0.5 - h / 2;
        const qx = Math.max(Math.abs(px) - hx, 0), qy = Math.max(Math.abs(py) - hy, 0);
        const len = Math.hypot(qx, qy), dedans = r - len;
        let nx = 0, ny = 0, mag = 0;
        if (dedans > 0 && len > 0) {
          nx = qx * Math.sign(px) / len; ny = qy * Math.sign(py) / len;
          mag = Math.pow(1 - dedans / r, 2.2);
        }
        const i = (y * w + x) * 4;
        // Vers l'intérieur seulement : au-delà du contour le filtre n'a pas de fond (cadre sombre).
        img.data[i] = 128 - nx * mag * 127;
        img.data[i + 1] = 128 - ny * mag * 127;
        img.data[i + 2] = 128;
        img.data[i + 3] = 255;
      }
    }
    g.putImageData(img, 0, 0);
    return c.toDataURL();
  }

  function filtre(id, w, h, r) {
    const scale = Math.round(Math.min(h, r * 2) * 0.8);
    return `<filter id="${id}" x="0" y="0" width="${w}" height="${h}" filterUnits="userSpaceOnUse" primitiveUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
      <feGaussianBlur in="SourceGraphic" stdDeviation="1.6" edgeMode="duplicate" result="src"/>
      <feImage href="${carte(w, h, r)}" x="0" y="0" width="${w}" height="${h}" preserveAspectRatio="none" result="map"/>
      <feDisplacementMap in="src" in2="map" scale="${scale}" xChannelSelector="R" yChannelSelector="G" result="bent"/>
      <feColorMatrix in="bent" type="saturate" values="1.5" result="sat"/>
      <feComponentTransfer in="sat">
        <feFuncR type="linear" slope="1.06" intercept="0.035"/>
        <feFuncG type="linear" slope="1.06" intercept="0.035"/>
        <feFuncB type="linear" slope="1.06" intercept="0.04"/>
      </feComponentTransfer>
    </filter>`;
  }

  const svg = document.createElementNS(NS, 'svg');
  svg.setAttribute('aria-hidden', 'true');
  svg.setAttribute('width', '0'); svg.setAttribute('height', '0');
  svg.style.position = 'absolute';
  document.body.appendChild(svg);

  const suivis = new Map();
  let n = 0;
  const rendre = () => { svg.innerHTML = [...suivis.values()].map(t => t.markup).join(''); };
  const ro = new ResizeObserver(entries => {
    let change = false;
    for (const e of entries) {
      const el = e.target, t = suivis.get(el);
      if (!t) continue;
      const w = Math.round(el.offsetWidth), h = Math.round(el.offsetHeight);
      if (!w || !h || `${w}x${h}` === t.size) continue;
      const r = Math.min(parseFloat(getComputedStyle(el).borderTopLeftRadius) || h / 2, h / 2, w / 2);
      t.size = `${w}x${h}`;
      t.markup = filtre(t.id, w, h, Math.max(4, r));
      change = true;
    }
    if (change) rendre();
  });
  const scan = () => {
    const els = new Set(document.querySelectorAll('.lens'));
    for (const el of [...suivis.keys()]) if (!els.has(el)) { ro.unobserve(el); suivis.delete(el); }
    for (const el of els) {
      if (suivis.has(el)) continue;
      const id = `drop-lens-${n++}`;
      suivis.set(el, { id, size: '', markup: '' });
      el.style.setProperty('--lens', `url(#${id})`);
      ro.observe(el);
    }
  };
  new MutationObserver(scan).observe(document.body, { childList: true, subtree: true });
  scan();
})();
