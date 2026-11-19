#!/usr/bin/env python3
"""Rebuild the bundled GPOS kern metrics offline. Requires fonttools; run from repository root.
The eight TTFs are static 400/600 instances (Inter optical size 14; Roboto width 100).
No atlas generation or font processing occurs in a frame loop.
"""
from pathlib import Path
from fontTools.ttLib import TTFont
import struct, json
root=Path('src/client/resources/assets/scrimmyclient')
for p in (root/'font').glob('*/*.ttf'):
    f=TTFont(p); cmap=f.getBestCmap(); upem=f['head'].unitsPerEm
    pairs={}
    if 'GPOS' in f:
        features=f['GPOS'].table.FeatureList.FeatureRecord
        indexes={i for feat in features if feat.FeatureTag=='kern' for i in feat.Feature.LookupListIndex}
        for index in sorted(indexes):
            lookup=f['GPOS'].table.LookupList.Lookup[index]
            for sub in lookup.SubTable:
                if lookup.LookupType==9: sub=sub.ExtSubTable
                if not hasattr(sub,'PairSet') and not hasattr(sub,'Class1Record'): continue
                for a in range(32,256):
                    ga=cmap.get(a)
                    if ga not in sub.Coverage.glyphs: continue
                    for b in range(32,256):
                        gb=cmap.get(b);v=None
                        if sub.Format==1:
                            rec=sub.PairSet[sub.Coverage.glyphs.index(ga)]
                            for pair in rec.PairValueRecord:
                                if pair.SecondGlyph==gb: v=pair.Value1;break
                        elif gb:
                            v=sub.Class1Record[sub.ClassDef1.classDefs.get(ga,0)].Class2Record[sub.ClassDef2.classDefs.get(gb,0)].Value1
                        value=getattr(v,'XAdvance',0) if v else 0
                        if value: pairs[a,b]=pairs.get((a,b),0)+value
    data=b''.join(struct.pack('>HHf',a,b,value*11/upem) for (a,b),value in sorted(pairs.items()))
    p.with_suffix('.kern').write_bytes(data)
    print(p, len(pairs))
for p in (root/'font').glob('*.json'):
    j=json.loads(p.read_text());j['providers'][0]['oversample']=8;p.write_text(json.dumps(j,indent=2)+'\n')
