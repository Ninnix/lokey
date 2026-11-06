#!/usr/bin/env python3
"""Map the model catalog and audit accents against pinned Unicode CLDR data."""
import argparse
import collections
import hashlib
import json
import re
import subprocess
import unicodedata as U
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT/'.local/language-audit'
BASE='https://raw.githubusercontent.com/unicode-org/cldr-json/49.0.0/cldr-json/'
PARENTS={'ayr':'ay','prs':'fa','dhd':'hi','ydd':'yi','khk':'mn','mhr':'chm','azj':'az','kmr':'ku','uzn':'uz','plt':'mg','dik':'din','als':'sq','tw':'ak','gaz':'om'}
LOCALES={**PARENTS,'prs':'fa-AF','zh-Hans':'zh','fuv':'ff','pbt':'ps'}
SCRIPT_NAMES={'Latn':'Latin QWERTY','Arab':'Arabic script','Cyrl':'Cyrillic','Deva':'Devanagari InScript','Beng':'Bengali / Assamese','Ethi':'Ethiopic','Mymr':'Burmese / Shan','Hant':'Traditional Chinese','Hans':'Chinese Pinyin','Hebr':'Hebrew script','Knda':'Kannada','Armn':'Armenian','Geor':'Georgian','Grek':'Greek','Gujr':'Gujarati','Jpan':'Japanese Romaji','Khmr':'Khmer','Kore':'Korean Hangul','Laoo':'Lao','Mlym':'Malayalam','Orya':'Odia','Guru':'Gurmukhi','Sinh':'Sinhala','Tibt':'Tibetan','Taml':'Tamil','Telu':'Telugu','Thai':'Thai Kedmanee'}
SPECIAL={'fr':'azerty','de':'qwertz','cs':'qwertz','sk':'qwertz','sl':'qwertz','hr':'qwertz','hu':'qwertz','bs':'qwertz','es':'spanish','pt':'portuguese','tr':'turkish','vi':'vietnamese','uk':'ukrainian','fa':'persian','prs':'persian','ckb':'persian','ks':'persian','pbt':'persian','ug':'persian'}
NATIVE={'Cyrl':'cyrillic','Arab':'arabic','Deva':'hindi','Grek':'greek','Hans':'pinyin','Kore':'korean','Jpan':'japanese','Thai':'thai'}
KEY_BASE={'æ':'a','œ':'o','ø':'o','ß':'s','ð':'d','þ':'t','ł':'l','đ':'d','ħ':'h','ı':'i','İ':'i','ɛ':'e','ɔ':'o','ŋ':'n','ɲ':'n','ɓ':'b','ɗ':'d','ƒ':'f','ɖ':'d','ɣ':'g','ɩ':'i','ɪ':'i','ʊ':'u','ʋ':'v','ʃ':'s','ʒ':'z','ə':'e','ǝ':'e','ɨ':'i','ƴ':'y','ƙ':'k','ƭ':'t','ƈ':'c','ɟ':'j','ǀ':'x','ǁ':'x','ǂ':'x','ǃ':'x'}
OLD={'a':'àáâäãåæāąă','e':'èéêëēėęě','i':'ìíîïīįıİ','o':'òóôöõøœōő','u':'ùúûüūůűų','c':'çćčċ','n':'ñńňņ','s':'ßśšșş','y':'ýÿŷ','w':'ŵẁẃẅ','d':'ðďđ','g':'ğģĝġ','h':'ħĥ','j':'ĵ','k':'ķ','l':'łĺľļ','r':'ŕř','t':'þťț','z':'žźż'}

def fetch(url,path):
    if path.exists(): return True
    result=subprocess.run(['curl','--location','--fail','--silent','--show-error','--retry','1','--max-time','30',url,'--output',str(path)],capture_output=True)
    if result.returncode:
        path.unlink(missing_ok=True)
        if b'404' not in result.stderr: raise RuntimeError(result.stderr.decode())
        return False
    return True

def characters(pattern):
    assert pattern.startswith('[') and pattern.endswith(']'),pattern
    pattern=pattern[1:-1];out=[];i=0
    while i<len(pattern):
        c=pattern[i];i+=1
        if c.isspace(): continue
        if c=='{':
            end=pattern.index('}',i);out.append(U.normalize('NFC',pattern[i:end]));i=end+1;continue
        if c=='\\':
            if pattern[i] in 'uU':
                size=4 if pattern[i]=='u' else 8;c=chr(int(pattern[i+1:i+1+size],16));i+=size+1
            else: c=pattern[i];i+=1
        if i+1<len(pattern) and pattern[i]=='-':
            end=pattern[i+1];out.extend(chr(v) for v in range(ord(c),ord(end)+1));i+=2
        else: out.append(U.normalize('NFC',c))
    return list(dict.fromkeys(out))

def clusters(value):
    out=[]
    for c in value:
        if U.category(c).startswith('M') and out: out[-1]+=c
        else: out.append(c)
    return [U.normalize('NFC',v) for v in out]

def native_characters(layout):
    source=(ROOT/'app/src/main/java/org/lokey/app/NativeLayouts.kt').read_text()
    if layout in ['hindi','bolnagri','persian','thai']:
        line=next(line for line in source.splitlines() if 'val '+layout+'=Keys(' in line)
        return set(''.join(json.loads(x.replace('\\$','$')) for x in re.findall(r'"(?:[^"\\]|\\.)*"',line)))
    source=(ROOT/'app/src/main/java/org/lokey/app/KeyboardLayouts.kt').read_text()
    names={'cyrillic':['cyrillic','ukrainian'],'ukrainian':['cyrillic','ukrainian'],'arabic':['arabic','arabicMarks'],'greek':['greek']}[layout]
    lines=[line for line in source.splitlines() if any('"'+name+'"' in line or 'val '+name+'=' in line for name in names)]
    chars=set(''.join(json.loads(x.replace('\\$','$')) for line in lines for x in re.findall(r'"(?:[^"\\]|\\.)*"',line)))
    if layout=='arabic': chars |= native_characters('persian')
    return chars

def key(value):
    if value in KEY_BASE: return KEY_BASE[value]
    parts=U.normalize('NFD',value).lower()
    first=parts[0]
    if first in KEY_BASE: return KEY_BASE[first]
    if first in 'abcdefghijklmnopqrstuvwxyz' and all(U.category(c).startswith('M') for c in parts[1:]): return first
    return None

def kotlin(value): return json.dumps(value,ensure_ascii=False).replace('$','\\$')
def strings(values): return 'listOf('+','.join(kotlin(v) for v in values)+')'

def generate(download):
    CACHE.mkdir(parents=True,exist_ok=True)
    codes=json.loads((ROOT/'data/supported-languages.json').read_text())
    if download:
        fetch('https://raw.githubusercontent.com/unicode-org/cldr/release-49/common/supplemental/likelySubtags.xml',CACHE/'likelySubtags.xml')
        with ThreadPoolExecutor(max_workers=8) as pool:
            list(pool.map(lambda c:fetch(BASE+'cldr-misc-full/main/'+c+'/characters.json',CACHE/(c+'.json')),sorted({LOCALES.get(c,c) for c in codes.values()})))
    likely={x.attrib['from']:x.attrib['to'] for x in ET.parse(CACHE/'likelySubtags.xml').iter('likelySubtag')}
    rows=[];usage=collections.defaultdict(list);per_language={};sources={}
    for name,code in codes.items():
        tag=code.replace('-','_')
        script=likely.get(tag,likely.get(code.split('-')[0],likely.get(PARENTS.get(code,''),''))).split('_')[1]
        locale=LOCALES.get(code,code);path=CACHE/(locale+'.json')
        required=[]
        if path.exists():
            data=json.loads(path.read_text());pattern=next(iter(data['main'].values()))['characters']['exemplarCharacters']
            required=characters(pattern)
            sources[locale]={'url':BASE+'cldr-misc-full/main/'+locale+'/characters.json','sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
        extra=list(dict.fromkeys(c for value in required for c in clusters(value) if any(ord(v)>127 for v in c) and any(U.category(v).startswith('L') for v in c)))
        layout=SPECIAL.get(code,NATIVE.get(script))
        target=SCRIPT_NAMES[script];status='available';note=''
        if script=='Latn':
            layout=SPECIAL.get(code,'qwerty' if extra or not required else 'intl')
            target={'azerty':'AZERTY','qwertz':'QWERTZ','spanish':'Spanish QWERTY','portuguese':'Portuguese QWERTY','turkish':'Turkish QWERTY','vietnamese':'Vietnamese Telex','intl':'QWERTY Intl','qwerty':'QWERTY Accent'}[layout]
            if required:
                options=collections.defaultdict(list)
                for c in extra:
                    base=key(c)
                    if base:
                        options[base].append(c);usage[c].append(name)
                per_language[name]=dict(options)
                unsupported=[c for c in extra if not key(c) and not all(v.lower() in 'abcdefghijklmnopqrstuvwxyz' or U.category(v).startswith('M') for v in c)]
                if unsupported: status='partial';note='Additional letters: '+' '.join(unsupported)
            else: status='review';note='Latin layout mapped; character inventory needs verification'
        elif name in ['Bengali','Hebrew','Urdu']:
            layout=None;status='excluded';note='Native keyboard excluded as requested'
        elif name=='Cantonese':
            layout='jyutping';target='Jyutping / Cangjie / Sucheng';note='Traditional characters; optional Jyutping tones, or shape codes'
            for method in ['jyutping','cangjie','sucheng']:
                asset=ROOT/'app/src/main/assets'/f'{method}.tsv'
                assert asset.exists() and asset.stat().st_size>100000,method+' conversion table missing'
            implementation=(ROOT/'app/src/main/java/org/lokey/app/KeyboardLayouts.kt').read_text()
            assert all('composer="'+method+'"' in implementation for method in ['jyutping','cangjie','sucheng'])
        elif script=='Hant':
            layout=None;target='Traditional Chinese Pinyin';status='missing';note='Traditional-character conversion is not implemented'
        elif layout is None:
            status='missing';note='Script keyboard is not implemented'
        elif not required:
            status='review';note='Shared script layout mapped; character inventory needs verification'
        elif layout not in ['pinyin','korean','japanese']:
            available=native_characters(layout)
            missing=sorted({c for value in required for c in value if (U.category(c).startswith(('L','M'))) and c.lower() not in available})
            if missing: status='partial';note='Additional characters needed: '+' '.join(missing)
        if locale!=code and required and code not in ['zh-Hans','azj','uzn','plt','als','tw','gaz']:
            if status=='available': status='review'
            note=('Parent-language inventory used; '+note).rstrip('; ')
        if name=='Chinese': note='Pinyin currently produces simplified characters'
        if name=='Hindi':
            note='InScript, Roman transliteration, or Bolnagri; ृ is a flick/hold on र in InScript'
            bolnagri=native_characters('bolnagri')
            missing=sorted({c for value in required for c in value if U.category(c).startswith(('L','M')) and c.lower() not in bolnagri})
            assert not missing,('Bolnagri',missing)
        rows.append({'name':name,'code':code,'script':script,'target':target,'layout':layout,'status':status,'note':note,'locale':locale if required else None,'characters':required})
    # The common menu retains familiar choices only when a mapped Latin language uses them.
    common={base:[c for c in values if c in usage or c=='İ' and 'ı' in usage] for base,values in OLD.items()}
    common={base:values for base,values in common.items() if values}
    removed=[c for base,values in OLD.items() for c in values if c not in common.get(base,[])]
    code=['package org.lokey.app','','// Generated from the model catalog and Unicode CLDR 49; see tools/prepare-language-layouts.py.','object LanguageLayouts {',
        '    data class Mapping(val code:String,val script:String,val target:String,val layout:String?,val status:String,val note:String)',
        '    val all=mapOf(']
    for row in rows:
        vals=[kotlin(row[x]) if row[x] is not None else 'null' for x in ['code','script','target','layout','status','note']]
        code.append('        '+kotlin(row['name'])+' to Mapping('+','.join(vals)+'),')
    aliases={parent:code for code,parent in PARENTS.items() if code in codes.values() and parent not in codes.values()}
    aliases.update({'tl':'fil'})
    code+=['    )','    private val codes=all.values.associateBy { it.code }','    private val aliases=mapOf('+','.join(kotlin(k)+' to '+kotlin(v) for k,v in aliases.items())+')',
        '    fun forCode(code:String):Mapping? {','        val base=code.substringBefore(\'-\')','        return codes[code] ?: codes[aliases[code]] ?: codes[base] ?: codes[aliases[base]]','    }','}']
    (ROOT/'app/src/main/java/org/lokey/app/LanguageLayouts.kt').write_text('\n'.join(code)+'\n')
    code=['package org.lokey.app','','// Audited main alphabets; the shared menu keeps common accents, with language-specific additions.','object LatinAccents {','    val common=mapOf(']
    for base,values in common.items(): code.append("        '"+base+"' to "+strings(values)+',')
    code+=['    )','    private val languages=mapOf(']
    for name,groups in per_language.items():
        if groups: code.append('        '+kotlin(name)+' to mapOf('+','.join("'"+base+"' to "+strings(vals) for base,vals in groups.items())+'),')
    code+=['    )','    private val names=LanguageLayouts.all.entries.associate { it.value.code to it.key }','    fun options(base:Char,language:String):List<String> {','        val name=if(language in languages) language else names[language] ?: names[language.substringBefore(\'-\')]','        val usual=common[base].orEmpty()','        val local=languages[name]?.get(base).orEmpty()','        return (local.filter { it !in usual }+usual.filter { it in local }+usual).distinct()','    }','}']
    (ROOT/'app/src/main/java/org/lokey/app/LatinAccents.kt').write_text('\n'.join(code)+'\n')
    report={'scope':'Parakeet v3 or Qwen3 ASR languages','cldr_version':'49','script_source':{'url':'https://raw.githubusercontent.com/unicode-org/cldr/release-49/common/supplemental/likelySubtags.xml','sha256':hashlib.sha256((CACHE/'likelySubtags.xml').read_bytes()).hexdigest()},'sources':sources,'mappings':rows,'common_accents':common,'removed_from_common':removed,'accent_languages':dict(usage)}
    (ROOT/'artifacts/language-layout-audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
    lines=['# Language and keyboard mapping','','Every one of the 39 languages listed by Parakeet v3 or Qwen3 ASR has an explicit code, default script, and an implemented input method. Broader text-model coverage does not expand Lokey’s supported-language list. Available means the audited main alphabet or a working script converter is present; it does not promise equal prediction quality or every regional typing convention.','',
        'Script defaults and main character inventories use [Unicode CLDR 49](https://github.com/unicode-org/cldr/tree/release-49). Language codes use ISO 639. Regional scripts can differ from the default shown here. A parent-language inventory is evidence for its shared script, not proof of a dialect\'s complete spelling system.','',
        'QWERTY Intl appears before QWERTY Accent. Plain Latin alphabets use Intl; accented Latin alphabets share Accent unless a regional layout or composing method is more appropriate. Common accent menus are retained only where a mapped language uses the character. Additional alphabet characters are offered for the selected language.','',
        '## Complete mapping','','| Language | Code | Script | Target keyboard | Status | Notes |','| --- | --- | --- | --- | --- | --- |']
    for r in rows: lines.append('| '+' | '.join(str(r[x] or '') for x in ['name','code','script','target','status','note'])+' |')
    lines+=['','## Common accent audit','','Removed from common menus: '+' '.join(removed)+'. These have no main-alphabet witness in the audited catalog; this is not a claim about all languages outside the catalog.','','| Key | Character | Mapped languages using it |','| --- | --- | --- |']
    for base,values in common.items():
        for c in values: lines.append('| '+base+' | '+c+' | '+', '.join(usage.get(c,usage.get('ı',[])))+' |')
    lines+=['','The audit uses main alphabets, rather than importing entire auxiliary repertoires, and includes combining sequences such as Dutch j́. A mapping marked review has no verified CLDR inventory. Script-specific coverage is reported separately from text-model coverage.','']
    (ROOT/'docs').mkdir(exist_ok=True)
    (ROOT/'docs/LANGUAGE-LAYOUTS.md').write_text('\n'.join(lines))
    print('Mapped',len(rows),'languages:',dict(collections.Counter(r['status'] for r in rows)))
    print('Common characters:',sum(map(len,common.values())),'removed:',' '.join(removed))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--download',action='store_true');generate(parser.parse_args().download)
