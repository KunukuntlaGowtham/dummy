# Trains the box model (app/src/main/assets/box_model.bin) from sorted samples:
# relabel/empty, relabel/ticked, relabel/other (+ other_screens). The samples themselves are
# not in the repo (they show names from the page). appcheck.py checks it the way the app runs it.
from PIL import Image
import numpy as np, os
O='relabel'; N=20
rng=np.random.default_rng(7)
CL=['empty','ticked','other']
def load(d): return [np.array(Image.open(f'{O}/{d}/{f}').convert('RGB')) for f in sorted(os.listdir(f'{O}/{d}'))]
data={'empty':load('empty'),'ticked':load('ticked'),'other':load('other')+load('other_screens')}
train,val=[],[]
for ci,c in enumerate(CL):
    idx=rng.permutation(len(data[c])); cut=len(idx)//5
    for j,i in enumerate(idx): (val if j<cut else train).append((data[c][i],ci))
def down(a):
    return np.asarray(Image.fromarray(a).resize((N,N),Image.BOX),dtype=np.float32).reshape(-1)/255.0
def aug(a,ci):
    im=Image.fromarray(a); lab=ci
    if ci<2 and rng.random()<0.2:   # a box only partly in the crop: not a box to tap
        dx,dy=[int(v) for v in rng.choice([-1,1],2)*rng.integers(26,40,2)]; lab=2
    else:
        dx,dy=[int(v) for v in rng.integers(-5,6,2)]
    s=rng.uniform(0.88,1.12); w=96/s
    cx,cy=48-dx,48-dy
    im=im.crop((cx-w/2,cy-w/2,cx+w/2,cy+w/2)).resize((96,96),Image.BILINEAR)
    b=np.asarray(im).astype(np.float32)
    b=b*rng.uniform(0.85,1.1)+rng.uniform(-12,12)
    if ci<2 and rng.random()<0.25: b=b*0.55+80*0.45       # dimmed behind a pop-up
    return np.clip(b,0,255).astype(np.uint8),lab
Xv=np.stack([down(a) for a,_ in val]); yv=np.array([c for _,c in val])
H=24; D=N*N*3
W1=rng.normal(0,np.sqrt(2/D),(D,H)).astype(np.float32); b1=np.zeros(H,np.float32)
W2=rng.normal(0,np.sqrt(2/H),(H,3)).astype(np.float32); b2=np.zeros(3,np.float32)
P=[W1,b1,W2,b2]; M=[np.zeros_like(p) for p in P]; V=[np.zeros_like(p) for p in P]
def fwd(X):
    h=np.maximum(X@W1+b1,0); z=h@W2+b2; z-=z.max(1,keepdims=True); e=np.exp(z); return h,e/e.sum(1,keepdims=True)
# balanced sampling by class weight
wts=np.array([1/sum(1 for _,c in train if c==k) for _,k in train]); wts/=wts.sum()
t=0; lr=2e-3
for ep in range(150):
    lr=2e-3 if ep<80 else (7e-4 if ep<120 else 2e-4)
    for it in range(40):
        pick=rng.choice(len(train),64,p=wts)
        xs,ys=[],[]
        for i in pick:
            a,l=aug(*train[i]); xs.append(down(a)); ys.append(l)
        X=np.stack(xs); y=np.array(ys)
        h,p=fwd(X); g=p.copy(); g[np.arange(len(y)),y]-=1; g*=np.where(y==2,3.0,1.0)[:,None]; g/=len(y)
        gW2=h.T@g+1e-4*W2; gb2=g.sum(0); gh=g@W2.T; gh[h<=0]=0
        gW1=X.T@gh+1e-4*W1; gb1=gh.sum(0)
        t+=1
        for k,gr in enumerate([gW1,gb1,gW2,gb2]):
            M[k]=0.9*M[k]+0.1*gr; V[k]=0.999*V[k]+0.001*gr*gr
            P[k]-=lr*(M[k]/(1-0.9**t))/(np.sqrt(V[k]/(1-0.999**t))+1e-8)
    if ep%25==24:
        _,pv=fwd(Xv); acc=(pv.argmax(1)==yv).mean(); print('epoch',ep+1,'val acc',round(acc*100,1))
_,pv=fwd(Xv); pred=pv.argmax(1)
cm=np.zeros((3,3),int)
for a,b in zip(yv,pred): cm[a,b]+=1
print('confusion (rows true empty/ticked/other):\n',cm)
# also: dimmed + shifted val copies
Xd=[];yd=[]
for a,c in val:
    b,l=aug(a,c); Xd.append(down(b)); yd.append(l)
_,pd=fwd(np.stack(Xd)); print('augmented val acc',round((pd.argmax(1)==np.array(yd)).mean()*100,1))
# save: header 'BOXM', N, H, then W1 (D*H row-major), b1, W2 (H*3), b2 - float32 little endian
with open('box_model.bin','wb') as f:
    f.write(b'BOXM'); f.write(np.array([N,H],dtype='<i4').tobytes())
    for p in P: f.write(p.astype('<f4').tobytes())
print('size',os.path.getsize('box_model.bin'))
# reference outputs for a unit test
for c in ['empty','ticked']:
    f=sorted(os.listdir(f'{O}/{c}'))[0]
    x=down(np.array(Image.open(f'{O}/{c}/{f}').convert('RGB')))
    print('ref',c,f,np.round(fwd(x[None])[1][0],4))

bad=[i for i,(a,b) in enumerate(zip(yv,pred)) if a!=b]
sh=Image.new('RGB',(max(1,len(bad))*100,100),'white')
for k,i in enumerate(bad): sh.paste(Image.fromarray(val[i][0]),(k*100,0))
sh.save('wrong.png'); print('wrong',[(CL[yv[i]],CL[pred[i]],round(float(pv[i].max()),2)) for i in bad])
for th in [0.5,0.8,0.9,0.95]:
    tap=(pv[:,0]>th); print('p_empty>',th,'taps on non-boxes:',int((tap&(yv!=0)).sum()),'real empties missed:',int((~tap&(yv==0)).sum()))
