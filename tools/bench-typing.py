#!/usr/bin/env python3
"""Replay real keyboard touches into an isolated, cross-process Android editor."""
import argparse
import json
import os
from pathlib import Path
import subprocess

KEYBOARDS = {
    'lokey': 'org.lokey.app/.LokeyService',
    'gboard': 'com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME',
    'swiftkey': 'com.touchtype.swiftkey.beta/com.touchtype.KeyboardService',
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default=str(Path(os.environ.get('ANDROID_HOME', Path.home() / 'Android/Sdk')) / 'platform-tools/adb'))
    parser.add_argument('--serial')
    parser.add_argument('--label', default='typing')
    parser.add_argument('--output-dir', type=Path, default=Path('artifacts'))
    parser.add_argument('--orientation', choices=['portrait', 'landscape'],
                        help='Require this orientation in the benchmark editor.')
    parser.add_argument('--keyboards', nargs='+', choices=KEYBOARDS, default=list(KEYBOARDS))
    parser.add_argument('--intervals', nargs='+', type=int, default=[80, 40, 24])
    parser.add_argument('--rounds', type=int, default=3)
    parser.add_argument('--read-delay-ms', type=int, default=0)
    parser.add_argument('--terminal', action='store_true')
    parser.add_argument('--ime', help='Override component for one selected keyboard (e.g. stable SwiftKey).')
    args = parser.parse_args()
    if args.ime and len(args.keyboards) != 1:
        parser.error('--ime requires exactly one --keyboards entry')
    adb = [args.adb] + (['-s', args.serial] if args.serial else [])

    def run(*command, timeout=60):
        return subprocess.check_output(adb + list(command), timeout=timeout).decode()

    out = args.output_dir
    out.mkdir(parents=True, exist_ok=True)
    device = run('shell', 'getprop', 'ro.product.model').strip()
    installed = run('shell', 'ime', 'list', '-a', '-s').splitlines()
    report = {'device': device, 'terminal': args.terminal, 'read_delay_ms': args.read_delay_ms, 'results': []}
    orientation = ['-e', 'orientation', args.orientation] if args.orientation else []
    for name in args.keyboards:
        ime = args.ime or KEYBOARDS[name]
        if ime not in installed:
            report['results'].append({'keyboard': name, 'skipped': 'Not installed', 'ime': ime})
            continue
        version = run('shell', 'dumpsys', 'package', ime.split('/')[0])
        version = next((s.strip() for s in version.splitlines() if 'versionName=' in s), 'unknown')
        for interval in args.intervals:
            print(f'{name}: {12000 / interval:.0f} offered WPM, terminal={args.terminal}, read delay={args.read_delay_ms} ms', flush=True)
            log = run('shell', 'am', 'instrument', '-w', '-e', 'ime', ime,
                      '-e', 'terminal', str(args.terminal).lower(), '-e', 'read_delay_ms', str(args.read_delay_ms),
                      '-e', 'interval_ms', str(interval), '-e', 'rounds', str(args.rounds),
                      *orientation, 'org.lokey.benchmark/.TypingBenchmark', timeout=180)
            stem = f'{args.label}-{name}-{interval}'
            (out / f'{stem}.log').write_text(log)
            raw = next((line.split('results=', 1)[1] for line in log.splitlines()
                        if line.startswith('INSTRUMENTATION_RESULT: results=')), None)
            if raw is None:
                raise RuntimeError(f'Benchmark did not return a report; see {out / (stem + ".log")}')
            result = json.loads(raw)
            (out / f'{stem}.json').write_text(json.dumps(result, indent=2) + '\n')
            result.pop('keyboard_nodes', None)
            for row in result.get('rounds', []):
                row.pop('changes', None)
            result.update(keyboard=name, version=version)
            report['results'].append(result)
            (out / f'{args.label}.json').write_text(json.dumps(report, indent=2) + '\n')
            rows = [row for row in result.get('rounds', []) if not row['warmup']]
            print(json.dumps({'keyboard': name, 'passed': result.get('passed'), 'error': result.get('error'),
                              'correct': [row['correct'] for row in rows],
                              'p95_ms': [row['latency_p95_ms'] for row in rows],
                              'editor_reads': [sum(row['editor_reads'].values()) for row in rows]}), flush=True)
    if any(not row.get('passed') and not row.get('skipped') for row in report['results']):
        raise SystemExit(1)


if __name__ == '__main__':
    main()
