#!/usr/bin/env python3
"""Fetch the exact dictionary sources needed by prepare-input-methods.py."""
import hashlib
import io
import json
import tarfile
from pathlib import Path
from urllib.request import Request, urlopen

ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT/'.local/jyutping'
CACHE.mkdir(parents=True,exist_ok=True)
sources=json.loads((ROOT/'app/src/main/assets/input-method-sources.json').read_text())
URL='https://storage.googleapis.com/gresearch/dakshina/dakshina_dataset_v1.0.tar'

class Remote(io.RawIOBase):
    def __init__(self): self.pos=0;self.start=-1;self.data=b''
    def tell(self): return self.pos
    def seek(self,offset,whence=0):
        assert whence in (0,1)
        self.pos=offset if whence==0 else self.pos+offset
        return self.pos
    def read(self,size=-1):
        assert size>=0
        if not (self.start<=self.pos and self.pos+size<=self.start+len(self.data)):
            request=Request(URL+'?offset='+str(self.pos),headers={'Range':f'bytes={self.pos}-{self.pos+max(size,65536)-1}'})
            with urlopen(request,timeout=45) as response:
                assert response.status==206,'Server must support byte ranges'
                self.start=self.pos;self.data=response.read()
        start=self.pos-self.start;out=self.data[start:start+size];self.pos+=len(out)
        return out

needed={name for name,info in sources.items() if 'url' in info and '#' in info['url'] and not (CACHE/name).exists()}
if needed:
    # Seek over the unrelated language files instead of downloading the full archive.
    with tarfile.open(fileobj=Remote(),mode='r:') as archive:
        for member in archive:
            name=Path(member.name).name
            if '/hi/lexicons/' in member.name and name in needed and member.isfile():
                (CACHE/name).write_bytes(archive.extractfile(member).read());needed.remove(name)
                if not needed: break
    assert not needed,needed

for name,info in sources.items():
    if 'url' not in info: continue
    path=CACHE/name
    if not path.exists():
        with urlopen(info['url'],timeout=45) as response: path.write_bytes(response.read())
    assert hashlib.sha256(path.read_bytes()).hexdigest()==info['sha256'],name+' source hash changed'
    print(name,flush=True)

jyut=sources['essay-cantonese.txt']['url'].rsplit('/',1)[0]
cangjie=sources['cangjie-cangjie5.base.dict.yaml']['url'].rsplit('/',1)[0]
(CACHE/'revision.txt').write_text(jyut.rsplit('/',1)[1]+'\n')
(CACHE/'cangjie-revision.json').write_text(json.dumps({'object':{'sha':cangjie.rsplit('/',1)[1]}})+'\n')
for name,url in {
    'LICENSE-CC-BY':jyut+'/LICENSE-CC-BY',
    'cangjie-AUTHORS':cangjie+'/AUTHORS',
    'cangjie-LICENSE':cangjie+'/LICENSE',
    'GPL-3.0':'https://www.gnu.org/licenses/gpl-3.0.txt',
    'CC-BY-SA-4.0':'https://creativecommons.org/licenses/by-sa/4.0/legalcode.txt',
}.items():
    path=CACHE/name
    if not path.exists():
        with urlopen(url,timeout=45) as response: path.write_bytes(response.read())
