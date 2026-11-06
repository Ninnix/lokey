#!/usr/bin/env python3
"""Exercise Android app-language settings and restore the original preference."""
import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--label',default='localization-ui')
parser.add_argument('--locales',nargs='+',default=['en','it','de','ar','fa','ja','yue','fil','id'])
args=parser.parse_args()
assert re.fullmatch(r'[A-Za-z0-9_-]+',args.label)
adb=str(Path.home()/'Android/Sdk/platform-tools/adb')
package='org.lokey.app'
out=root/'artifacts'

def run(*command,timeout=90):
    return subprocess.check_output([adb,'-s',args.serial,*command],timeout=timeout)

def set_locale(code):
    command=['shell','cmd','locale','set-app-locales',package]
    if code:command+=['--locales',code]
    run(*command)

def decode(value):
    if value.startswith('"') and value.endswith('"'):value=value[1:-1]
    return re.sub(r'\\(.)',lambda m:{'n':'\n','t':'\t','r':'\r'}.get(m[1],m[1]),value)

def resources(code):
    folder='values' if code=='en' else 'values-in' if code=='id' else 'values-b+'+code.replace('-','+')
    return {row.attrib['name']:decode(''.join(row.itertext())) for row in ET.parse(root/'app/src/main/res'/folder/'strings.xml').getroot()}

original=re.search(r'\[(.*?)\]',run('shell','cmd','locale','get-app-locales',package).decode()).group(1)
awake=run('shell','settings','get','global','stay_on_while_plugged_in').decode().strip()
pages={'home':'ui_type_speak_one_flow','feel':'ui_keyboard_layout','models':'ui_small_models_close_to_you'}
results=[]
try:
    run('shell','svc','power','stayon','usb')
    run('shell','input','keyevent','KEYCODE_WAKEUP');run('shell','wm','dismiss-keyguard')
    for code in args.locales:
        set_locale(code)
        values=resources(code)
        for page,key in pages.items():
            run('shell','am','start','-W','-n',package+'/.MainActivity','--es','page',page,'-f','0x10008000')
            expected=' '.join(values[key].split())
            texts=[]
            for attempt in range(3):
                time.sleep(.5)
                run('shell','uiautomator','dump','/data/local/tmp/lokey-localization-ui.xml')
                tree=ET.fromstring(run('exec-out','cat','/data/local/tmp/lokey-localization-ui.xml'))
                texts=[' '.join(node.attrib.get('text','').split()) for node in tree.iter('node')]
                if expected in texts:break
            assert expected in texts,(code,page,expected,texts)
            name=f'{args.label}-{code}-{page}.png'
            (out/name).write_bytes(run('exec-out','screencap','-p'))
            results.append({'locale':code,'page':page,'expected':expected,'visible':True,'screenshot':str(out/name)})
            print(code,page,'passed',flush=True)
        (out/(args.label+'.json')).write_text(json.dumps({'passed':True,'cases':results},ensure_ascii=False,indent=2)+'\n')
finally:
    try:
        set_locale(original)
        run('shell','rm','-f','/data/local/tmp/lokey-localization-ui.xml')
    finally:
        if awake=='null':run('shell','settings','delete','global','stay_on_while_plugged_in')
        else:run('shell','settings','put','global','stay_on_while_plugged_in',awake)
