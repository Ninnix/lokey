package org.lokey.app

import android.text.InputType
import android.view.inputmethod.EditorInfo
import kotlin.math.sqrt
import kotlin.math.exp

object AdaptiveKeys {
    fun probabilities(prefix:String,candidates:List<Pair<String,Double>>):FloatArray {
        val typed=Languages.fold(prefix).replace("'","")
        val words=candidates.filter { it.second.isFinite() }.groupBy { Languages.lower(it.first) }.values.map { group -> group.maxBy { it.second } }
        val values=words.mapNotNull { (word,score) ->
            val key=Languages.fold(word).replace("'","")
            val next=if(key.startsWith(typed)) key.getOrNull(typed.length) else null
            if(next in 'a'..'z') (next!!-'a') to score else null
        }
        val out=FloatArray(26);val high=values.maxOfOrNull { it.second } ?: return out
        for((letter,score) in values) out[letter]+=exp(score-high).toFloat()
        val total=out.sum()
        if(total>0) for(i in out.indices) out[i]/=total
        return out
    }
    fun allowed(type:Int,options:Int):Boolean {
        val variation=type and InputType.TYPE_MASK_VARIATION
        return type and InputType.TYPE_MASK_CLASS==InputType.TYPE_CLASS_TEXT &&
            type and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS==0 && options and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING==0 &&
            variation !in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_URI,InputType.TYPE_TEXT_VARIATION_FILTER)
    }
    fun boundary(a:String,b:String,prior:FloatArray,pitch:Float,budget:Float=.12f):Float {
        val ca=a.singleOrNull();val cb=b.singleOrNull()
        if(ca !in 'a'..'z' || cb !in 'a'..'z') return 0f
        fun value(c:Char):Float {
            val p=prior.getOrElse(c-'a') { 0f }
            return if(p.isFinite()) p.coerceIn(0f,1f) else 0f
        }
        if(prior.none { it.isFinite() && it>=.12f }) return 0f
        val x=sqrt(value(ca!!));val y=sqrt(value(cb!!))
        return pitch*budget.coerceIn(0f,.12f)*(x-y)/(x+y+.12f)
    }
}
