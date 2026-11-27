package org.lokey.app

import android.content.SharedPreferences

internal class EmojiHistory(private val store:SharedPreferences) {
    companion object {
        const val key="emoji_history"
        val defaults=listOf("😊","❤️","👍","😂","😭","🥺")
        val catalog=defaults+(
            "😅 😀 😃 😄 😁 🤣 🙂 🙃 😉 😌 😍 🥰 😘 😋 😛 🤪 😎 🤩 🥳 🤔 🤨 😐 🙄 😒 😬 😴 😮 😱 😳 😢 😞 😔 😤 😡 🤯 😇 🤗 🤫 🫣 " +
            "💛 💚 💙 💜 🖤 🤍 💔 💕 💖 🙌 👏 🙏 👌 ✌️ 🤝 👋 💪 👎 🤞 👀 🎉 🎂 🎁 ✨ 🔥 ⭐ ✅ ❌ 💯 💬 💡 " +
            "🐶 🐱 🐻 🐼 🦊 🐸 🌸 🌹 ☀️ 🌙 🌈 ☁️ 🍕 🍔 🍟 🍰 ☕ 🍵 🍺 🍷 ⚽ 🎮 🎵 🚀 🏠 🌍 📸 📚 💻 ⏰"
        ).split(' ')
        private val supported=catalog.toSet()
        fun contains(text:String)=text in supported
    }

    // One entry per emoji, newest first. Stable sorting makes recency break frequency ties.
    private data class Entry(val emoji:String,val count:Int)
    private fun entries()=store.getString(key,"").orEmpty().split(' ').mapNotNull { item ->
        val emoji=item.substringBefore(':')
        if(emoji in supported) Entry(emoji,item.substringAfter(':').toIntOrNull() ?: 0) else null
    }
    private fun save(edit:SharedPreferences.Editor,entries:List<Entry>) {
        edit.putString(key,entries.joinToString(" ") { if(it.count>0) "${it.emoji}:${it.count}" else it.emoji })
    }
    fun shortcuts(learning:Boolean):List<String> {
        val recent=entries()
        val ranked=if(learning) recent.sortedByDescending { it.count } else recent
        return (ranked.map { it.emoji }+defaults).distinct().take(defaults.size)
    }
    fun record(emoji:String,learning:Boolean) {
        if(emoji !in supported) return
        val recent=entries()
        val previous=recent.firstOrNull { it.emoji==emoji }?.count ?: 0
        val count=if(learning) previous.coerceIn(0,Int.MAX_VALUE-1)+1 else 0
        val updated=listOf(Entry(emoji,count))+recent.filterNot { it.emoji==emoji }
        val edit=store.edit()
        save(edit,if(learning) updated else updated.take(defaults.size))
        edit.apply()
    }
    fun clearCounts(edit:SharedPreferences.Editor) {
        save(edit,entries().take(defaults.size).map { Entry(it.emoji,0) })
    }
    fun clear() { store.edit().remove(key).apply() }
}
