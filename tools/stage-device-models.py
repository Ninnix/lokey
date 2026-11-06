#!/usr/bin/env python3
"""Copy existing workspace models into a debug installation without replacing its files."""
import argparse
import hashlib
import json
import shlex
import subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--models',nargs='+',choices=['lfm','qwen','parakeet','qwen_asr'],default=['lfm','qwen','parakeet','qwen_asr'])
parser.add_argument('--fixtures',action='store_true')
args=parser.parse_args()
adb=str(Path.home()/'Android/Sdk/platform-tools/adb')
models={'lfm':['LFM2.5-350M-Q4_K_M.gguf'],'qwen':['Qwen3.5-0.8B-Q4_K_M.gguf'],
        'parakeet':['parakeet-tdt-0.6b-v3-q4_k.gguf'],
        'qwen_asr':['Qwen3-ASR-0.6B-Q8_0.gguf','mmproj-Qwen3-ASR-0.6B-Q8_0.gguf']}
files=[]
for model in args.models:
    for name in models[model]:
        source=root/'.local'/('asr-models' if model=='qwen_asr' else 'models')/name
        files.append((source,'files/models/'+name))
if args.fixtures:
    files.extend([(root.parent/'whisper.cpp/samples/jfk.wav','files/jfk.wav'),
                  (root/'.local/asr-models/asr_zh.wav','files/asr-zh.wav'),
                  (root/'.local/asr-models/asr_en_16k.wav','files/asr-en.wav')])
def run(*command,**kwargs):
    return subprocess.run([adb,'-s',args.serial,*command],stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=300,**kwargs)
assert run('shell','run-as','org.lokey.app','mkdir','-p','files/models').returncode==0
report=[]
for source,target in files:
    assert source.is_file(),source
    exists=run('shell','run-as','org.lokey.app','test','-e',target).returncode==0
    if not exists:
        print('Copying '+source.name,flush=True)
        with source.open('rb') as stream:
            command=shlex.join(['run-as','org.lokey.app','sh','-c','cat > '+shlex.quote(target)])
            status=run('shell','-T',command,stdin=stream)
        assert status.returncode==0,status.stderr.decode()
    digest=run('shell','run-as','org.lokey.app','sha256sum',target)
    assert digest.returncode==0,digest.stderr.decode()
    actual=digest.stdout.decode().split()[0]
    if not exists:
        with source.open('rb') as stream: expected=hashlib.file_digest(stream,'sha256').hexdigest()
        assert actual==expected,(source,actual,expected)
    report.append({'file':target,'copied':not exists,'sha256':actual})
    print(('Kept ' if exists else 'Verified ')+source.name,flush=True)
(root/'artifacts'/('device-models-'+args.serial+'.json')).write_text(json.dumps(report,indent=2)+'\n')
