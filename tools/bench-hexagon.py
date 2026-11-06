#!/usr/bin/env python3
"""Compare Lokey's completion code on CPU and Hexagon using installed models."""
import argparse
import fcntl
import hashlib
import json
import shlex
import statistics
import subprocess
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ADB=str(Path.home()/'Android/Sdk/platform-tools/adb')
REMOTE='/data/local/tmp/lokey-hexagon'
MODELS={'lfm':'LFM2.5-350M-Q4_K_M.gguf','qwen':'Qwen3.5-0.8B-Q4_K_M.gguf'}
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--remote-dir',default=REMOTE)
parser.add_argument('--label',default='oneplus15-hexagon')
parser.add_argument('--models',nargs='+',choices=MODELS,default=list(MODELS))
parser.add_argument('--device',choices=['cpu','htp','auto','both'],default='both')
parser.add_argument('--power-mode',choices=['balanced','high_performance','burst'],default='balanced')
parser.add_argument('--op-filter',default='')
parser.add_argument('--fusion',choices=['0','1'],default='1')
parser.add_argument('--collect',action='store_true',help='Collect already completed runs')
args=parser.parse_args()
device_lock=open('/tmp/lokey-inference-'+args.serial.replace('/','_')+'.lock','w')
fcntl.flock(device_lock,fcntl.LOCK_EX)
REMOTE=args.remote_dir
assert all(c.isalnum() or c in '-_' for c in args.label)
out=ROOT/'artifacts';out.mkdir(exist_ok=True)

def adb(*command,check=True):
    return subprocess.run([ADB,'-s',args.serial,*command],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=300,check=check)

devices=['cpu','htp'] if args.device=='both' else [args.device]
summary={'source_sha256_at_collection':hashlib.sha256((ROOT/'app/src/main/c/lokey.c').read_bytes()).hexdigest(),'serial':args.serial,'power_mode':args.power_mode,'models':{}}
for model in args.models:
    results={}
    for device in devices:
        name=f'{model}-{device}'
        if not args.collect:
            command=f'cd {shlex.quote(REMOTE)}; GGML_HEXAGON_OPFILTER={shlex.quote(args.op_filter)} GGML_HEXAGON_OPFUSION={args.fusion} GGML_HEXAGON_POWER_MODE={args.power_mode} ADSP_LIBRARY_PATH={shlex.quote(REMOTE)} CDSP_LIBRARY_PATH={shlex.quote(REMOTE)} ./keyboard-bench {model}.gguf {device} > {name}.json 2> {name}.log'
            run=adb('shell',command,check=False)
        log=adb('exec-out','cat',f'{REMOTE}/{name}.log').stdout
        (out/f'{args.label}-{name}.log').write_bytes(log)
        data=adb('exec-out','cat',f'{REMOTE}/{name}.json').stdout
        (out/f'{args.label}-{name}.json').write_bytes(data)
        if not args.collect: assert run.returncode==0,log.decode(errors='replace')[-3000:]
        value=json.loads(data);results[device]=value
        warm=[t['ms'] for t in value['trials'] if t['pass']>0 and not t['prompt'].startswith(('Ciao','C’é',"C'é"))]
        summary['models'].setdefault(model,{})[device]={'backend':value['device'],'load_ms':value['load_ms'],'warm_english_median_ms':statistics.median(warm),'trials':len(value['trials'])}
        print(f'{model} {device}: {statistics.median(warm):.1f} ms median, {value["load_ms"]:.1f} ms load',flush=True)
    if len(results)==2:
        cpu=summary['models'][model]['cpu']['warm_english_median_ms'];htp=summary['models'][model]['htp']['warm_english_median_ms']
        pairs=list(zip(results['cpu']['trials'],results['htp']['trials']))
        summary['models'][model]['speedup']=cpu/htp
        summary['models'][model]['identical_suggestions']=sum(c['suggestions']==h['suggestions'] for c,h in pairs)
        summary['models'][model]['differences']=[{'prompt':c['prompt'],'pass':c['pass'],'cpu':c['suggestions'],'htp':h['suggestions']} for c,h in pairs if c['suggestions']!=h['suggestions']]
        print(f'{model}: {cpu/htp:.2f}x speedup; {summary["models"][model]["identical_suggestions"]}/{len(pairs)} identical outputs',flush=True)
for name in ['ops-kquant','ops-gdn']:
    result=adb('exec-out','cat',f'{REMOTE}/{name}.log',check=False)
    if result.returncode==0: (out/f'{args.label}-{name}.log').write_bytes(result.stdout)
(out/f'{args.label}-summary.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n')
