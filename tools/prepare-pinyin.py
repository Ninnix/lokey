#!/usr/bin/env python3
import hashlib
import json
import unicodedata
from pathlib import Path

root=Path(__file__).resolve().parents[1]
source=root/'.local/pinyin-data'
assets=root/'app/src/main/assets'

def reading(value):
    text=unicodedata.normalize('NFD',value.lower())
    out=[]
    for c in text:
        if c=='\u0308' and out and out[-1]=='u': out[-1]='v'
        elif not unicodedata.combining(c) and c.isalpha(): out.append(c)
    return ''.join(out)

frequencies={}
for row in (source/'frequency.txt').read_text().splitlines():
    word,count,*_=row.split()
    frequencies[word]=int(count)
chars={}
for row in (source/'pinyin.txt').read_text().splitlines():
    if not row.startswith('U+'): continue
    cp,values=row.split(':',1)
    chars[chr(int(cp[2:],16))]=[reading(value) for value in values.split('#')[0].strip().split(',')]
phrases={}
for row in (source/'phrases.txt').read_text().splitlines():
    if not row or row.startswith('#'): continue
    word,value=row.split(':',1)
    phrases[word]="'".join(reading(s) for s in value.split('#')[0].strip().split())
entries={}
for char,values in chars.items():
    if not 0x3400<=ord(char)<=0x9fff: continue
    for value in values:
        if value: entries[value,char]=max(1,frequencies.get(char,1))
for word,count in sorted(frequencies.items(),key=lambda item:-item[1])[:100000]:
    if not 2<=len(word)<=8 or any(c not in chars for c in word): continue
    value=phrases.get(word) or "'".join(chars[c][0] for c in word)
    if value: entries[value,word]=count
for word,value in phrases.items():
    if 2<=len(word)<=8 and all(c in chars for c in word): entries[value,word]=max(1,frequencies.get(word,1))
with (assets/'pinyin.tsv').open('w',encoding='utf8') as out:
    for (key,word),count in sorted(entries.items()): out.write(f'{key}\t{word}\t{count}\n')
urls={
 'pinyin.txt':'https://github.com/mozillazg/pinyin-data',
 'phrases.txt':'https://github.com/mozillazg/phrase-pinyin-data',
 'frequency.txt':'https://github.com/fxsjy/jieba',
}
metadata={name:{'source':url,'sha256':hashlib.sha256((source/name).read_bytes()).hexdigest(),'license':'MIT'} for name,url in urls.items()}
metadata['entries']=len(entries)
(assets/'pinyin-sources.json').write_text(json.dumps(metadata,indent=2)+'\n')
print(f'{len(entries)} Pinyin entries, {(assets/"pinyin.tsv").stat().st_size} bytes')
