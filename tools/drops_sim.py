# Usage : python3 tools/drops_sim.py <enregistrement.csv> [temps de confirmation=4] [attente après annulation=0.5] [écart max aux passages forts=3] [montée min=6] [seuil de déclenchement=4]
# Rejoue la règle de drop de Analyzer.kt sur un enregistrement (files/recordings, voir Recorder.kt).
"""Simule la règle de drop sur un enregistrement : candidats au retour des basses, confirmation sur N temps."""
import sys, math, numpy as np
path=sys.argv[1]; N=int(sys.argv[2]) if len(sys.argv)>2 else 4
COOL=float(sys.argv[3]) if len(sys.argv)>3 else 0.5
LOUD=float(sys.argv[4]) if len(sys.argv)>4 else 3
RISE=float(sys.argv[5]) if len(sys.argv)>5 else 6
TRIG=float(sys.argv[6]) if len(sys.argv)>6 else 4
fr=[];ev=[]
for l in open(path):
    p=l.rstrip('\n').split(',')
    if p[0]=='F' and p[1]!='t': fr.append([float(x) for x in p[1:]])
    elif p[0]=='E' and p[1]!='t': ev.append((float(p[1]),p[2],','.join(p[3:])))
F=np.array(fr); t=F[:,0]; bass=F[:,2]; bmid=F[:,8]
beats=sorted([(e[0],int(e[2].split()[0])) for e in ev if e[1]=='beat'])
db=lambda v:10*math.log10(v+1e-12)
# énergie par temps
bt=[]
for b in beats:
    if not bt or b[0]-bt[-1]>0.15: bt.append(b[0])
per={}
for i in range(len(bt)-1):
    m=(t>=bt[i])&(t<bt[i+1])
    if m.any(): per[i]=db(bass[m].mean())
hist=[]; loud=None; low=None
tracks=sorted(e[0] for e in ev if e[1]=='track'); ti=0
cand=None; lastDrop=-100; lastCancel=-100; out=[]
bi=0  # prochain temps à fermer
for k in range(len(t)):
    tt=t[k]
    if ti<len(tracks) and tt>=tracks[ti]:
        hist=[]; loud=None; ti+=1
    # fermer les temps dont la fin est passée
    while bi+1<len(bt) and bt[bi+1]<=tt:
        v=per.get(bi)
        if v is not None:
            hist.append(v); hist=hist[-140:]
            if len(hist)>=16:
                s=sorted(hist); loud=s[min(int(len(s)*0.8),len(s)-1)]
                low=db(np.mean([10**(x/10) for x in hist[-16:]]))
        bi+=1
        if cand and bi>=cand['beat']+N:
            vals=[per.get(j) for j in range(cand['beat'],cand['beat']+N)]
            if None in vals: res='manque'; ok=False
            else:
                mean=float(np.mean(vals)); mn=min(vals)
                rise=mean-cand['low']
                ok= rise>=RISE and mean>=loud-LOUD and mn>=max(loud-12,mean-12)
                res=f"rise={rise:.1f} mean-loud={mean-loud:+.1f} min-loud={mn-loud:+.1f} [{' '.join(f'{x:.0f}' for x in vals)}]"
            out.append((cand['t'], 'DROP' if ok else 'annulé', res))
            if ok: lastDrop=tt
            else: lastCancel=tt
            cand=None
    if cand is None and loud is not None and tt-lastDrop>15 and tt-lastCancel>COOL:
        m=db(bmid[k])
        if m>=loud-TRIG and m>=low+RISE:
            back=tt-0.15
            j=min(range(len(bt)),key=lambda i:abs(bt[i]-back))
            cand={'t':bt[j],'beat':j,'low':low}
for o in out: print(f"{o[0]:7.2f}  {o[1]:7s} {o[2]}")
