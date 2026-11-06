#!/usr/bin/env python3
"""Check Android locale coverage and formatting before building Lokey."""
import collections
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

root=Path(__file__).resolve().parents[1]
res=root/'app/src/main/res'
android='{http://schemas.android.com/apk/res/android}'
codes=[row.attrib[android+'name'] for row in ET.parse(res/'xml/locales_config.xml').getroot()]
catalog=(root/'app/src/main/java/org/lokey/app/LanguageLayouts.kt').read_text()
supported=set(re.findall(r'"[^"\n]+" to Mapping\("([^"\n]+)"',catalog))
assert len(supported)==39 and set(codes)==supported|{'zh-Hant'},'Locale configuration differs from the 39-language catalog'
assert len(codes)==len(set(codes))
format=re.compile(r'%%|%\d+\$(?:\.\d+)?[sdf]')

def strings(path):
    rows=ET.parse(path).getroot().findall('string')
    assert len(rows)==len({row.attrib['name'] for row in rows}),f'Duplicate resource in {path}'
    def decode(value):
        if value.startswith('"') and value.endswith('"'):value=value[1:-1]
        return re.sub(r'\\(.)',lambda m:{'n':'\n','t':'\t','r':'\r'}.get(m[1],m[1]),value)
    return {row.attrib['name']:decode(''.join(row.itertext())) for row in rows}

base=strings(res/'values/strings.xml')
summary=[]
for code in codes:
    folder='values' if code=='en' else 'values-in' if code=='id' else 'values-b+'+code.replace('-','+')
    path=res/folder/'strings.xml'
    values=strings(path)
    assert values.keys()==base.keys(),f'Missing or extra strings for {code}: {base.keys()^values.keys()}'
    assert values['ui_language_tag']==code
    for key,value in values.items():
        assert value.strip(),f'Empty {code}: {key}'
        assert collections.Counter(format.findall(value))==collections.Counter(format.findall(base[key])),f'Format mismatch {code}: {key}'
        assert not re.search(r'ZXQ\s*\d+\s*QXZ',value,re.I),f'Translation marker in {code}: {key}'
    summary.append({'locale':code,'strings':len(values)-1,'identical_to_english':sum(value==base[key] for key,value in values.items() if key!='ui_language_tag')})
print(json.dumps({'languages':len(supported),'locales':summary,'passed':True},ensure_ascii=False,indent=2))
