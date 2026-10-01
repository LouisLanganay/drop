"""Rejoue la détection des drops de Drop sur un enregistrement (files/recordings/*.csv).

Usage : python3 drops.py <csv> [png]
Affiche les événements enregistrés, l'énergie des basses par temps, et trace basses + temps + drops.
"""
import sys, math
import numpy as np

path = sys.argv[1]
frames, events = [], []
for line in open(path):
    p = line.rstrip('\n').split(',')
    if p[0] == 'F' and p[1] != 't':
        frames.append([float(x) for x in p[1:]])
    elif p[0] == 'E' and p[1] != 't':
        events.append((float(p[1]), p[2], ','.join(p[3:])))
F = np.array(frames)
cols = "t,rms2,bass,high,bflux,mflux,onset,level,bassmid,bpm,conf".split(',')
C = {c: F[:, i] for i, c in enumerate(cols)}
t = C['t']
print(f"{len(F)} blocs, de {t[0]:.1f} à {t[-1]:.1f} s ; {len(events)} événements")

beats = [(et, int(d.split()[0]), int(d.split()[1])) for et, k, d in events if k == 'beat']
for et, k, d in events:
    if k != 'beat':
        print(f"{et:8.2f}  {k:12s} {d}")

# Énergie des basses par temps (de ce temps au suivant), en dB.
bt = np.array([b[0] for b in beats])
per = []
for i in range(len(bt) - 1):
    m = (t >= bt[i]) & (t < bt[i + 1])
    if m.sum() == 0:
        continue
    per.append((bt[i], beats[i][2], 10 * math.log10(C['bass'][m].mean() + 1e-12)))
np.save(path + '.beats.npy', np.array(per))

if len(sys.argv) > 2:
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    fig, ax = plt.subplots(2, 1, figsize=(22, 8), sharex=True)
    bd = 10 * np.log10(C['bass'] + 1e-12)
    ax[0].plot(t, bd, lw=0.3, color='#888')
    ax[0].plot(t, 10 * np.log10(C['bassmid'] + 1e-12), lw=1, color='#e07030')
    P = np.array(per)
    if len(P):
        ax[1].step(P[:, 0], P[:, 2], where='post', color='#3070e0')
        for x, pos, _ in P:
            if pos % 32 == 0:
                ax[1].axvline(x, color='k', lw=0.8)
            elif pos % 4 == 0:
                ax[1].axvline(x, color='#ccc', lw=0.5)
    for et, k, d in events:
        c = {'drop': 'red', 'drop_start': 'orange', 'drop_cancel': 'purple', 'title': 'green', 'track': 'green'}.get(k)
        if c:
            for a in ax:
                a.axvline(et, color=c, lw=1.2)
            ax[0].text(et, ax[0].get_ylim()[1], f"{k} {d[:30]}", fontsize=7, rotation=90, va='top', color=c)
    ax[0].set_ylabel('basses (dB, bloc et ~1 temps)')
    ax[1].set_ylabel('basses par temps (dB)')
    plt.tight_layout()
    plt.savefig(sys.argv[2], dpi=90)
    print("graphe :", sys.argv[2])
