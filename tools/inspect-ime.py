#!/usr/bin/env python3
"""Capture Lokey's keyboard and system navigation bounds on an explicit device."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--label',required=True)
parser.add_argument('--open-editor',action='store_true')
args=parser.parse_args()
assert all(c.isalnum() or c in '-_' for c in args.label)
adb=str(Path.home()/'Android/Sdk/platform-tools/adb')

def run(*command):
    return subprocess.check_output([adb,'-s',args.serial,*command],timeout=45)

def hierarchy():
    run('shell','uiautomator','dump','/sdcard/lokey-insets.xml')
    return run('exec-out','cat','/sdcard/lokey-insets.xml')

def bounds(node):
    return list(map(int,re.findall(r'\d+',node.get('bounds',''))))

if args.open_editor:
    run('shell','input','keyevent','KEYCODE_WAKEUP')
    run('shell','wm','dismiss-keyguard')
    run('shell','am','start','-n','org.lokey.app/.MainActivity','--es','page','lab')
    xml=ET.fromstring(hierarchy())
    editor=next(n for n in xml.iter('node') if n.get('package')=='org.lokey.app' and n.get('class')=='android.widget.EditText')
    l,t,r,b=bounds(editor)
    run('shell','input','tap',str((l+r)//2),str((t+b)//2))
    time.sleep(.5)
data=hierarchy();xml=ET.fromstring(data)
out=ROOT/'artifacts';out.mkdir(exist_ok=True)
(out/f'{args.label}.xml').write_bytes(data)
(out/f'{args.label}.png').write_bytes(run('exec-out','screencap','-p'))
nodes=[{'label':n.get('content-desc') or n.get('text'),'class':n.get('class'),'package':n.get('package'),'bounds':bounds(n)} for n in xml.iter('node') if n.get('content-desc') or n.get('text')]
report={'size':run('shell','wm','size').decode().strip(),'density':run('shell','wm','density').decode().strip(),'nodes':nodes}
(out/f'{args.label}.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
for node in nodes:
    if node['label'].lower().startswith(('space','done','hide','close','switch','backspace')) or 'keyboard' in node['label'].lower(): print(node)
