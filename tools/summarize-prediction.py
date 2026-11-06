#!/usr/bin/env python3
"""Summarize the on-device probabilitylanguages comparison without dropping misses."""
import argparse
from collections import defaultdict
import json
from pathlib import Path
from statistics import median

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('input',type=Path)
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--backend',default='HTP0')
parser.add_argument('--prompt',default='language_label')
args=parser.parse_args()
report=json.loads(args.input.read_text())
assert report['passed']
cases=report['probabilitylanguages']['cases']
groups=defaultdict(list)
for case in cases:
    assert case['valid']
    groups[case['model'],case['backend'],case['prompt']].append(case)
fixtures=None
rows=[]
shared={'English','Arabic','French','German','Portuguese','Spanish'}
for (model,backend,prompt),samples in sorted(groups.items()):
    keys={(c['language'],c['context'],c['prefix']) for c in samples}
    assert len(keys)==len(samples)==report['probabilitylanguages']['fixture_count']
    if fixtures is None: fixtures=keys
    assert keys==fixtures
    def score(items):
        return {'hits':sum(c['top3_hit'] for c in items),'count':len(items)}
    languages={language:score([c for c in samples if c['language']==language]) for language in sorted({c['language'] for c in samples})}
    rows.append({'model':model,'backend':backend,'prompt':prompt,**score(samples),
        'median_ms':median(c['ms'] for c in samples),'empty':sum(not c['words'] for c in samples),
        'prefix':score([c for c in samples if c['prefix']]),'next_word':score([c for c in samples if not c['prefix']]),
        'lfm_declared_language_subset':score([c for c in samples if c['language'] in shared]),'languages':languages})
summary={'source':str(args.input),'fixture_count':len(fixtures),'evaluations':len(cases),'results':rows,
    'models':report['probabilitylanguages'].get('models',[]),
    'limitations':['Hand-picked fixtures, including development regressions; not a held-out language benchmark.',
        'Reference words are not exhaustive; other completions can also be valid.',
        'Cold prompt evaluation with the same bounded search; model loading and cache reset are excluded from timing.',
        'Fixtures run once in a fixed order after model warmup.',
        'GGUF conversions differ across model families; these results do not isolate architecture alone.',
        'LFM does not list Italian, Russian or Greek among its supported languages.']}
args.output.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print('| Model | Reference hits | Prefix | Next word | Median ms |')
print('|---|---:|---:|---:|---:|')
for row in rows:
    if row['backend']!=args.backend or row['prompt']!=args.prompt: continue
    p,n=row['prefix'],row['next_word']
    print(f"| {row['model']} | {row['hits']}/{row['count']} | {p['hits']}/{p['count']} | {n['hits']}/{n['count']} | {row['median_ms']:.1f} |")
