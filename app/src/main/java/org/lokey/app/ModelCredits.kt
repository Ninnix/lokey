package org.lokey.app

import android.content.Context
import android.widget.ImageView

object ModelCredits {
    data class Credit(val id:String,val provider:String,val license:String,val file:String,val source:String,val conversion:String,val format:String)
    val all=listOf(
        Credit("lfm","Liquid AI","LFM Open License v1.0","lfm-model.txt","https://huggingface.co/LiquidAI/LFM2.5-350M","Liquid AI","Q4_K_M"),
        Credit("qwen","Qwen Team · Alibaba Cloud","Apache 2.0","qwen-model.txt","https://huggingface.co/Qwen/Qwen3.5-0.8B","Unsloth","Q4_K_M"),
        Credit("parakeet","NVIDIA","CC BY 4.0","parakeet-model.txt","https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3","cstr","Q4_K"),
        Credit("qwen_asr","Qwen Team · Alibaba Cloud","Apache 2.0","qwen3-asr.txt","https://huggingface.co/Qwen/Qwen3-ASR-0.6B","ggml-org","Q8_0 model and audio encoder")
    )
    fun logo(context:Context,prefs:Prefs,id:String)=ImageView(context).apply {
        val icon=when(id) { "lfm" -> R.drawable.model_liquid;"parakeet" -> R.drawable.model_nvidia;else -> R.drawable.model_qwen }
        setImageResource(icon);scaleType=ImageView.ScaleType.FIT_CENTER
        if(id=="lfm") imageTintList=android.content.res.ColorStateList.valueOf(if(prefs.appLight) 0xff1c1d1e.toInt() else 0xfff5f0e7.toInt())
        contentDescription=all.first { it.id==id }.provider+" logo"
    }
}
