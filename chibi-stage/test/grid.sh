#!/bin/sh
# Render every motion and tile them into one image: test/out/grid.png
cd "$(dirname "$0")/.." && node build.mjs >/dev/null && \
OUT=test/out node test/render.mjs "$1" "${2:-AvatarSample_C.vrm}" 200 300 >/dev/null && \
python3 - "$1" <<'PY'
import sys
from PIL import Image, ImageDraw
names=[s.split('@')[0]+('-'+s.split('@')[2] if s.count('@')>1 else '') for s in sys.argv[1].split(',')]
ims=[Image.open(f'test/out/{n}.png').convert('RGB') for n in names]
cols=min(6,len(ims)); rows=(len(ims)+cols-1)//cols
W,H=ims[0].size
o=Image.new('RGB',(W*cols,H*rows),'white'); d=ImageDraw.Draw(o)
for k,(n,i) in enumerate(zip(names,ims)):
    x,y=(k%cols)*W,(k//cols)*H; o.paste(i,(x,y)); d.text((x+4,y+4),n,fill='red')
o.save('test/out/grid.png')
PY
