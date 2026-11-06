#!/usr/bin/env python3
"""Build offline conversion tables from the pinned sources in input-method-sources.json."""
import hashlib
import json
import re
import unicodedata as U
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'.local/jyutping'
ASSETS=ROOT/'app/src/main/assets'
metadata={}

def source(name,url,license):
    path=SOURCE/name
    metadata[name]={'url':url,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'license':license}
    return path.read_text()

def yaml_rows(text):
    for row in text.split('...',1)[1].splitlines():
        if row and not row.startswith('#'):
            fields=row.split('\t')
            if len(fields)>=2: yield fields

def write(name,entries):
    path=ASSETS/(name+'.tsv')
    path.write_text(''.join(f'{code}\t{text}\t{max(1,count)}\n' for (code,text),count in sorted(entries.items())))
    metadata[name]={'entries':len(entries),'bytes':path.stat().st_size,'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
    print(name,metadata[name])

revision=(SOURCE/'revision.txt').read_text().strip()
base=f'https://raw.githubusercontent.com/rime/rime-cantonese/{revision}/'
frequencies={}
for row in source('essay-cantonese.txt',base+'essay-cantonese.txt','CC-BY-4.0').splitlines():
    fields=row.split('\t')
    if len(fields)==2 and fields[1].isdigit(): frequencies[fields[0]]=int(fields[1])
entries={}
for name in ['jyut6ping3.chars.dict.yaml','jyut6ping3.words.dict.yaml','jyut6ping3.phrase.dict.yaml']:
    for text,reading,*weight in yaml_rows(source(name,base+name,'CC-BY-4.0')):
        syllables=reading.split()
        if not syllables or not all(re.fullmatch('[a-z]+[1-6]',s) for s in syllables): continue
        if len(text)>12 or name.endswith('phrase.dict.yaml') and len(text)>4 and text not in frequencies: continue
        count=max(1,frequencies.get(text,1))
        if weight and weight[0].endswith('%'):
            count=max(1,round(count*float(weight[0][:-1])/100))
        key="'".join(syllables),text
        entries[key]=max(entries.get(key,0),count)
write('jyutping',entries)

cj=json.loads((SOURCE/'cangjie-revision.json').read_text())['object']['sha']
base=f'https://raw.githubusercontent.com/rime/rime-cangjie/{cj}/'
full={};short={}
for name in ['cangjie5.base.dict.yaml','cangjie5.extended.dict.yaml']:
    for text,code,*_ in yaml_rows(source('cangjie-'+name,base+name,'GPL (table headers); LGPL-3.0 (repository LICENSE)')):
        if len(text)!=1 or not re.fullmatch('[a-z]{1,5}',code): continue
        count=max(1,frequencies.get(text,1))
        full[code,text]=count
        simple=code if len(code)==1 else code[0]+code[-1]
        short[simple,text]=max(short.get((simple,text),0),count)
write('cangjie',full);write('sucheng',short)

freq={}
for row in source('hi_full.txt','https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018/hi/hi_full.txt','CC-BY-SA-4.0').splitlines():
    text,count=row.rsplit(' ',1);freq[text]=int(count)
entries={}
for split in ['train','dev','test']:
    name=f'hi.translit.sampled.{split}.tsv'
    for row in source(name,'https://storage.googleapis.com/gresearch/dakshina/dakshina_dataset_v1.0.tar#dakshina_dataset_v1.0/hi/lexicons/'+name,'CC-BY-SA-4.0').splitlines():
        text,reading,count=row.split('\t')
        reading=reading.lower();text=U.normalize('NFC',text)
        if not re.fullmatch('[a-z]{1,40}',reading): continue
        score=max(1,freq.get(text,1))*int(count)
        entries[reading,text]=max(entries.get((reading,text),0),score)
write('hindi',entries)
(ASSETS/'input-method-sources.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n')
(ASSETS/'licenses/rime-cantonese.txt').write_text('Rime Cantonese / CanCLID contributors.\nhttps://github.com/rime/rime-cantonese/tree/'+revision+'\nModified into ranked TSV tables by tools/prepare-input-methods.py.\nThe ODbL jyut6ping3.maps file is not used.\n\n'+(SOURCE/'LICENSE-CC-BY').read_text())
(ASSETS/'licenses/rime-cangjie.txt').write_text('Rime Cangjie tables, revision '+cj+'\nhttps://github.com/rime/rime-cangjie/tree/'+cj+'\nCangjie 5 character tables and derived Sucheng first/last codes.\nThe upstream table headers say GPL; the repository LICENSE is LGPL-3.0. Both notices are preserved here.\nRebuildable with tools/prepare-input-methods.py from the sources and hashes in input-method-sources.json.\n\n'+(SOURCE/'cangjie-AUTHORS').read_text()+'\n'+(SOURCE/'cangjie-LICENSE').read_text()+'\n'+(SOURCE/'GPL-3.0').read_text())
(ASSETS/'licenses/hindi-transliteration.txt').write_text('Dakshina v1.0, Google Research (Roark et al., 2020).\nhttps://github.com/google-research-datasets/dakshina\nHindi train/dev/test lexicons combined and ranked using FrequencyWords Hindi 2018 (Hermit Dave, OpenSubtitles). These are input dictionaries, not evaluation benchmarks.\nhttps://github.com/hermitdave/FrequencyWords\nModified into a lower-case ranked TSV table by tools/prepare-input-methods.py.\nBoth datasets: CC BY-SA 4.0.\n\n'+(SOURCE/'CC-BY-SA-4.0').read_text())
