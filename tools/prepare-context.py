#!/usr/bin/env python3
"""Build small public-corpus context tables; no runtime training or user data."""
import argparse,collections,hashlib,json,re,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output',type=Path,required=True);parser.add_argument('--vocabulary',type=Path,required=True)
args=parser.parse_args();cache=ROOT/'.local/context';cache.mkdir(parents=True,exist_ok=True)
spec=json.loads((ROOT/'app/src/main/assets/context/sources.json').read_text())
output=args.output/'context';output.mkdir(parents=True,exist_ok=True)
pattern=re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*",re.UNICODE)
credits=['Offline word context: counts of public word pairs/triples, not neural weights.',
 'Sources: Universal Dependencies 2.18. Tables transformed by tools/prepare-context.py.',
 'Retain repeated prediction ngrams and spelling contrasts, using the bundled vocabulary.',
 'No user typing, names, offsets or usage statistics are collected.\n']
for source in spec['sources']:
 for file in source['files']:
  path=cache/file['name']
  if not path.exists():
   temporary=path.with_suffix('.part')
   subprocess.run(['curl','-fLsS','--retry','2',file['url'],'-o',str(temporary)],check=True)
   if hashlib.sha256(temporary.read_bytes()).hexdigest()!=file['sha256']:raise ValueError('Context source checksum mismatch: '+file['name'])
   temporary.replace(path)
  if hashlib.sha256(path.read_bytes()).hexdigest()!=file['sha256']:raise ValueError('Cached context source changed: '+file['name'])
 code=source['code'];words=set()
 for path in [ROOT/f'app/src/main/assets/languages/{code}.txt',args.vocabulary/'swipe-words'/(code+'.txt')]:
  words.update(line.split(' ',1)[0] for line in path.read_text().splitlines())
 if code=='en':words.update("i'm i've i'll i'd you're you've you'll you'd we're we've we'll we'd they're they've they'll they'd he's he'll he'd she's she'll she'd it's it'll that's there's here's what's who's how's isn't aren't wasn't weren't don't doesn't didn't can't couldn't shouldn't wouldn't won't hasn't haven't hadn't mustn't let's".split())
 if code=='it':words.update(["c'è","com'è","dov'è"])
 counts=collections.Counter()
 for line in (cache/(code+'-train.conllu')).read_text().splitlines():
  if not line.startswith('# text = '):continue
  tokens=pattern.findall(line[9:].lower().replace('’',"'"))
  for i in range(1,len(tokens)):
   if tokens[i] not in words:continue
   for size in [1,2]:
    if i>=size:counts[(tuple(tokens[i-size:i]),tokens[i])]+=1
 rows=[(context,word,count) for (context,word),count in counts.items() if count>=spec['min_count']]
 rows.sort(key=lambda row:(-row[2],len(row[0]),row[0],row[1]));rows=rows[:spec['max_ngrams']]
 contrasts={'en':{'its',"it's"},'it':{'ce',"c'è"}}.get(code,set())
 selected={(context,word) for context,word,_ in rows}
 for (context,word),count in counts.items():
  if word in contrasts and (context,word) not in selected:rows.append((context,word,count))
 (output/(code+'.tsv')).write_text(''.join(' '.join(h)+'\t'+w+'\t'+str(n)+'\n' for h,w,n in rows))
 credits.extend(['\n'+source['treebank']+' · '+source['license']])
 credits.extend(file['url'] for file in source['files'])
 credits.extend([(cache/(code+'-README.txt')).read_text(),(cache/(code+'-LICENSE.txt')).read_text()])
 print(code,len(rows),'public context ngrams')
(output/'CREDITS.txt').write_text('\n'.join(credits)+'\n')
