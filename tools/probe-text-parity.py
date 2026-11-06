#!/usr/bin/env python3
"""Compare full text logits and recurrent-state restoration on CPU and Hexagon."""
import argparse
import fcntl
import array
import json
import math
import shlex
import subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--label',required=True)
parser.add_argument('--op-filter',default='')
parser.add_argument('--mm-select',choices=['0','1','2'],default='2')
parser.add_argument('--cpu-weights',default='')
parser.add_argument('--dma64',choices=['0','1'],default='1')
parser.add_argument('--trace',action='store_true')
parser.add_argument('--backends',nargs='+',choices=['cpu','htp'],default=['cpu','htp'])
parser.add_argument('--model',default='lfm.gguf')
parser.add_argument('--reference-model',help='F32 copy of the same weights, evaluated on CPU')
args=parser.parse_args()
device_lock=open('/tmp/lokey-inference-'+args.serial.replace('/','_')+'.lock','w')
fcntl.flock(device_lock,fcntl.LOCK_EX)
assert all(c.isalnum() or c in '-_' for c in args.label)
adb=str(Path.home()/'Android/Sdk/platform-tools/adb');remote='/data/local/tmp/lokey-hexagon'
out=root/'artifacts';results={};logits={}
def run(*command):
    return subprocess.check_output([adb,'-s',args.serial,*command],timeout=300)
jobs=[(backend,args.model,backend) for backend in args.backends]
if args.reference_model:
    jobs.append(('reference',args.reference_model,'cpu'))
for backend,model,device in jobs:
    name=args.label+'-'+backend
    env=['env',f'ADSP_LIBRARY_PATH={remote}',f'CDSP_LIBRARY_PATH={remote}','GGML_HEXAGON_POWER_MODE=balanced',
         f'GGML_HEXAGON_OPFILTER={args.op_filter}',f'GGML_HEXAGON_MM_SELECT={args.mm_select}',f'LOKEY_CPU_WEIGHTS={args.cpu_weights}',f'GGML_HEXAGON_DMA64={args.dma64}',
         *([f'LOKEY_TENSOR_DUMP={name}.trace'] if args.trace else [])]
    command=shlex.join([*env,'./npu-text-parity',model,device,name+'.bin'])
    run('shell',f'cd {remote} && {command} > {name}.json 2> {name}.log')
    for suffix in ['json','log','bin']+(['trace'] if args.trace else []):
        (out/(name+'.'+suffix)).write_bytes(run('exec-out','cat',remote+'/'+name+'.'+suffix))
    results[backend]=json.loads((out/(name+'.json')).read_text())
    raw=(out/(name+'.bin')).read_bytes();size=int.from_bytes(raw[:4],'little')
    values=array.array('f');values.frombytes(raw[4:]);assert len(values)==len(results[backend]['cases'])*2*size
    logits[backend]=[values[i:i+size] for i in range(0,len(values),size)]
def compare(left,right):
    rows=[]
    for i,(a,b) in enumerate(zip(logits.get(left,[]),logits.get(right,[]))):
        assert len(a)==len(b),'Vocabulary mismatch'
        assert all(math.isfinite(x) for x in a) and all(math.isfinite(x) for x in b),'Non-finite logits'
        dot=sum(x*y for x,y in zip(a,b));norm_a=sum(x*x for x in a);norm_b=sum(x*x for x in b)
        rows.append({'prompt':results[left]['cases'][i//2]['prompt'],'stage':'prefill' if i%2==0 else 'restored_decode',
                     'cosine':dot/math.sqrt(norm_a*norm_b),'rmse':math.sqrt(sum((x-y)**2 for x,y in zip(a,b))/len(a)),
                     'max_error':max(abs(x-y) for x,y in zip(a,b)),
                     'top1_same':max(range(len(a)),key=a.__getitem__)==max(range(len(b)),key=b.__getitem__)})
    return rows
rows=compare('cpu','htp')
summary={'op_filter':args.op_filter,'mm_select':args.mm_select,'dma64':args.dma64,'cpu_weights':args.cpu_weights,'backends':results,'logits':rows,
         'minimum_cosine':min((r['cosine'] for r in rows),default=None),'top1_agreement':sum(r['top1_same'] for r in rows),'cases':len(rows)}
if args.reference_model:
    summary['reference']={}
    for backend in args.backends:
        checks=compare(backend,'reference')
        summary['reference'][backend]={'logits':checks,'top1_agreement':sum(r['top1_same'] for r in checks),
                                       'cases':len(checks),'mean_rmse':sum(r['rmse'] for r in checks)/len(checks)}
(out/(args.label+'-summary.json')).write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in summary.items() if k not in ['backends','logits','reference']},indent=2))
for backend,checks in summary.get('reference',{}).items():
    print('F32 reference:',backend,{k:v for k,v in checks.items() if k!='logits'})
print('State restore errors:',{k:max(r['restore_max_error'] for r in v['cases']) for k,v in results.items()})
