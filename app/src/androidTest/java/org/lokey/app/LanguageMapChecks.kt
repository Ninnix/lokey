package org.lokey.app

import android.content.Context
import org.json.JSONObject

object LanguageMapChecks {
    fun run(context:Context):JSONObject {
        check(Models.text.map { it.id }==listOf("lfm","qwen"))
        val retired=object:android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("retired-model-test",mode)
        }
        try {
            retired.getSharedPreferences("lokey",Context.MODE_PRIVATE).edit().putString("model","onejev").commit()
            check(Prefs(retired).model=="auto")
        } finally { context.deleteSharedPreferences("retired-model-test") }
        val languages=ModelLanguages.all(context)
        check(languages.size==39 && LanguageLayouts.all.size==39)
        check(languages.map { it.code }.toSet()==ModelLanguages.parakeetCodes+ModelLanguages.qwenVoiceCodes)
        for(language in languages) {
            val mapping=checkNotNull(language.mapping)
            check(mapping.code.isNotBlank() && mapping.script.isNotBlank() && mapping.target.isNotBlank())
            check(mapping.layout==null || KeyboardLayouts.all.any { it.id==mapping.layout })
            check(mapping.layout!=null || mapping.status=="missing")
            check(mapping.status=="available" || mapping.note.isNotEmpty())
        }
        fun mapping(name:String)=LanguageLayouts.all.getValue(name)
        check(KeyboardLayouts.variants("qwerty").take(2).map { it.id }==listOf("intl","qwerty"))
        check(KeyboardLayouts.find("unknown").id=="qwerty")
        check(mapping("English").layout=="intl" && mapping("Italian").layout=="qwerty")
        check(mapping("French").layout=="azerty" && mapping("German").layout=="qwertz")
        check(mapping("Cantonese").layout=="jyutping")
        check(languages.all { it.mapping!!.status=="available" })
        check(KeyboardLayouts.variants("hindi").map { it.id }==listOf("hindi","hindi_phonetic","bolnagri"))
        check(KeyboardLayouts.variants("pinyin").map { it.id }==listOf("pinyin","jyutping","cangjie","sucheng"))
        check("simplified" in mapping("Chinese").note)
        for(name in listOf("Bengali","Hebrew","Urdu","Welsh","Akan","Yoruba")) check(name !in LanguageLayouts.all)
        check(LatinAccents.common.values.flatten().none { it in listOf("ō","ŷ","ŵ","ẁ","ẃ","ẅ","ð","ĝ","ĥ","ĵ","þ") })
        check(LatinAccents.common.values.sumOf { it.size }==75)
        check(LatinAccents.options('u',"de").first()=="ü")
        check(LatinAccents.options('i',"ro").first()=="î")
        check(LatinAccents.options('r',"cs").first()=="ř")
        check(LatinAccents.options('j',"nl").contains("j́"))
        check(LatinAccents.options('e',"it").take(2)==listOf("è","é"))
        check("ɛ" !in LatinAccents.options('e',"it"))
        val statuses=JSONObject()
        languages.groupingBy { it.mapping!!.status }.eachCount().forEach { (key,count) -> statuses.put(key,count) }
        return JSONObject().put("mapped_languages",39).put("voice_models_define_scope",true).put("qwerty_intl_first",true).put("common_accents",75).put("language_specific_order",true).put("combining_accents",true).put("all_input_methods_available",true).put("statuses",statuses)
    }
}
