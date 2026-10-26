package com.volfloat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class LogActivity : Activity() {

    private lateinit var tv: TextView
    private lateinit var scroll: ScrollView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(24), dp(12), 0)
        }

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun btn(label: String, onClick: () -> Unit) {
            bar.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { onClick() }
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        btn("刷新") { load() }
        btn("复制") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("log", tv.text))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        btn("清空") {
            Logger.clear(this)
            load()
        }
        root.addView(bar)

        tv = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
        }
        scroll = ScrollView(this)
        scroll.addView(tv)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val t = Logger.read(this)
        tv.text = if (t.isEmpty()) "（暂无日志。先在主界面打开「记录日志」开关，再去用一下）" else t
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
