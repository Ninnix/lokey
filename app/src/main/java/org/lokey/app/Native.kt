package org.lokey.app

object Native {
    init { System.loadLibrary("lokey") }
    external fun npuAvailable():Boolean
    external fun beginModelUse(kind:Int)
    external fun endModelUse(kind:Int)
    external fun touchModel(kind:Int)
    external fun modelGeneration(kind:Int):Long
    external fun modelIdleMillis(kind:Int):Long
    external fun modelUsers(kind:Int):Int
    external fun residentModels():Int
    external fun nextModelIdleMillis(idleMillis:Long):Long
    external fun trimModels(idleMillis:Long):Int
    private fun modelChanged() { ModelMemory.changed() }
    external fun loadText(path: String,accelerator:Int=0): Boolean
    external fun textBackend():String
    external fun resetText()
    external fun predict(text: String): String
    external fun rank(context: String, language: String, words: Array<String>): FloatArray
    external fun complete(context:String,language:String,prefix:String,edits:Int=0):String
    external fun score(context:String,language:String,words:Array<String>):FloatArray
    external fun letters(): FloatArray
    external fun loadSpeech(path: String,projector:String="",accelerator:Int=0): Boolean
    external fun speechBackend():String
    external fun transcribe(samples: FloatArray): String
    external fun cancelSpeech()
    external fun loadTranslator(path:String):Boolean
    external fun translate(text:String,target:String,epoch:Long,sink:Translation.Sink):String
    external fun cancelTranslation():Long
    external fun closeTranslator()
    external fun release()
}
