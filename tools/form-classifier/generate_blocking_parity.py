"""Generate synthetic Python/Android v2 preprocessing parity cases (no athletes)."""
import json
from pathlib import Path
import numpy as np
from blocking_arm_temporal import window, RECIPE


def main():
    world=np.zeros((33,3))
    for i,point in {0:(0,-.7,.1),11:(-.2,-.5,0),12:(.2,-.5,0),13:(-.3,-.8,.1),
        14:(.3,-.8,.1),15:(-.3,-1,.15),16:(.3,-1,.15),23:(-.2,0,0),24:(.2,0,0)}.items():
        world[i]=point
    image=np.full((33,2),.5); image[15:17,1]=.2
    cases=[]
    definitions=[('left',[],[]),('right',[dict(frame=i,joint=15,visibility=0) for i in range(32)],[]),
        ('occlusion',[dict(frame=i,joint=16,visibility=0) for i in range(32)]+[dict(frame=12,joint=15,visibility=0)],[]),
        ('missing_pose',[],list(range(12,17))),('dropped_frame',[],list(range(8,13))),
        ('low_presence',[dict(frame=i,joint=15,presence=0) for i in range(32)],[])]
    for name,changes,missing in definitions:
        images=np.repeat(image[None],32,axis=0); worlds=np.repeat(world[None],32,axis=0)
        visibility=np.ones((32,33)); presence=visibility.copy(); detected=np.ones(32,dtype=bool)
        times=np.arange(32,dtype=np.int64)*33
        if name=='dropped_frame': times+=np.arange(32)%4*5
        for i in range(32):
            images[i,15:17,1]+=i*.001
            worlds[i,15:17,2]+=i*.001
        for change in changes:
            if 'visibility' in change: visibility[change['frame'],change['joint']]=change['visibility']
            if 'presence' in change: presence[change['frame'],change['joint']]=change['presence']
        detected[missing]=False
        values,quality,arm,_=window(images,worlds,visibility,presence,detected,times)
        expected=[[float(v) if np.isfinite(v) else None for v in row] for row in values]
        cases.append(dict(name=name,changes=changes,missing_frames=missing,timestamps_ms=times.tolist(),
            arm=arm,quality=quality,expected=expected))
    result=dict(world=world.tolist(),image=image.tolist(),cases=cases,feature_recipe=RECIPE,
        provenance='Synthetic geometry only; expected values from timestamp-based Python v2 recipe; not accuracy evidence')
    out=Path(__file__).resolve().parents[2]/'app/src/test/resources/blocking_arm_parity.json'
    out.write_text(json.dumps(result,indent=2,allow_nan=False),encoding='utf-8')

if __name__=='__main__': main()
