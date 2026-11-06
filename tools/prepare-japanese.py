#!/usr/bin/env python3
import concurrent.futures
import hashlib
import json
import math
import re
import urllib.request
from pathlib import Path

root=Path(__file__).resolve().parents[1]
source=root/'.local/japanese'
source.mkdir(parents=True,exist_ok=True)
files=json.loads((source/'files.json').read_text())
files=[item for item in files if re.fullmatch(r'dictionary\d+\.txt',item['name'])]

def fetch(item):
    target=source/item['name']
    if not target.exists() or target.stat().st_size!=item['size']:
        url=item['download_url']
        assert url.startswith('https://raw.githubusercontent.com/google/mozc/')
        with urllib.request.urlopen(url,timeout=60) as stream: target.write_bytes(stream.read())
    print(item['name'],target.stat().st_size,flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: list(pool.map(fetch,files))
entries={}
for item in files:
    for row in (source/item['name']).read_text().splitlines():
        columns=row.split('\t')
        if len(columns)<5: continue
        reading,_,_,cost,word=columns[:5]
        reading=''.join(chr(ord(c)-0x60) if 'ァ'<=c<='ヶ' else c for c in reading)
        if not 1<=len(reading)<=24 or not 1<=len(word)<=20 or any(not ('ぁ'<=c<='ゖ' or c=='ー') for c in reading): continue
        cost=int(cost)
        key=(reading,word)
        entries[key]=min(entries.get(key,cost),cost)
entries=sorted(entries.items(),key=lambda item:item[1])[:180000]
asset=root/'app/src/main/assets/japanese.tsv'
with asset.open('w') as out:
    for (reading,word),cost in sorted(entries):
        weight=max(1,min(60000000,round(60000000*math.exp(-max(0,cost)/1000))))
        out.write(f'{reading}\t{word}\t{weight}\n')
metadata={'source':'https://github.com/google/mozc/tree/master/src/data/dictionary_oss','entries':len(entries),
 'files':{item['name']:hashlib.sha256((source/item['name']).read_bytes()).hexdigest() for item in files}}
(root/'app/src/main/assets/japanese-sources.json').write_text(json.dumps(metadata,indent=2)+'\n')
with urllib.request.urlopen('https://raw.githubusercontent.com/google/mozc/master/LICENSE') as license:
    (root/'app/src/main/assets/licenses/mozc.txt').write_bytes(license.read())
print(len(entries),'Japanese entries',flush=True)
