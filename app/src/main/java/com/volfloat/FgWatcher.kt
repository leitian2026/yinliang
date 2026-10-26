package com.volfloat

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock

/** 常驻 root shell：一次 su，之后反复执行命令（带超时） */
class RootShell {
    private var p: Process? = null

    private fun ensure(): Boolean {
        val cur = p
        if (cur != null && cur.isAlive) return true
        return try {
            p = ProcessBuilder("su").redirectErrorStream(true).start()
            true
        } catch (e: Exception) {
            p = null
            false
        }
    }

    private fun close() {
        try { p?.destroy() } catch (_: Exception) {}
        p = null
    }

    @Synchronized
    fun exec(cmd: String, timeoutMs: Long = 4000): String? {
        if (!ensure()) return null
        val pr = p ?: return null
        var result: String? = null
        try {
            pr.outputStream.write("($cmd) 2>&1\necho $END\n".toByteArray())
            pr.outputStream.flush()
            val ins = pr.inputStream
            val sb = StringBuilder()
            val buf = ByteArray(8192)
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            var done = false
            while (!done) {
                val n = ins.available()
                if (n > 0) {
                    val k = ins.read(buf, 0, minOf(n, buf.size))
                    if (k < 0) { close(); done = true; continue }
                    sb.append(String(buf, 0, k))
                    val i = sb.indexOf(END)
                    if (i >= 0) { result = sb.substring(0, i); done = true }
                } else if (!pr.isAlive || SystemClock.uptimeMillis() > deadline) {
                    close()
                    done = true
                } else {
                    Thread.sleep(8)
                }
            }
        } catch (e: Exception) {
            close()
        }
        return result
    }

    fun stop() = close()

    companion object {
        private const val END = "__VF_END__"
    }
}

/**
 * 用 root 判断前台应用，事件驱动：
 * 1. 常驻 `logcat -b events` 监听 Activity 切换事件（几乎不耗电）
 * 2. 事件到来后用 dumpsys 确认真正的前台应用
 * 3. 亮屏时每 5 秒兜底确认一次，防止某些系统版本的事件标签对不上
 */
class FgWatcher(private val ctx: Context, private val onPkg: (String) -> Unit) {

    private val shell = RootShell()
    private val ht = HandlerThread("vf-fg").apply { start() }
    private val h = Handler(ht.looper)
    @Volatile private var running = false
    @Volatile private var logcatProc: Process? = null
    private var lastQuery = ""

    private val queryRunnable = Runnable { query("事件") }
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) query("兜底")
            h.postDelayed(this, 5000)
        }
    }

    fun start() {
        if (running) return
        running = true
        h.post {
            val pkg = ctx.packageName
            val out = shell.exec(
                "appops set $pkg SYSTEM_ALERT_WINDOW allow; " +
                    "pm grant $pkg android.permission.POST_NOTIFICATIONS; " +
                    "cmd deviceidle whitelist +$pkg"
            )
            rootOk = out != null
            Logger.w(ctx, "root ${if (out != null) "可用" else "不可用"} 授权输出=${out?.trim()}")
            query("启动")
        }
        Thread { logcatLoop() }.apply { isDaemon = true }.start()
        h.postDelayed(pollRunnable, 5000)
    }

    fun requestQuery() {
        h.removeCallbacks(queryRunnable)
        h.postDelayed(queryRunnable, 150)
    }

    fun stop() {
        running = false
        h.removeCallbacksAndMessages(null)
        try { logcatProc?.destroy() } catch (_: Exception) {}
        shell.stop()
        ht.quitSafely()
    }

    private fun logcatLoop() {
        while (running) {
            try {
                val p = ProcessBuilder("su", "-c", "logcat -b events -T 1 -v brief -s $TAGS")
                    .redirectErrorStream(true).start()
                logcatProc = p
                Logger.w(ctx, "已开始监听 events 日志")
                p.inputStream.bufferedReader().useLines { seq ->
                    for (line in seq) {
                        if (!running) break
                        handleLine(line)
                    }
                }
                p.destroy()
            } catch (e: Exception) {
                Logger.w(ctx, "监听 events 出错: ${e.message}")
            }
            if (running) {
                Logger.w(ctx, "events 监听已结束，3 秒后重试")
                try { Thread.sleep(3000) } catch (_: Exception) {}
            }
        }
    }

    private fun handleLine(line: String) {
        if (line.startsWith("---")) return
        Logger.w(ctx, "events> " + line.take(140))
        if (line.contains("wm_set_resumed_activity")) {
            parsePkg(line)?.let { onPkg(it) }
        }
        requestQuery()
    }

    private fun query(reason: String) {
        val t0 = SystemClock.uptimeMillis()
        var pkg: String? = null
        var out = shell.exec(
            "dumpsys activity activities | grep -E 'topResumedActivity=|mResumedActivity:' | head -n 2"
        )
        if (out != null) {
            val lines = out.lines()
            pkg = parsePkg(lines.firstOrNull { it.contains("topResumedActivity") } ?: lines.firstOrNull() ?: "")
        }
        if (pkg == null && out != null) {
            out = shell.exec("dumpsys window | grep -m1 mCurrentFocus")
            pkg = parsePkg(out ?: "")
        }
        val cost = SystemClock.uptimeMillis() - t0
        if (out == null) {
            rootOk = false
            Logger.w(ctx, "查询($reason)失败：root 命令无响应 耗时=${cost}ms")
            return
        }
        rootOk = true
        if (pkg == null) {
            Logger.w(ctx, "查询($reason)解析失败: ${out.trim().take(200)}")
            return
        }
        if (pkg != lastQuery || cost > 300) {
            lastQuery = pkg
            Logger.w(ctx, "查询($reason) 前台=$pkg 耗时=${cost}ms")
        }
        onPkg(pkg)
    }

    private fun parsePkg(s: String): String? = RX.find(s)?.groupValues?.get(1)

    companion object {
        private val RX = Regex("""([A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)/""")
        private const val TAGS =
            "wm_set_resumed_activity:I wm_resume_activity:I wm_focused_root_task:I " +
                "wm_focused_stack:I wm_task_to_front:I wm_on_top_resumed_gained_called:I"

        @Volatile
        var rootOk: Boolean? = null
    }
}
