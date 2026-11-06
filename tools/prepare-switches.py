#!/usr/bin/env python3
"""Slice the original Mechvibes recordings into preloaded press/release samples."""
import array
import json
import subprocess
import wave
from pathlib import Path

SOURCE = Path('.local/sounds')
TARGET = Path('app/src/main/assets/switches')
RATE = 44100
KEYS = {**{c: 'Key' + c.upper() for c in 'abcdefghijklmnopqrstuvwxyz'},
        **{c: 'Digit' + c for c in '0123456789'},
        'space': 'Space', 'enter': 'Enter', 'delete': 'Backspace', 'shift': 'ShiftLeft',
        'symbols': 'Tab', 'period': 'Period', 'comma': 'Comma'}

def write(path, samples):
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), 'wb') as output:
        output.setparams((1, 2, RATE, 0, 'NONE', 'not compressed'))
        output.writeframes(samples.tobytes())

for profile in ['cherry-red']:
    pcm = SOURCE / (profile + '.wav')
    subprocess.run(['ffmpeg', '-y', '-v', 'error', '-i', str(SOURCE / (profile + '.ogg')),
                    '-ac', '1', '-ar', str(RATE), '-c:a', 'pcm_s16le', str(pcm)], check=True)
    with wave.open(str(pcm), 'rb') as recording:
        samples = array.array('h', recording.readframes(recording.getnframes()))
    config = json.loads((SOURCE / (profile + '.json')).read_text())
    count = 0
    for key, original in KEYS.items():
        for phase, timing in zip(['down', 'up'], config['definitions'][original]['timing']):
            start, end = [round(value * RATE / 1000) for value in timing]
            clip = samples[start:end]
            peak = max(map(abs, clip), default=0)
            if peak < 120:
                continue
            threshold = max(100, peak * .035)
            first = next(i for i, sample in enumerate(clip) if abs(sample) > threshold)
            last = next(i for i in range(len(clip)-1, -1, -1) if abs(clip[i]) > threshold)
            clip = clip[max(0, first - 44):min(len(clip), last + 221)]
            # Preserve the recording's waveform and key-to-key level differences.
            clip = array.array('h', (round(sample * .8) for sample in clip))
            write(TARGET / profile.replace('-', '_') / f'{key}-{phase}.wav', clip)
            count += 1
    print(profile, count, 'recorded samples')
