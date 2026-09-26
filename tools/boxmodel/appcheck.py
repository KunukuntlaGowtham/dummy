# Same maths as the Kotlin BoxModel: box-average the crop to 20x20, then the network.
from PIL import Image
import numpy as np, os
b=open('box_model.bin','rb').read(); assert b[:4]==b'BOXM'
N,H=np.frombuffer(b[4:12],'<i4'); D=N*N*3; o=12
def take(k):
    global o; a=np.frombuffer(b[o:o+4*k],'<f4'); o+=4*k; return a
W1=take(D*H).reshape(D,H); b1=take(H); W2=take(H*3).reshape(H,3); b2=take(3)
def boxavg(rgb, n):   # rgb HxWx3 uint8 -> n*n*3 floats, like BoxModel.shrink
    h,w,_=rgb.shape; out=np.zeros((n,n,3))
    for oy in range(n):
        y0=oy*h//n; y1=max(y0+1,(oy+1)*h//n)
        for ox in range(n):
            x0=ox*w//n; x1=max(x0+1,(ox+1)*w//n)
            out[oy,ox]=rgb[y0:y1,x0:x1].reshape(-1,3).mean(0)
    return (out/255.0).reshape(-1)
def net(x):
    h=np.maximum(x@W1+b1,0); z=h@W2+b2; e=np.exp(z-z.max()); return e/e.sum()
O='relabel'; CL=['empty','ticked','other']
tot=0; wrong=0; badtap=0; missed=0
for ci,d in enumerate(['empty','ticked','other','other_screens']):
    ci=min(ci,2)
    for f in sorted(os.listdir(f'{O}/{d}')):
        a=Image.open(f'{O}/{d}/{f}').convert('RGB')
        for size in (40,48,60):   # the crop at half size is about this big in the frame
            s=np.asarray(a.resize((size,size),Image.BILINEAR))
            p=net(boxavg(s,N)); tot+=1
            if p.argmax()!=ci: wrong+=1
            if p[0]>0.8 and ci!=0: badtap+=1
            if p[0]<=0.8 and ci==0: missed+=1
print('app-style check on all',tot,'crops: wrong',wrong,'| taps on non-boxes',badtap,'| empties not tapped',missed)
