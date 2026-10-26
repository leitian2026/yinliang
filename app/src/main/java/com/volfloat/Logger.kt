package com.volfloat

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 简单文件日志：开关打开才写；文件超过 200KB 时只保留最后 100KB */
object Logger {
    @Volatile
    var enabled = false

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun file(ctx: Context) = File(ctx.filesDir, "log.txt")

    @Synchronized
    fun w(ctx: Context, msg: String) {
        if (!enabled) return
        try {
            val f = file(ctx)
            if (f.length() > 200_000) f.writeText(f.readText().takeLast(100_000))
            f.appendText(fmt.format(Date()) + " " + msg + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(ctx: Context): String = try {
        val f = file(ctx)
        if (f.exists()) f.readText() else ""
    } catch (_: Exception) {
        ""
    }

    @Synchronized
    fun clear(ctx: Context) {
        try { file(ctx).delete() } catch (_: Exception) {}
    }
}
