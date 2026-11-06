#!/usr/bin/env python3
"""Verify speech auto selection in a folder without Hexagon DSP kernels."""
import argparse
import fcntl
import json
import shlex
import subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
args=parser.parse_args()
device_lock=open('/tmp/lokey-inference-'+args.serial.replace('/','_')+'.lock','w')
fcntl.flock(device_lock,fcntl.LOCK_EX)
adb=str(Path.home()/'Android/Sdk/platform-tools/adb')
remote='/data/local/tmp/lokey-npu-fallback';models='/data/local/tmp/lokey-hexagon'
def run(*command):
    return subprocess.run([adb,'-s',args.serial,*command],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120)
assert run('shell','mkdir','-p',remote).returncode==0
assert run('push',str(root/'.local/native-probes/npu-speech-probe'),remote+'/speech-probe').returncode==0
results={}
for name,model,encoder in [('parakeet','parakeet.gguf',''),('qwen','asr.gguf',models+'/encoder.gguf')]:
    command=['env',f'ADSP_LIBRARY_PATH={remote}',f'CDSP_LIBRARY_PATH={remote}',
             './speech-probe',models+'/'+model,encoder,'auto',models+'/jfk.wav']
    status=run('shell',f'cd {remote} && {shlex.join(command)} > {name}.json 2> {name}.log')
    for suffix in ['json','log']:
        data=run('exec-out','cat',remote+'/'+name+'.'+suffix).stdout
        (root/'artifacts'/f'oneplus15-speech-fallback-{name}.{suffix}').write_bytes(data)
    assert status.returncode==0,(name,status.stderr.decode())
    value=json.loads((root/'artifacts'/f'oneplus15-speech-fallback-{name}.json').read_text())
    assert value['backend']=='CPU',value['backend']
    assert all('country' in t['text'].lower() and 'ask' in t['text'].lower() for t in value['trials'])
    results[name]=value
    print(name+': CPU fallback passed',flush=True)
(root/'artifacts/oneplus15-speech-fallback-summary.json').write_text(json.dumps(results,indent=2)+'\n')
