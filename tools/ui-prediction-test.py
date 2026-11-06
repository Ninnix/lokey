#!/usr/bin/env python3
"""Check the real keyboard's multilingual correction in Lokey's own editor."""
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = str(Path.home() / 'Android/Sdk/platform-tools/adb')
OUT = Path('artifacts')

def adb(*args):
    return subprocess.check_output([ADB, *map(str, args)], timeout=30)

def shell(*args):
    return adb('shell', *args)

def guard():
    state = shell('dumpsys', 'activity', 'activities').decode()
    focused = [line for line in state.splitlines() if 'topResumedActivity' in line or 'mResumedActivity' in line]
    if not any('org.lokey.app/.MainActivity' in line for line in focused):
        raise RuntimeError('Lokey is no longer foreground; stopping phone interaction.')

def value():
    guard(); shell('uiautomator', 'dump', '/data/local/tmp/lokey-prediction-ui.xml'); guard()
    data = adb('exec-out', 'cat', '/data/local/tmp/lokey-prediction-ui.xml')
    for node in ET.fromstring(data).iter('node'):
        if node.get('class') == 'android.widget.EditText' and node.get('package') == 'org.lokey.app':
            return node.get('text')
    raise AssertionError('Lokey editor not found')

shell('am', 'start', '-f', '0x10008000', '-n', 'org.lokey.app/.MainActivity', '--es', 'page', 'lab')
shell('ime', 'set', 'org.lokey.app/.LokeyService')
time.sleep(2)
w, h = map(int, re.search(rb'(\d+)x(\d+)', shell('wm', 'size')).groups())
density = int(re.search(rb'(\d+)', shell('wm', 'density')).group(1)) / 160
top = h - round(293 * density)

def tap(x, y):
    guard(); shell('input', 'tap', round(x), round(top + y * density))

cases = [('it', 'Ciao ci vediamo domnai', 'Ciao ci vediamo domani '),
         ('es', 'Hola muchas graciass', 'Hola muchas gracias '),
         ('fr', 'Bonjour merci beacoup', 'Bonjour merci beaucoup '),
         ('en', 'Hello I will recevie', 'Hello I will receive ')]
if len(sys.argv) > 1:
    cases = [case for case in cases if case[0] in sys.argv[1:]]
previous = OUT / 'oneplus-live-prediction.json'
results = json.loads(previous.read_text()).get('cases', []) if previous.exists() else []
for code, text, expected in cases:
    guard(); shell('input', 'keycombination', '113', '29'); guard(); shell('input', 'keyevent', 'KEYCODE_DEL')
    guard(); shell('input', 'text', text.replace(' ', '%s'))
    time.sleep(1)
    assert value() == text, (code, value())
    tap(w / 2, 264)
    assert value() == expected, (code, value(), expected)
    tap(w * .94, 212)
    assert value() == text, (code, 'Undo', value())
    results = [item for item in results if item['language'] != code]
    results.append({'language': code, 'space_corrected_typo': True, 'backspace_restored_original': True})
    previous.write_text(json.dumps({'passed': True, 'cases': results}, indent=2)+'\n')
    print(code + ': actual IME correction and undo passed', flush=True)
(OUT / 'oneplus-live-prediction.json').write_text(json.dumps({'passed': True, 'cases': results}, indent=2)+'\n')
