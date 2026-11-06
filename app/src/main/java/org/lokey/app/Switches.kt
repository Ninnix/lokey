package org.lokey.app

import android.os.VibrationEffect.Composition

object Switches {
    data class Profile(val id:String,val name:String,val kind:String,val feel:String,val down:List<String> = emptyList(),val up:List<String> = emptyList()) {
        val perKey get()=down.isEmpty()
        val samples get()=if(perKey) 86 else down.size+up.size
    }
    val profiles=listOf(
        Profile("cherry_red","Cherry MX Red","Linear","A light, smooth press and a soft return."),
        Profile("gateron_brown","Gateron Brown","Tactile","A subtle bump, followed by a gentle bottom-out.",listOf("gateron-brown-down-1.wav")),
        Profile("cherry_brown","Cherry MX Brown","Tactile","A firmer bump with a short, rounded landing.",List(3) { "cherry-brown-down-${it+1}.mp3" },listOf("cherry-brown-up.mp3")),
        Profile("cherry_blue","Cherry MX Blue","Clicky","A crisp click and a distinct return.",List(3) { "cherry-blue-down-${it+1}.mp3" },listOf("cherry-blue-up.mp3")),
        Profile("holy_panda","Holy Panda","Strong tactile","A pronounced early bump and a deep, rounded landing.",List(3) { "holy-panda-down-${it+1}.mp3" },listOf("holy-panda-up.mp3")),
        Profile("kailh_box_jade","Kailh BOX Jade","Click bar","A firm, thick click-bar snap with a decisive landing.",listOf("kailh-box-jade-down-1.ogg")),
        Profile("razer_green","Razer Green","Clicky","A sharp, bright click with a defined tactile bump.",List(3) { "razer-green-down-${it+1}.wav" }),
        Profile("topre","Topre 45g","Soft dome","A soft, rounded dome press and a deep thock.",List(4) { "topre-down-${it+1}.mp3" },listOf("topre-up-1.mp3"))
    )
    data class Pulse(val primitive:Int,val scale:Float,val delay:Int=0)
    fun pulses(profile:String,down:Boolean):List<Pulse> {
        if(!down) return listOf(Pulse(Composition.PRIMITIVE_LOW_TICK,when(profile) { "cherry_blue","razer_green" -> .35f;"kailh_box_jade" -> .5f;"holy_panda" -> .27f;"topre" -> .12f;else -> .18f }))
        return when(profile) {
            "gateron_brown" -> listOf(Pulse(Composition.PRIMITIVE_TICK,.55f),Pulse(Composition.PRIMITIVE_LOW_TICK,.3f,5))
            "cherry_brown" -> listOf(Pulse(Composition.PRIMITIVE_TICK,.7f),Pulse(Composition.PRIMITIVE_LOW_TICK,.4f,4))
            "cherry_blue" -> listOf(Pulse(Composition.PRIMITIVE_CLICK,.8f),Pulse(Composition.PRIMITIVE_TICK,.35f,3))
            "holy_panda" -> listOf(Pulse(Composition.PRIMITIVE_CLICK,.85f),Pulse(Composition.PRIMITIVE_LOW_TICK,.52f,4))
            "kailh_box_jade" -> listOf(Pulse(Composition.PRIMITIVE_CLICK,1f),Pulse(Composition.PRIMITIVE_CLICK,.5f,6))
            "razer_green" -> listOf(Pulse(Composition.PRIMITIVE_CLICK,.65f),Pulse(Composition.PRIMITIVE_TICK,.45f,2))
            "topre" -> listOf(Pulse(Composition.PRIMITIVE_LOW_TICK,.4f),Pulse(Composition.PRIMITIVE_LOW_TICK,.15f,5))
            else -> listOf(Pulse(Composition.PRIMITIVE_LOW_TICK,.48f))
        }
    }
    fun gain(profile:String)=when(profile) { "topre" -> 2.4f;"kailh_box_jade" -> 1.6f;"holy_panda" -> 1.2f;"razer_green" -> .8f;else -> 1f }
}
