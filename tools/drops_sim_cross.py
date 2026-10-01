"""Variante : référence gardée d'un morceau à l'autre (KEEP=1), et un drop nettement plus fort que le précédent passe malgré l'écart de 15 s."""
import sys, math, numpy as np
path=sys.argv[1]; KEEP=int(sys.argv[2]); LOUD=float(sys.argv[3]); WIN=int(sys.argv[4]) if len(sys.argv)>4 else 140
N=4; RISE=6; TRIG=LOUD+1
fr=[];ev=[]
for l in open(path):
    p=l.rstrip('\n').split(',')
    if p[0]=='F' and p[1]!='t': fr.append([float(x) for x in p[1:]])
    elif p[0]=='E' and p[1]!='t': ev.append((float(p[1]),p[2],','.join(p[3:])))
F=np.array(fr); t=F[:,0]; bass=F[:,2]; bmid=F[:,8]
beats=sorted([e[0] for e in ev if e[1]=='beat'])
bt=[]
for b in beats:
    if not bt or b-bt[-1]>0.15: bt.append(b)
db=lambda v:10*math.log10(v+1e-12)
per={}
for i in range(len(bt)-1):
    m=(t>=bt[i])&(t<bt[i+1])
    if m.any(): per[i]=db(bass[m].mean())
tracks=sorted(e[0] for e in ev if e[1]=='track'); titles=sorted((e[0],e[2]) for e in ev if e[1]=='title')
hist=[]; loud=None; low=None; cand=None; lastDrop=-100; lastLevel=-99; lastCancel=-100; out=[]; bi=0; ti=0
for k in range(len(t)):
    tt=t[k]
    if ti<len(tracks) and tt>=tracks[ti]:
        if not KEEP: hist=[]; loud=None
        ti+=1
    while bi+1<len(bt) and bt[bi+1]<=tt:
        v=per.get(bi)
        if v is not None:
            hist.append(v); hist=hist[-WIN:]
            if len(hist)>=16:
                s=sorted(hist); loud=s[min(int(len(s)*0.8),len(s)-1)]
                low=db(np.mean([10**(x/10) for x in hist[-16:]]))
        bi+=1
        if cand and bi>=cand['beat']+N:
            vals=[per.get(j) for j in range(cand['beat'],cand['beat']+N)]
            if None in vals: ok=False; res='manque'
            else:
                mean=float(np.mean(vals)); mn=min(vals); rise=mean-cand['low']
                ok= rise>=RISE and mean>=cand['loud']-LOUD and mn>=max(cand['loud']-12,mean-12)
                res=f"rise={rise:.1f} mean-loud={mean-cand['loud']:+.1f} [{' '.join(f'{x:.0f}' for x in vals)}]"
            out.append((cand['t'],'DROP' if ok else 'annulé',res))
            if ok: lastDrop=tt; lastLevel=mean
            else: lastCancel=tt
            cand=None
    if cand is None and loud is not None and tt-lastCancel>0.5:
        m=db(bmid[k])
        stronger = m>=lastLevel+6
        if (tt-lastDrop>15 or stronger) and m>=loud-TRIG and m>=low+RISE:
            back=tt-0.15
            j=min(range(len(bt)),key=lambda i:abs(bt[i]-back))
            cand={'t':bt[j],'beat':j,'low':low,'loud':loud}
def name(x):
    n=''; s=0
    for a,b in titles:
        if a<=x: n=b.split(' - ')[-1]; s=a
    return f"{n} {x-s:+.1f}s"
for o in out: print(f"{o[0]:7.2f}  {o[1]:7s} {o[2]:45s} ({name(o[0])})")
