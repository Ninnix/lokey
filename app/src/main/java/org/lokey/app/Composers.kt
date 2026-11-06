package org.lokey.app

import java.text.Normalizer

object Composers {
    fun accepts(kind:String,value:String)=value.isNotEmpty() && when(kind) {
        "korean" -> value.all { it in 'ㄱ'..'ㅣ' }
        "pinyin" -> value.all { it.lowercaseChar() in 'a'..'z' || it=='\'' || it=='ü' || it=='Ü' }
        "jyutping" -> value.all { it.lowercaseChar() in 'a'..'z' || it=='\'' || it in '1'..'6' }
        "cangjie","sucheng" -> value.all { it.lowercaseChar() in 'a'..'z' || it=='\'' }
        "hindi_phonetic" -> value.all { it.lowercaseChar() in 'a'..'z' || it=='\'' }
        "japanese" -> value.all { it.lowercaseChar() in 'a'..'z' || it=='\'' || it=='-' || it=='ー' }
        "vietnamese" -> value.all { it.isLetter() }
        else -> false
    }
    fun display(kind:String,raw:String,final:Boolean=false)=when(kind) {
        "korean" -> Hangul.render(raw)
        "japanese" -> Japanese.kana(raw,final)
        "vietnamese" -> Telex.render(raw)
        "hindi_phonetic" -> Hindi.render(raw,final)
        else -> raw
    }
    fun delete(kind:String,raw:String)=when(kind) {
        "japanese" -> Japanese.delete(raw)
        "vietnamese" -> Telex.render(raw).dropLast(1)
        else -> raw.dropLast(1)
    }
}

object Hindi {
    private val vowels=mapOf("a" to ("अ" to ""),"aa" to ("आ" to "ा"),"i" to ("इ" to "ि"),"ii" to ("ई" to "ी"),"ee" to ("ई" to "ी"),"u" to ("उ" to "ु"),"uu" to ("ऊ" to "ू"),"oo" to ("ऊ" to "ू"),"e" to ("ए" to "े"),"ai" to ("ऐ" to "ै"),"o" to ("ओ" to "ो"),"au" to ("औ" to "ौ"))
    private val consonants=mapOf("k" to "क","kh" to "ख","g" to "ग","gh" to "घ","ng" to "ङ","ch" to "च","chh" to "छ","c" to "च","j" to "ज","jh" to "झ","ny" to "ञ","t" to "त","th" to "थ","d" to "द","dh" to "ध","n" to "न","p" to "प","ph" to "फ","b" to "ब","bh" to "भ","m" to "म","y" to "य","r" to "र","l" to "ल","v" to "व","w" to "व","sh" to "श","s" to "स","h" to "ह","q" to "क़","x" to "ख़","z" to "ज़","f" to "फ़","T" to "ट","Th" to "ठ","D" to "ड","Dh" to "ढ","N" to "ण","Sh" to "ष")
    fun render(raw:String,final:Boolean=false):String {
        val out=StringBuilder();var index=0
        while(index<raw.length) {
            val token=(3 downTo 1).mapNotNull { length -> raw.substring(index,minOf(index+length,raw.length)).takeIf { it.length==length && (it in vowels || it in consonants || it in listOf("M","H","R","'")) } }.firstOrNull()
            if(token==null) { out.append(raw[index++]);continue }
            val vowel=vowels[token];val consonant=consonants[token]
            when {
                vowel!=null -> if(out.endsWith("्")) { out.setLength(out.length-1);out.append(vowel.second) } else out.append(vowel.first)
                consonant!=null -> out.append(consonant).append('्')
                token=="M" || token=="H" -> { if(out.endsWith("्")) out.setLength(out.length-1);out.append(if(token=="M") 'ं' else 'ः') }
                token=="R" -> if(out.endsWith("्")) { out.setLength(out.length-1);out.append('ृ') } else out.append('ऋ')
                else -> if(out.endsWith("्")) out.setLength(out.length-1)
            }
            index+=token.length
        }
        if(final && out.endsWith("्")) out.setLength(out.length-1)
        return out.toString()
    }
}

object Hangul {
    private const val initials="ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    private const val vowels="ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    private const val finals=" ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"
    private val vowelPairs=mapOf("ㅗㅏ" to 'ㅘ',"ㅗㅐ" to 'ㅙ',"ㅗㅣ" to 'ㅚ',"ㅜㅓ" to 'ㅝ',"ㅜㅔ" to 'ㅞ',"ㅜㅣ" to 'ㅟ',"ㅡㅣ" to 'ㅢ',"ㅘㅣ" to 'ㅙ',"ㅝㅣ" to 'ㅞ')
    private val finalPairs=mapOf("ㄱㅅ" to 'ㄳ',"ㄴㅈ" to 'ㄵ',"ㄴㅎ" to 'ㄶ',"ㄹㄱ" to 'ㄺ',"ㄹㅁ" to 'ㄻ',"ㄹㅂ" to 'ㄼ',"ㄹㅅ" to 'ㄽ',"ㄹㅌ" to 'ㄾ',"ㄹㅍ" to 'ㄿ',"ㄹㅎ" to 'ㅀ',"ㅂㅅ" to 'ㅄ')
    private val double=mapOf('ㄱ' to 'ㄲ','ㄷ' to 'ㄸ','ㅂ' to 'ㅃ','ㅅ' to 'ㅆ','ㅈ' to 'ㅉ')
    fun render(input:String):String {
        val out=StringBuilder();var l=-1;var v=-1;var t=0
        fun flush() {
            if(l>=0 && v>=0) out.append((0xac00+(l*21+v)*28+t).toChar())
            else if(l>=0) out.append(initials[l])
            else if(v>=0) out.append(vowels[v])
            l=-1;v=-1;t=0
        }
        for(c in input) {
            val vowel=vowels.indexOf(c);val initial=initials.indexOf(c)
            if(vowel>=0) {
                if(v<0) v=vowel
                else if(t>0) {
                    val ending=finals[t];val pair=finalPairs.entries.firstOrNull { it.value==ending }?.key
                    val next=pair?.get(1) ?: ending
                    t=pair?.get(0)?.let { finals.indexOf(it) } ?: 0
                    flush();l=initials.indexOf(next);v=vowel
                } else {
                    val combined=vowelPairs["${vowels[v]}$c"]
                    if(combined!=null) v=vowels.indexOf(combined) else { flush();v=vowel }
                }
            } else if(initial>=0) {
                if(l<0 && v<0) l=initial
                else if(l>=0 && v<0 && initials[l]==c && double[c]!=null) l=initials.indexOf(double[c]!!)
                else if(l>=0 && v>=0 && t==0 && finals.indexOf(c)>0) t=finals.indexOf(c)
                else if(t>0 && finalPairs["${finals[t]}$c"]!=null) t=finals.indexOf(finalPairs["${finals[t]}$c"]!!)
                else { flush();l=initial }
            } else { flush();out.append(c) }
        }
        flush();return out.toString()
    }
}

object Japanese {
    private val mapping=buildMap {
        val data="a あ i い u う e え o お ka か ki き ku く ke け ko こ sa さ shi し si し su す se せ so そ ta た chi ち ti ち tsu つ tu つ te て to と na な ni に nu ぬ ne ね no の ha は hi ひ fu ふ hu ふ he へ ho ほ ma ま mi み mu む me め mo も ya や yu ゆ yo よ ra ら ri り ru る re れ ro ろ wa わ wo を ga が gi ぎ gu ぐ ge げ go ご za ざ ji じ zi じ zu ず ze ぜ zo ぞ da だ di ぢ du づ de で do ど ba ば bi び bu ぶ be べ bo ぼ pa ぱ pi ぴ pu ぷ pe ぺ po ぽ vu ゔ fa ふぁ fi ふぃ fe ふぇ fo ふぉ va ゔぁ vi ゔぃ ve ゔぇ vo ゔぉ she しぇ che ちぇ je じぇ wi うぃ we うぇ thi てぃ dhi でぃ tsa つぁ tsi つぃ tse つぇ tso つぉ xa ぁ xi ぃ xu ぅ xe ぇ xo ぉ xya ゃ xyu ゅ xyo ょ xtu っ xtsu っ xwa ゎ xka ゕ xke ゖ"
        val words=data.split(' ');for(i in words.indices step 2) put(words[i],words[i+1])
        for((roman,kana) in listOf("ky" to "き","gy" to "ぎ","sh" to "し","sy" to "し","ch" to "ち","ty" to "ち","ny" to "に","hy" to "ひ","by" to "び","py" to "ぴ","my" to "み","ry" to "り","jy" to "じ","zy" to "じ"))
            for((vowel,small) in listOf("a" to "ゃ","u" to "ゅ","o" to "ょ")) put(roman+vowel,kana+small)
        for((key,value) in toMap()) if(key.startsWith('x')) put("l"+key.drop(1),value)
    }
    fun hiragana(value:String)=value.map { if(it in 'ァ'..'ヶ') (it.code-0x60).toChar() else it }.joinToString("")
    fun kana(input:String,final:Boolean=false):String {
        val raw=input.lowercase().replace('ー','-');val out=StringBuilder();var i=0
        while(i<raw.length) {
            val c=raw[i]
            if(c=='-') { out.append('ー');i++;continue }
            if(c=='n') {
                val next=raw.getOrNull(i+1)
                if(next=='\'') { out.append('ん');i+=2;continue }
                if(next=='n') { out.append('ん');i+=if(raw.getOrNull(i+2) in listOf('a','i','u','e','o','y')) 1 else 2;continue }
                if(next==null && final || next!=null && next !in "aiueoy") { out.append('ん');i++;continue }
            }
            if(c!='n' && c !in "aiueo" && c==raw.getOrNull(i+1)) { out.append('っ');i++;continue }
            var found=false
            for(length in minOf(4,raw.length-i) downTo 1) {
                val part=raw.substring(i,i+length);val value=mapping[part] ?: continue
                out.append(value);i+=length;found=true;break
            }
            if(!found) { out.append(c);i++ }
        }
        val katakana=input.firstOrNull()?.isUpperCase()==true
        return if(katakana) out.map { if(it in 'ぁ'..'ゖ') (it.code+0x60).toChar() else it }.joinToString("") else out.toString()
    }
    fun delete(raw:String):String {
        val current=kana(raw)
        if(current.lastOrNull()?.let { it.lowercaseChar() in 'a'..'z' }==true) return raw.dropLast(1)
        val target=hiragana(current.dropLast(1));val out=StringBuilder();var index=0
        val reverse=mapping.entries.sortedBy { it.key.length }.associateBy({ it.value },{ it.key }).toMutableMap().apply { put("ん","n'");put("ー","-") }
        while(index<target.length) {
            val pair=if(index+2<=target.length) target.substring(index,index+2) else ""
            val double=reverse[pair]
            if(double!=null) { out.append(double);index+=2 } else { out.append(reverse[target[index].toString()] ?: target[index].toString());index++ }
        }
        return if(raw.firstOrNull()?.isUpperCase()==true) out.toString().replaceFirstChar { it.uppercase() } else out.toString()
    }
}

object Telex {
    private val tones=mapOf('s' to '\u0301','f' to '\u0300','r' to '\u0309','x' to '\u0303','j' to '\u0323')
    private val toneMarks=tones.values.toSet()
    private fun base(c:Char)=Normalizer.normalize(c.toString(),Normalizer.Form.NFD).first().lowercaseChar()
    private fun clean(value:String)=Normalizer.normalize(Normalizer.normalize(value,Normalizer.Form.NFD).filter { it !in toneMarks },Normalizer.Form.NFC)
    private fun decorate(c:Char,mark:Char):String {
        val text=Normalizer.normalize(c.toString(),Normalizer.Form.NFD)
        val tones=text.filter { it in toneMarks }
        val stem=text.filter { it !in toneMarks && it!=mark }
        return Normalizer.normalize(stem+mark+(if(mark in toneMarks) "" else tones),Normalizer.Form.NFC)
    }
    private fun tone(value:String,key:Char):String? {
        val text=clean(value);var indices=text.indices.filter { base(text[it]) in "aeiouy" }
        if(indices.size>1 && text.startsWith("qu",true)) indices=indices.filter { it!=1 }
        if(indices.size>1 && text.startsWith("gi",true)) indices=indices.filter { it!=1 }
        if(indices.isEmpty()) return null
        if(key=='z') return text
        val marked=indices.lastOrNull { clean(text[it].toString()).lowercase() in listOf("ê","ơ") }
        val position=marked ?: if(indices.size==1) indices[0] else if(indices.size>=3) indices[indices.size-2] else if(indices.last()<text.lastIndex) indices.last() else indices.first()
        return text.replaceRange(position,position+1,decorate(text[position],tones[key]!!))
    }
    fun render(raw:String):String {
        var text="";var previous:Char?=null;var undo:String?=null
        for(c in raw) {
            val key=c.lowercaseChar()
            if(key==previous && undo!=null) { text=undo+c;undo=null;previous=key;continue }
            var next:String?=null
            if(key in tones || key=='z') next=tone(text,key)
            val last=text.lastOrNull()
            if(next==null && last!=null) {
                val mark=when { key=='a' && base(last)=='a' -> '\u0302';key=='e' && base(last)=='e' -> '\u0302';key=='o' && base(last)=='o' -> '\u0302';key=='w' && base(last)=='a' -> '\u0306';key=='w' && base(last) in "uo" -> '\u031b';else -> null }
                if(mark!=null) {
                    next=text.dropLast(1)+decorate(last,mark)
                    if(key=='w' && text.length>=2 && base(text[text.lastIndex-1])=='u' && base(last)=='o')
                        next=text.dropLast(2)+decorate(text[text.lastIndex-1],mark)+decorate(last,mark)
                }
                if(key=='d' && last.lowercaseChar()=='d') next=text.dropLast(1)+if(last.isUpperCase()) "Đ" else "đ"
            }
            if(next!=null) { undo=text;text=next } else { text+=c;undo=null }
            previous=key
        }
        return text
    }
}
