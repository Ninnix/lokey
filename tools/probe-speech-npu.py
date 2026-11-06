#!/usr/bin/env python3
"""Capture speech parity probes and native failures without launching the keyboard."""
import argparse
import fcntl
import json
import shlex
import subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--label',required=True)
parser.add_argument('--model',choices=['qwen','parakeet'],default='qwen')
parser.add_argument('--backend',choices=['auto','cpu','decoder','hexagon'],default='hexagon')
parser.add_argument('--op-filter',default='')
parser.add_argument('--fusion',choices=['0','1'],default='1')
parser.add_argument('--batch',type=int,default=2048)
parser.add_argument('--verbose',action='store_true')
parser.add_argument('--capture',action='store_true')
parser.add_argument('--dma64',choices=['0','1'],default='1')
parser.add_argument('fixtures',nargs='*',default=['asr-zh.wav'])
args=parser.parse_args()
device_lock=open('/tmp/lokey-inference-'+args.serial.replace('/','_')+'.lock','w')
fcntl.flock(device_lock,fcntl.LOCK_EX)
assert args.batch>0
assert all(c.isalnum() or c in '-_' for c in args.label)
adb=str(Path.home()/'Android/Sdk/platform-tools/adb');remote='/data/local/tmp/lokey-hexagon'
def run(*command):
    return subprocess.run([adb,'-s',args.serial,*command],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=300)
model,encoder=('asr.gguf','encoder.gguf') if args.model=='qwen' else ('parakeet.gguf','')
command=['env',f'ADSP_LIBRARY_PATH={remote}',f'CDSP_LIBRARY_PATH={remote}','GGML_HEXAGON_POWER_MODE=balanced',
         f'GGML_HEXAGON_DMA64={args.dma64}',
         *([f'LOKEY_ENCODER_DUMP={args.label}.f32'] if args.capture else []),
         f'GGML_HEXAGON_OPFILTER={args.op_filter}',f'GGML_HEXAGON_OPFUSION={args.fusion}',f'GGML_HEXAGON_OPBATCH={args.batch}',f'GGML_HEXAGON_VERBOSE={int(args.verbose)}',
         './npu-speech-probe',model,encoder,args.backend,*args.fixtures]
status=run('shell',f'cd {shlex.quote(remote)} && {shlex.join(command)} > {args.label}.json 2> {args.label}.log')
out=root/'artifacts'
for suffix in ['json','log']+(['f32'] if args.capture else []):
    result=run('exec-out','cat',f'{remote}/{args.label}.{suffix}')
    (out/f'{args.label}.{suffix}').write_bytes(result.stdout)
if status.returncode:
    print('Probe failed:',status.returncode,status.stderr.decode(errors='replace'))
    print((out/f'{args.label}.log').read_text(errors='replace')[-3500:])
else:
    data=json.loads((out/f'{args.label}.json').read_text())
    print(json.dumps(data,ensure_ascii=False,indent=2))
raise SystemExit(status.returncode)
