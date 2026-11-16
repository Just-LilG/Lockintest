import sys
from PIL import Image, ImageDraw
# usage: sheet.py out.png cols label=file ...
out_path=sys.argv[1]; cols=int(sys.argv[2]); items=[a.split('=',1) for a in sys.argv[3:]]
ims=[]
for lab,f in items:
    im=Image.open(f).convert('RGB'); ImageDraw.Draw(im).text((6,6),lab,fill=(198,244,50)); ims.append(im)
W,H=ims[0].size; rows=(len(ims)+cols-1)//cols
out=Image.new('RGB',(W*cols,H*rows))
for k,im in enumerate(ims): out.paste(im,((k%cols)*W,(k//cols)*H))
out.save(out_path)
