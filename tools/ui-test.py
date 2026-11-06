#!/usr/bin/env python3
"""Exercise the installed IME on the OnePlus at its normal portrait density."""
import json
import os
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ADB = str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platform-tools/adb')
OUT = Path('artifacts')
OUT.mkdir(exist_ok=True)

def adb(*args):
    return subprocess.check_output([ADB, *map(str, args)])

def shell(*args):
    return adb('shell', *args)

def guard():
    state = shell('dumpsys', 'activity', 'activities').decode()
    focused = [line for line in state.splitlines() if 'topResumedActivity' in line or 'mResumedActivity' in line]
    if not any('org.lokey.app/.MainActivity' in line for line in focused):
        raise RuntimeError('Lokey is no longer foreground; stopping phone interaction.')

def screenshot(name):
    guard()
    data = adb('exec-out', 'screencap', '-p')
    guard()
    (OUT / f'oneplus-{name}.png').write_bytes(data)

def editor():
    guard()
    shell('uiautomator', 'dump', '/data/local/tmp/lokey-ui.xml')
    guard()
    data = adb('exec-out', 'cat', '/data/local/tmp/lokey-ui.xml')
    for node in ET.fromstring(data).iter('node'):
        if node.get('class') == 'android.widget.EditText' and node.get('package') == 'org.lokey.app':
            return node.get('text')
    raise AssertionError('Editor not found')

shell('am', 'start', '-f', '0x10008000', '-n', 'org.lokey.app/.MainActivity', '--es', 'page', 'lab')
shell('ime', 'set', 'org.lokey.app/.LokeyService')
time.sleep(2)
size = list(map(int, re.search(rb'(\d+)x(\d+)', shell('wm', 'size')).groups()))
density = int(re.search(rb'(\d+)', shell('wm', 'density')).group(1)) / 160
w, h = size
assert w < h, 'Run this test in portrait'
top = h - round(293 * density)
margin, gap = 5 * density, 4 * density
step = (w - 2 * margin + gap) / 10

def tap(x, y):
    guard()
    shell('input', 'tap', round(x), round(top + y * density))

def key(c):
    if c in 'qwertyuiop':
        i = 'qwertyuiop'.index(c)
        tap(margin + step * (i + .5) - gap / 2, 116)
    elif c in 'asdfghjkl':
        i = 'asdfghjkl'.index(c)
        inset = 15 * density
        pitch = (w - 2 * margin - 2 * inset + gap) / 9
        tap(margin + inset + pitch * (i + .5) - gap / 2, 164)
    elif c in 'zxcvbnm':
        i = 'zxcvbnm'.index(c)
        pitch = (w - 2 * margin + gap) / 9.7
        tap(margin + pitch * (1.35 + i + .5) - gap / 2, 212)
    elif c == ' ':
        tap(w / 2, 264)
    elif c == '^':
        tap(w * .07, 212)
    else:
        raise ValueError(c)

shell('dumpsys', 'gfxinfo', 'org.lokey.app', 'reset')
for c in 'hello ^lokey':
    key(c)
assert editor() == 'Hello Lokey', editor()
screenshot('keyboard')

# A stationary swipe keeps the period held while the popup is captured.
period_x = w * .795
period_y = top + 264 * density
guard()
hold = subprocess.Popen([ADB, 'shell', 'input', 'swipe', str(round(period_x)), str(round(period_y)),
                         str(round(period_x)), str(round(period_y)), '1600'])
time.sleep(.8)
screenshot('punctuation')
assert hold.wait() == 0
assert editor() == 'Hello Lokey?'

guard()
shell('input', 'swipe', round(w / 2), round(top + 264 * density), round(w / 2 - 36 * density), round(top + 264 * density), 180)
key('q')
value = editor()
assert 'q' in value and not value.endswith('q'), value
# Remove the probe letter, leaving the sample intact.
tap(w * .94, 212)
assert editor() == 'Hello Lokey?'

# Start real microphone capture and confirm it stops after cancellation.
guard()
shell('input', 'swipe', round(w * .93), round(top + 264 * density), round(w * .93), round(top + 184 * density), 1200)
time.sleep(.4)
screenshot('voice')
running = shell('cmd', 'appops', 'get', 'org.lokey.app', 'RECORD_AUDIO').decode()
assert 'running' in running.lower(), running
tap(w - 35 * density, 24)
time.sleep(.4)
stopped = shell('cmd', 'appops', 'get', 'org.lokey.app', 'RECORD_AUDIO').decode()
assert 'running' not in stopped.lower(), stopped
assert editor() == 'Hello Lokey?'
(OUT / 'oneplus-frames.txt').write_bytes(shell('dumpsys', 'gfxinfo', 'org.lokey.app'))
(OUT / 'oneplus-live-tests.json').write_text(json.dumps({
    'typing_into_editor': True, 'period_popup': True, 'cursor_gesture': True,
    'microphone_opened': True, 'recording_locked_after_release': True, 'microphone_released_on_cancel': True,
    'cancel_did_not_insert_text': True, 'size': size, 'density': density,
}, indent=2) + '\n')
print('Live typing, punctuation, cursor, and microphone tests passed.')
