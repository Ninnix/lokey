package org.lokey.app

import android.os.Build
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import kotlin.math.abs
import kotlin.math.min

object Cursor {
    fun position(connection: InputConnection): Int {
        if (Build.VERSION.SDK_INT >= 31) {
            val text = connection.getSurroundingText(0, 0, 0)
            if (text != null && text.offset >= 0 && text.selectionStart == text.selectionEnd) return text.offset + text.selectionStart
        }
        val text = connection.getExtractedText(ExtractedTextRequest(), 0) ?: return -1
        return if (text.selectionStart == text.selectionEnd) text.startOffset + text.selectionStart else -1
    }
    private fun target(text: CharSequence, start: Int, end: Int, delta: Int): Int? {
        if (start !in 0..text.length || end !in 0..text.length) return null
        val left = delta < 0
        val origin = if (left) minOf(start, end) else maxOf(start, end)
        val available = if (left) Character.codePointCount(text, 0, origin) else Character.codePointCount(text, origin, text.length)
        val steps = min(available, (abs(delta) - if (start != end) 1 else 0).coerceAtLeast(0))
        return Character.offsetByCodePoints(text, origin, if (left) -steps else steps)
    }
    fun move(connection: InputConnection, amount: Int, knownStart: Int, knownEnd: Int): Int? {
        val delta = amount.coerceIn(-80, 80)
        if (delta == 0) return null
        var position: Int? = null
        if (Build.VERSION.SDK_INT >= 31) {
            val surrounding = connection.getSurroundingText(192, 192, 0)
            if (surrounding != null) {
                val offset = if (surrounding.offset >= 0) surrounding.offset else if (knownEnd >= 0) knownEnd - surrounding.selectionEnd else -1
                if (offset >= 0) position = target(surrounding.text, surrounding.selectionStart, surrounding.selectionEnd, delta)?.plus(offset)
            }
        }
        if (position == null) {
            val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 384; hintMaxLines = 12 }, 0)
            val text = extracted?.text
            if (text != null && extracted.startOffset >= 0 && extracted.partialStartOffset < 0)
                position = target(text, extracted.selectionStart, extracted.selectionEnd, delta)?.plus(extracted.startOffset)
        }
        if (position == null && knownStart >= 0 && knownEnd >= 0) {
            val left = delta < 0
            val text = if (left) connection.getTextBeforeCursor(192, 0) else connection.getTextAfterCursor(192, 0)
            if (text != null) {
                val steps = (abs(delta) - if (knownStart != knownEnd) 1 else 0).coerceAtLeast(0)
                val origin = if (left) text.length else 0
                val shift = target(text, origin, origin, if (left) -steps else steps)?.minus(origin) ?: 0
                position = (if (left) minOf(knownStart, knownEnd) else maxOf(knownStart, knownEnd)) + shift
            }
        }
        // Unsupported editors stay put; arrow events can move focus out of the text field.
        val result = position?.coerceAtLeast(0) ?: return null
        return if (connection.setSelection(result, result)) result else null
    }
}
