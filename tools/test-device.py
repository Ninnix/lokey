#!/usr/bin/env python3
"""Run Lokey checks on an explicit device without replacing its app or models."""
import argparse
import fcntl
import json
import os
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--label',required=True)
parser.add_argument('--install-tests',action='store_true')
parser.add_argument('--speech-model',choices=['selected','parakeet','qwen_asr'],default='selected')
parser.add_argument('--accelerator',choices=['auto','cpu','hexagon'],default='auto')
parser.add_argument('--speech-accelerator',choices=['auto','cpu','decoder','hexagon'],default='auto')
parser.add_argument('modes',nargs='+')
args=parser.parse_args()
device_lock=open('/tmp/lokey-inference-'+args.serial.replace('/','_')+'.lock','w')
fcntl.flock(device_lock,fcntl.LOCK_EX)
assert all(c.isalnum() or c in '-_' for c in args.label)
adb=str(Path(os.environ.get('ANDROID_HOME',Path.home()/'Android/Sdk'))/'platform-tools/adb')
out=ROOT/'artifacts';out.mkdir(exist_ok=True)

def run(*command,timeout=60):
    return subprocess.check_output([adb,'-s',args.serial,*command],timeout=timeout)

preferences=ET.fromstring(run('exec-out','run-as','org.lokey.app','cat','shared_prefs/lokey.xml'))
prefs={node.attrib['name']:node.attrib.get('value',node.text) for node in preferences}
assert prefs.get('setup_complete')=='true' or set(args.modes)<= {'setup','languagemap'},'Finish setup on the phone before running these tests'
if args.install_tests:
    apk=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    print(run('install','-r',str(apk),timeout=180).decode().strip(),flush=True)
voice=args.speech_model
failures=[]
original_layout=prefs.get('layout','auto')
try:
    for mode in args.modes:
        run('shell','input','keyevent','KEYCODE_WAKEUP')
        run('shell','wm','dismiss-keyguard')
        layout='qwerty' if mode in ['gestures','interaction','flow','imeflow','cursor','correction','completion','sentence'] else original_layout
        options=['-e','mode',mode,'-e','layout',layout]
        if mode=='speech': options+=['-e','speech_model',voice,'-e','speech_accelerator',args.speech_accelerator]
        if mode=='models': options+=['-e','accelerator',args.accelerator]
        log=run('shell','am','instrument','-w',*options,'org.lokey.app.test/org.lokey.app.KeyboardTest',timeout=900)
        (out/f'{args.label}-{mode}.log').write_bytes(log)
        rows=[line.split('results=',1)[1] for line in log.decode().splitlines() if line.startswith('INSTRUMENTATION_RESULT: results=')]
        result=json.loads(rows[-1]) if rows else {'passed':False,'error':log.decode()}
        (out/f'{args.label}-{mode}.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        passed=result.get('passed') and mode in result and b'INSTRUMENTATION_CODE: -1' in log
        print(mode+(': passed' if passed else ': FAILED: '+result.get('error','No result').splitlines()[0]),flush=True)
        if not passed: failures.append(mode)
        if passed and mode=='homespeed':
            for name in ['home-clean','speed-selected-pinyin','speed-selected-persian','speed-selected-cyrillic']:
                (out/f'{args.label}-{name}.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat',f'files/{name}.png',timeout=180))
        if passed and mode=='automatic':
            for name in ['automatic-en','automatic-bg']:
                (out/f'{args.label}-{name}.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat',f'files/{name}.png',timeout=180))
        if passed and mode=='modelui':
            (out/f'{args.label}-models.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat','files/models-clean.png'))
            (out/f'{args.label}-credits.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat','files/models-credits.png'))
            (out/f'{args.label}-input-credits.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat','files/input-credits.png'))
        if passed and mode=='themes':
            for name in ['feel-grid','switch-grid']:
                (out/f'{args.label}-{name}.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat',f'files/{name}.png'))
        if passed and mode=='customizationui':
            for name in ['color-dark','color-light','preset-name','presets','add-key','edit-key','layout-editor','live-keyboard']:
                (out/f'{args.label}-{name}.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat',f'files/customization-{name}.png',timeout=180))
        if passed and mode=='intl':
            for name in ['intl-symbols','intl-popup-number','intl-popup-p','intl-popup-m','intl-popup-no-suggestions']:
                (out/f'{args.label}-{name}.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat',f'files/{name}.png'))
        if passed and mode=='popups':
            for name in ['o-acute','intl-labels','vietnamese-o']:
                (out/f'{args.label}-popup-{name}.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat',f'files/popup-{name}.png'))
        if passed and mode=='layoutui':
            (out/f'{args.label}-keyboard.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat','files/layout-live-intl.png'))
        if passed and mode=='insets':
            (out/f'{args.label}-keyboard.png').write_bytes(run('exec-out','run-as','org.lokey.app','cat','files/insets-live.png'))
finally:
    log=run('shell','am','instrument','-w','-e','mode','languagemap','-e','layout',original_layout,'org.lokey.app.test/org.lokey.app.KeyboardTest',timeout=120)
    (out/f'{args.label}-restore.log').write_bytes(log)
    assert b'"passed":true' in log,'Could not restore the keyboard layout'
assert not failures,failures
