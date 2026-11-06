#!/usr/bin/env python3
"""Check IME insets under navigation modes and rotations, then restore the device."""
import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial',required=True)
parser.add_argument('--label',required=True)
args=parser.parse_args()
root=Path(__file__).resolve().parents[1]
adb=str(Path(os.environ.get('ANDROID_HOME',Path.home()/'Android/Sdk'))/'platform-tools/adb')

def shell(*command):
    for attempt in range(10):
        result=subprocess.run([adb,'-s',args.serial,'shell',*command],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30)
        output=result.stdout.decode().strip()
        if result.returncode==0: return output
        if attempt==9 or not any(word in output.lower() for word in ('not found','closed','offline','device disconnected')):
            raise RuntimeError(output)
        time.sleep(1)

overlays=shell('cmd','overlay','list')
original=re.findall(r'^\[x\] (com\.android\.internal\.systemui\.navbar\.[\w.]+)$',overlays,re.M)
assert len(original)==1,original
rotation=shell('wm','user-rotation').split()
assert rotation and rotation[0] in ('free','lock'),rotation
try:
    for navigation in ['threebutton','gestural']:
        package='com.android.internal.systemui.navbar.'+navigation
        assert package in overlays,package
        shell('cmd','overlay','enable-exclusive','--category',package)
        for orientation,value in [('portrait','0'),('landscape','1')]:
            shell('wm','user-rotation','lock',value)
            time.sleep(1)
            subprocess.run([sys.executable,str(root/'tools/test-device.py'),'--serial',args.serial,
                            '--label',f'{args.label}-{navigation}-{orientation}','insets'],check=True,cwd=root)
finally:
    shell('cmd','overlay','enable-exclusive','--category',original[0])
    shell('wm','user-rotation',*rotation)
