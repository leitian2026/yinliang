package com.volfloat

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
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
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView

class VolService : AccessibilityService() {

    private lateinit var wm: WindowManager
    private lateinit var am: AudioManager
    private lateinit var prefs: SharedPreferences
    private lateinit var root: LinearLayout
    private lateinit var tv: TextView
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private var shown = false
    private var receiverOn = false
    private var current = ""
    private var selected: Set<String> = emptySet()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refresh()
        }
    }

    // 必须持有强引用，SharedPreferences 只保存弱引用
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "apps") {
            selected = loadSelected()
            evaluate()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadSelected(): Set<String> =
        HashSet(prefs.getStringSet("apps", emptySet()) ?: emptySet())

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (::root.isInitialized) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        am = getSystemService(AUDIO_SERVICE) as AudioManager
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        selected = loadSelected()
        buildView()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            // 自己的浮窗不算前台，只有主界面才算
            if (event.className?.toString() != MainActivity::class.java.name) return
        } else if (pkg in IGNORED || isIme(pkg)) {
            return
        }
        current = pkg
        evaluate()
    }

    override fun onInterrupt() {}

    private fun isIme(pkg: String): Boolean {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    private fun evaluate() {
        if (!::root.isInitialized) return
        val want = current in selected
        if (want && !shown) show() else if (!want && shown) hide()
    }

    private fun clamp() {
        val m = resources.displayMetrics
        lp.x = lp.x.coerceIn(0, (m.widthPixels - dp(170)).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (m.heightPixels - dp(60)).coerceAtLeast(0))
    }

    private fun show() {
        clamp()
        try {
            wm.addView(root, lp)
        } catch (_: Exception) {
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
        handler.removeCallbacksAndMessages(null)
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
        if (!::root.isInitialized) return
        if (shown) hide()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    companion object {
        private val IGNORED = setOf(
            "com.android.systemui",
            "android",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )
    }
}
