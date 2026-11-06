#!/usr/bin/env python3
import json
import re
import unicodedata
from pathlib import Path
root=Path(__file__).resolve().parents[1]
header=Path('/usr/include/X11/keysymdef.h').read_text()
keysyms={name:chr(int(cp,16)) for name,cp in re.findall(r'#define XK_(\w+)\s+\S+\s+/\* U\+([0-9A-Fa-f]+)',header)}
codes={name:code for name,code in re.findall(r'#define XK_(\w+)\s+(0x[0-9A-Fa-f]+)',header)}
unicode_by_code={codes[name]:value for name,value in keysyms.items() if name in codes}
keysyms.update({name:unicode_by_code[code] for name,code in codes.items() if code in unicode_by_code})
def char(name):
 name=name.strip()
 if re.fullmatch(r'U[0-9A-Fa-f]{4,8}',name): return chr(int(name[1:],16))
 if len(name)==1: return name
 return keysyms.get(name)
def extract(file,section,groups):
 source=Path('/usr/share/X11/xkb/symbols')/file
 block=re.search(r'xkb_symbols "'+section+r'"\s*\{(.*?)^\};',source.read_text(),re.S|re.M)[1]
 keys={}
 for key,values in re.findall(r'key\s*<(\w+)>\s*\{\s*\[([^]]+)\]',block):
  values=[char(value) for value in values.split(',')]
  if values[0]: keys[key]=values
 rows=[];shift={};alternates={}
 for group,limit in groups:
  selected=sorted((key,value) for key,value in keys.items() if key.startswith(group))[:limit]
  rows.append([value[0] for _,value in selected])
  for _,values in selected:
   base=values[0]
   if len(values)>1 and values[1] and values[1]!=base: shift[base]=values[1]
   extra=[value for value in values[1:] if value and value!=base and (not unicodedata.category(value[0]).startswith("C") or value in ["\u200c","\u200d"])]
   if extra: alternates[base]=list(dict.fromkeys(extra))
 return {'rows':rows,'shift':shift,'alternates':alternates}
data={
 'persian':extract('ir','pes',[('AD',12),('AC',11),('AB',10)]),
 'hindi':extract('in','deva',[('AD',12),('AC',11),('AB',10)]),
 'bolnagri':extract('in','bolnagri',[('AD',12),('AC',11),('AB',10)]),
 'thai':extract('th','basic',[('AE',12),('AD',12),('AC',11),('AB',10)]),
}
# Keep vowel marks omitted by the shortened number row on their related letters.
data['hindi']['alternates']['र']=['ृ','ऋ','ॄ','ॠ','ऱ']
for key,extra in {'ं':['ः'],'ै':['ॅ','ऍ'],'ो':['ॉ','ऑ'],'्':['\u200c','\u200d']}.items():
 data['hindi']['alternates'].setdefault(key,[]).extend(extra)
for key,extra in {'र':['ॄ','ॠ'],'ल':['ॣ','ॡ'],'न':['ं','ँ'],'श':['ॅ','ऍ'],'्':['ॉ','ऑ'],'द':['ड़','ढ़'],'य':['य़']}.items():
 data['bolnagri']['alternates'].setdefault(key,[]).extend(extra)
data['thai']['alternates'].setdefault('ื',[]).append('๎')
# Keep the obsolete Thai consonants available without adding another primary key.
data['thai']['alternates'].setdefault('ล',[]).extend(['ฃ','ฅ'])
def string(value): return json.dumps(value,ensure_ascii=False).replace('$','\\$')
def strings(values): return 'listOf('+','.join(string(value) for value in values)+')'
def mapping(values,lists=False):
 return 'mapOf('+','.join(string(key)+' to '+(strings(value) if lists else string(value)) for key,value in values.items())+')'
code=['package org.lokey.app','','// Native key data and mobile hold alternatives; see xkeyboard-config.txt.','object NativeLayouts {',
 '    data class Keys(val rows:List<List<String>>,val shift:Map<String,String>,val alternates:Map<String,List<String>>)']
for name,values in data.items():
 code.append('    val '+name+'=Keys(listOf('+','.join(strings(row) for row in values['rows'])+'),'+mapping(values['shift'])+','+mapping(values['alternates'],True)+')')
code.append('}')
(root/'app/src/main/java/org/lokey/app/NativeLayouts.kt').write_text('\n'.join(code)+'\n')
(root/'app/src/main/assets/licenses/xkeyboard-config.txt').write_text(Path('/usr/share/licenses/xkeyboard-config/COPYING').read_text())
print({key:[len(row) for row in value['rows']] for key,value in data.items()})
