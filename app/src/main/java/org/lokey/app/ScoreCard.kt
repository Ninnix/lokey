package org.lokey.app

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import java.io.File

object ScoreCard {
    fun create(context: Context, score: JSONObject, record: Boolean): File {
        val bitmap = Bitmap.createBitmap(1080, 1350, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val bg = Color.rgb(23, 24, 25); val cream = Color.rgb(245, 240, 231); val red = Color.rgb(240, 92, 94)
        c.drawColor(bg)
        fun text(value: String, x: Float, y: Float, size: Float, color: Int = cream, bold: Boolean = false, maxWidth:Float=1004f-x) {
            p.color = color; p.textSize = size; p.typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            val width=p.measureText(value)
            if(width>maxWidth) p.textSize*=maxWidth/width
            c.drawText(value, x, y, p)
        }
        val logo = BitmapFactory.decodeResource(context.resources, R.drawable.lokey_logo)
        c.drawBitmap(logo, null, RectF(76f, 68f, 162f, 154f), p); logo.recycle()
        text("lokey", 188f, 129f, 59f, bold = true)
        text(context.tr("INFERNO / SPEED TEST"), 76f, 235f, 27f, red, true)
        text(if (record) context.tr("A new personal best.") else context.tr("Words on fire."), 76f, 325f, 56f, bold = true)
        val wpm = String.format(context.uiLocale(), "%.1f", score.getDouble("wpm"))
        text(wpm, 68f, 565f, 190f, red, true)
        text(context.tr("WORDS PER MINUTE"), 80f, 625f, 28f)
        p.color = Color.rgb(51, 52, 53); c.drawRoundRect(76f, 698f, 1004f, 1040f, 30f, 30f, p)
        val metrics = listOf(context.tr("ACCURACY") to String.format(context.uiLocale(), "%.1f%%", score.getDouble("accuracy")), context.tr("BEST COMBO") to context.tr("%1\$s characters",score.getInt("combo")), context.tr("SCORE") to score.getInt("score").toString(), context.tr("PREDICTION TAPS") to score.getInt("prediction_taps").toString())
        metrics.forEachIndexed { i, (label, value) ->
            val x = if (i % 2 == 0) 110f else 575f; val y = if (i < 2) 755f else 920f
            text(label, x, y, 23f, Color.LTGRAY,maxWidth=390f); text(value, x, y + 65f, 49f, bold = true,maxWidth=390f)
        }
        text(context.tr("DANTE / INFERNO I"), 76f, 1124f, 26f, red, true)
        text(if (score.optBoolean("eligible", true)) java.util.Locale.forLanguageTag(score.optString("language","en")).getDisplayLanguage(context.uiLocale())+" · "+context.layoutName(KeyboardLayouts.find(score.optString("layout","intl"))) else context.tr("Practice run · Voice or paste used"), 76f, 1174f, 26f)
        text(context.tr("EVERY CHARACTER COUNTS."), 76f, 1270f, 27f)
        val file = File(context.cacheDir, "lokey-speed-record.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        return file
    }
}

class ScoreProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        require(uri.path == "/record.png")
        return File(context!!.cacheDir, "lokey-speed-record.png")
    }
    override fun getType(uri: Uri): String { file(uri); return "image/png" }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            val row = newRow()
            for (column in columns) row.add(when (column) { OpenableColumns.DISPLAY_NAME -> file.name; OpenableColumns.SIZE -> file.length(); else -> null })
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
