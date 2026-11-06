#!/usr/bin/env python3
"""Keep ranked Latin-script vocabulary; no text is downloaded at typing time."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.request import urlopen
import unicodedata

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/languages'
CODES = 'en it es fr pt de nl sv da fi pl cs sk ro hu tr hr sl et lt lv'.split()
BASE = 'https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/content/2018'

def fetch(code):
    url = f'{BASE}/{code}/{code}_50k.txt'
    try:
        data = urlopen(url, timeout=40).read().decode('utf-8')
    except Exception:
        data = urlopen(url.replace('_50k', '_full'), timeout=40).read().decode('utf-8')
    rows = []
    for line in data.splitlines():
        word, count = line.rsplit(' ', 1)
        if 1 <= len(word) <= 28 and word[0].isalpha() and word[-1].isalpha() and all(c.isalpha() or c in "'’" for c in word):
            word = word.lower()
            if code == 'en' and len(word) == 1 and word not in ('a', 'i'):
                continue
            folded = ''.join(c for c in unicodedata.normalize('NFD', word) if not unicodedata.combining(c)).replace('ł', 'l').replace('ø', 'o').replace('đ', 'd').replace('’', chr(39))
            rows.append(f'{word} {count} {folded}')
        if len(rows) == 8000:
            break
    ROOT.mkdir(parents=True, exist_ok=True)
    (ROOT / f'{code}.txt').write_text('\n'.join(dict.fromkeys(rows)) + '\n')
    return code, len(rows)

if __name__ == '__main__':
    with ThreadPoolExecutor(max_workers=6) as pool:
        for code, count in pool.map(fetch, CODES):
            print(code, count, flush=True)
