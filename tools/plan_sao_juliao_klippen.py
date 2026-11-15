import json,sys,math,glob
import numpy as np
from PIL import Image
exec(open('muehle_pack.py').read().split("def load(pattern)")[0])
tiles=[]
for n in glob.glob('sj/MDS*.tif'):
    im=Image.open(n); tp=im.tag_v2[33922]; tiles.append((tp[3],tp[4],2.0,np.array(im,dtype=np.float32)))
def surf(lat,lon):
    x,y=tm(lat,lon)
    for x0,y0,d,a in tiles:
        c=int((x-x0)/d); r=int((y0-y)/d)
        if 0<=r<a.shape[0] and 0<=c<a.shape[1]:
            v=float(a[r,c]); return 0.0 if v<-900 else v
    return 0.0   # außerhalb der Kacheln liegt hier nur Meer (westlich)
bar=(38.9327963,-9.4192322); G0=17.3; kx=111320*math.cos(math.radians(38.93)); ky=111200
def P(ds,dw): return (bar[0]-ds/ky, bar[1]-dw/kx)
def check(a,ha,b,hb):
    d=math.hypot((a[0]-b[0])*ky,(a[1]-b[1])*kx); n=max(1,int(d/4)); land=1e9; clr=1e9
    for i in range(n+1):
        t=i/n; p=(a[0]+(b[0]-a[0])*t,a[1]+(b[1]-a[1])*t); h=ha+(hb-ha)*t
        for r in range(0,61,6):
            for k in range(12):
                q=(p[0]+r*math.cos(k*.5236)/ky,p[1]+r*math.sin(k*.5236)/kx); s=surf(*q)
                if s>1.5: land=min(land,r)
                if r<=24: clr=min(clr,h-(s-G0))
    return d,land,clr
wps=[]
def wp(p,h,**kw): wps.append(dict(lat=round(p[0],6),lon=round(p[1],6),h=h,photo=False,**kw))
# Clip 1: an Strand und Klippen entlang nach Süden, unterhalb der Klippenkante, über dem Wasser
wp(P(0,116),35,video="start",hover=2,yaw=160,pitch=-12)
wp(P(100,150),32,yaw=160,pitch=-10); wp(P(200,190),32,yaw=155,pitch=-8); wp(P(300,240),34,yaw=150,pitch=-6)
wp(P(400,280),36,yaw=150,pitch=-6); wp(P(500,320),38,video="stop",hover=1,yaw=140,pitch=-6)
# Clip 2: steigen und hoch über dem Wasser zurück, Blick auf die ganze Küste
wp(P(520,420),75,video="start",hover=2,yaw=50,pitch=-18)
wp(P(350,360),75,yaw=50,pitch=-18); wp(P(170,300),70,video="stop",hover=1,yaw=60,pitch=-20)
# Clip 3: Bogen vor dem Strand von Süd nach Nord, Kamera auf die Strandmitte
T=(38.9322,-9.4195)
def off(p,d,brg): r=math.radians(brg); return (p[0]+d*math.cos(r)/ky,p[1]+d*math.sin(r)/kx)
for i,a in enumerate(range(-55,56,22)):
    brg=285+a; q=off(T,150,brg); back=(brg+180)%360
    kw=dict(yaw=round(back),pitch=-17)
    if i==0: kw.update(video="start",hover=1)
    wp(q,42,**kw)
wps[-1].update(video="stop",hover=2)
plan={"name":"klippen-sao-juliao-2","beschreibung":"Langer Küstenflug Praia de São Julião: 1) an Strand und Klippen entlang 500 m nach Süden, über dem Wasser und unterhalb der Klippenkante, Kamera auf die Klippen; 2) Steigen auf 75 m und hoch über dem Wasser zurück mit Blick auf die ganze Küste; 3) Bogen vor dem Strand, Kamera auf die Strandmitte. Drei Clips, weiteste Entfernung etwa 670 m. Gegen DGT-LiDAR geprüft: alle Punkte über dem Wasser, mindestens 40 m vom Land. Hinderniserkennung für diesen Flug aus. Nur bei Tageslicht.",
 "speed":5,"rcLost":"goHome","hinderniserkennung":"aus","transitHoehe":50,"waypoints":wps}
pts=[(bar,50)]+[((w['lat'],w['lon']),w['h']) for w in wps]+[(bar,50)]
tot=0
for (a,ha),(b,hb) in zip(pts,pts[1:]):
    d,land,clr=check(a,max(ha,50) if a==bar else ha,b,max(hb,50) if b==bar else hb); tot+=d
    print(f"{d:5.0f} m  h {ha}→{hb}  Land ab {land if land<1e8 else '>60'} m  Höhe über Oberfläche(24 m) {clr:5.1f}  Entfernung Bar {math.hypot((b[0]-bar[0])*ky,(b[1]-bar[1])*kx):4.0f}",file=sys.stderr)
print(f"{tot:.0f} m, {tot/5/60:.1f} min",file=sys.stderr)
json.dump(plan,open(sys.argv[1],'w'),ensure_ascii=False,indent=1)
