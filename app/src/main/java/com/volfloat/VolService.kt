package com.volfloat

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class VolService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var am: AudioManager
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var tv: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private val repeats = ArrayList<Runnable>()

    private var shown = false
    private var receiverOn = false
    private var visible: Set<String> = emptySet()
    private var selected: Set<String> = emptySet()

    private val refreshRunnable = Runnable { refreshForeground() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    // 必须持有强引用，SharedPreferences 只保存弱引用
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "apps") {
            selected = loadSelected()
            refreshForeground()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadSelected(): Set<String> =
        HashSet(prefs.getStringSet("apps", emptySet()) ?: emptySet())

    override fun onServiceConnected() {
        super.onServiceConnected()
        connected = true
        if (!::root.isInitialized) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            am = getSystemService(AUDIO_SERVICE) as AudioManager
            prefs = getSharedPreferences("cfg", MODE_PRIVATE)
            selected = loadSelected()
            buildView()
        }
        selected = loadSelected()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refreshForeground()
    }

    // 窗口变化时合并成一次检查（事件驱动，不轮询）
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        handler.removeCallbacks(refreshRunnable)
        handler.postDelayed(refreshRunnable, 80)
    }

    override fun onInterrupt() {}

    /**
     * 从窗口列表里取出当前看得见的应用窗口的包名（支持小窗、分屏）。
     * 从上往下收集，遇到铺满屏幕的窗口就停，它下面的窗口是被挡住的。
     */
    private fun visibleAppPackages(): Set<String> {
        val ws = try { windows } catch (_: Exception) { return emptySet() }
        val m = resources.displayMetrics
        val r = Rect()
        val out = HashSet<String>()
        for (w in ws.sortedByDescending { it.layer }) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val p = w.root?.packageName?.toString() ?: continue
            out.add(p)
            w.getBoundsInScreen(r)
            if (r.width() >= m.widthPixels * 0.9 && r.height() >= m.heightPixels * 0.9) break
        }
        return out
    }

    private fun refreshForeground() {
        if (!::root.isInitialized) return
        visible = visibleAppPackages()
        evaluate()
    }

    private fun evaluate() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val want = !km.isKeyguardLocked &&
            (packageName in visible || visible.any { it in selected })
        if (want && !shown) show() else if (!want && shown) hide()
    }

    private fun clamp() {
        val m = resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (m.widthPixels - dp(170)).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (m.heightPixels - dp(60)).coerceAtLeast(0))
    }

    private fun show() {
        clamp()
        root.getChildAt(0).alpha = 1f
        root.getChildAt(2).alpha = 1f
        try {
            wm.addView(root, lp)
        } catch (e: Exception) {
            Toast.makeText(this, "浮窗添加失败：" + e.message, Toast.LENGTH_LONG).show()
            return
        }
        shown = true
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        receiverOn = true
        refresh()
    }

    private fun hide() {
        repeats.forEach { handler.removeCallbacks(it) }
        if (receiverOn) {
            try { unregisterReceiver(receiver) } catch (_: Exception) {}
            receiverOn = false
        }
        try { wm.removeView(root) } catch (_: Exception) {}
        shown = false
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildView() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 30, 30, 30))
                cornerRadius = dp(24).toFloat()
            }
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }

        tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            minWidth = dp(52)
            setPadding(dp(6), dp(10), dp(6), dp(10))
        }

        root.addView(makeButton("−", -1))
        root.addView(tv)
        root.addView(makeButton("+", 1))

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", dp(40))
            y = prefs.getInt("y", dp(200))
        }

        // 拖动中间的数字移动浮窗位置
        var startX = 0
        var startY = 0
        var downX = 0f
        var downY = 0f
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    downX = e.rawX
                    downY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).toInt()
                    lp.y = startY + (e.rawY - downY).toInt()
                    clamp()
                    wm.updateViewLayout(root, lp)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun makeButton(label: String, delta: Int): TextView {
        val repeat = object : Runnable {
            override fun run() {
                step(delta)
                handler.postDelayed(this, 50)
            }
        }
        repeats.add(repeat)
        return TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(52), dp(48))
            setOnTouchListener { v: View, e: MotionEvent ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        v.alpha = 0.5f
                        step(delta)
                        handler.postDelayed(repeat, 400)
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.alpha = 1f
                        handler.removeCallbacks(repeat)
                    }
                }
                true
            }
        }
    }

    private fun step(delta: Int) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val next = (cur + delta).coerceIn(0, max)
        if (next != cur) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
        }
        refresh()
    }

    private fun refresh() {
        tv.text = am.getStreamVolume(AudioManager.STREAM_MUSIC).toString()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (shown) {
            clamp()
            wm.updateViewLayout(root, lp)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        connected = false
        handler.removeCallbacks(refreshRunnable)
        if (!::root.isInitialized) return
        if (shown) hide()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    companion object {
        @Volatile
        var connected = false
    }
}
