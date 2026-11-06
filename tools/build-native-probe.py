#!/usr/bin/env python3
"""Link a C diagnostic against the same runtime libraries as the Android app."""
import argparse
import json
import shlex
import subprocess
from pathlib import Path

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('source',type=Path)
parser.add_argument('--build',type=Path,default=root/'app/.cxx/Release/191y501p/arm64-v8a')
args=parser.parse_args()
build=args.build.resolve()
commands=json.loads((build/'compile_commands.json').read_text())
command=next(c for c in commands if c['file'].endswith('/main/c/lokey.c'))
compile=shlex.split(command['command'])
out=root/'.local/native-probes'/args.source.stem
out.parent.mkdir(parents=True,exist_ok=True)
compile[compile.index('-o')+1]=str(out)+'.o'
compile[compile.index('-c')+1]=str(args.source.resolve())
subprocess.run(compile,cwd=build,check=True)
link=subprocess.check_output(['ninja','-C',str(build),'-t','commands','lokey']).decode().splitlines()[-1]
parts=shlex.split(link)
libraries=[str(build/p) for p in parts if p.endswith('.a')]
compiler=compile[0]+'++'
target=[p for p in compile if p.startswith(('--target=','--sysroot='))]
subprocess.run([compiler,*target,'-static-libstdc++','-Wl,--gc-sections','-Wl,-z,max-page-size=16384',
                '-o',str(out),str(out)+'.o','-Wl,--start-group',*libraries,'-Wl,--end-group',
                '-llog','-ldl','-lm','-latomic','-pthread'],check=True)
subprocess.run([str(Path(compiler).with_name('llvm-strip')),'--strip-debug',str(out)],check=True)
print(out)
