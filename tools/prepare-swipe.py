#!/usr/bin/env python3
"""Prepare pinned public swipe vocabulary; never reads or stores user typing."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import unicodedata

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
cache=ROOT/'.local/swipe-words';cache.mkdir(parents=True,exist_ok=True)
spec=json.loads((ROOT/'app/src/main/assets/swipe-words/sources.json').read_text())
def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
for item in spec['files']:
    source=cache/(item['code']+'.source')
    if not source.exists():
        temporary=source.with_suffix('.part')
        subprocess.run(['curl','-fLsS','--retry','2',item['url'],'-o',str(temporary)],check=True)
        if digest(temporary)!=item['sha256']:raise ValueError('Swipe source checksum mismatch: '+item['code'])
        temporary.replace(source)
    if digest(source)!=item['sha256']:raise ValueError('Cached swipe source changed: '+item['code'])
    words={}
    for line in source.read_text().splitlines():
        word,count=line.rsplit(' ',1);word=word.lower()
        if len(word) not in range(2,25) or not word[0].isalpha() or not word[-1].isalpha() or not all(c.isalpha() or c in "'’" for c in word):continue
        folded=''.join(c for c in unicodedata.normalize('NFD',word) if not unicodedata.combining(c)).replace('ł','l').replace('ø','o').replace('đ','d').replace('’',"'")
        words.setdefault(word,(max(1,int(count)),folded))
        if len(words)>=spec['max_words']:break
    target=args.output/'swipe-words'/(item['code']+'.txt');target.parent.mkdir(parents=True,exist_ok=True)
    target.write_text(''.join(f'{word} {count} {folded}\n' for word,(count,folded) in words.items()))
    print(item['code'],len(words),'swipe words')
