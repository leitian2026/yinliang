package com.volfloat

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private class Item(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var prefs: SharedPreferences
    private lateinit var status: TextView
    private lateinit var adapter: BaseAdapter
    private val selected = HashSet<String>()
    private val items = ArrayList<Item>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        Logger.enabled = prefs.getBoolean("log_on", false)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        selected.addAll(prefs.getStringSet("apps", emptySet()) ?: emptySet())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), 0)
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)
        val logRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        logRow.addView(
            Switch(this).apply {
                text = "记录日志"
                isChecked = prefs.getBoolean("log_on", false)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean("log_on", on).apply()
                    Logger.enabled = on
                    if (on) Logger.w(this@MainActivity, "日志已开启")
                }
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        logRow.addView(Button(this).apply {
            text = "查看日志"
            setOnClickListener { startActivity(Intent(this@MainActivity, LogActivity::class.java)) }
        })
        root.addView(logRow)
        root.addView(TextView(this).apply {
            text = "勾选的应用在前台时才显示音量浮窗（用 root 判断前台应用）；拖动浮窗中间的数字可以移动位置。"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        })
        root.addView(
            Switch(this).apply {
                text = "启用浮窗服务（开机自启）"
                isChecked = prefs.getBoolean("run_on", true)
                setOnCheckedChangeListener { _, on ->
                    prefs.edit().putBoolean("run_on", on).apply()
                    if (on) startFloat() else stopService(Intent(this@MainActivity, FloatService::class.java))
                    status.postDelayed({ updateStatus() }, 1500)
                }
            }
        )
        root.addView(Button(this).apply {
            text = "打开悬浮窗权限设置"
            setOnClickListener {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        })

        adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    addView(ImageView(this@MainActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
                    })
                    addView(TextView(this@MainActivity).apply {
                        textSize = 16f
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            .apply { leftMargin = dp(12) }
                    })
                    addView(CheckBox(this@MainActivity).apply {
                        isClickable = false
                        isFocusable = false
                    })
                }
                val it = items[position]
                (row.getChildAt(0) as ImageView).setImageDrawable(it.icon)
                (row.getChildAt(1) as TextView).text = it.label
                (row.getChildAt(2) as CheckBox).isChecked = it.pkg in selected
                return row
            }
        }

        val lv = ListView(this)
        lv.adapter = adapter
        lv.setOnItemClickListener { _, _, pos, _ ->
            val pkg = items[pos].pkg
            if (!selected.remove(pkg)) selected.add(pkg)
            prefs.edit().putStringSet("apps", HashSet(selected)).apply()
            adapter.notifyDataSetChanged()
        }
        root.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        Thread {
            val pm = packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
                .filter { it.activityInfo.packageName != packageName }
                .map { Item(it.activityInfo.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm)) }
                .sortedWith(
                    compareByDescending<Item> { it.pkg in selected }
                        .thenBy { it.label.lowercase() }
                )
            runOnUiThread {
                items.addAll(list)
                adapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun startFloat() {
        try {
            startForegroundService(Intent(this, FloatService::class.java))
        } catch (_: Exception) {
        }
    }

    private fun updateStatus() {
        val rootText = when (FgWatcher.rootOk) {
            true -> "可用"
            false -> "不可用（请在 Magisk / KernelSU 里给本应用授权）"
            null -> "检测中"
        }
        val overlay = if (Settings.canDrawOverlays(this)) "已授权" else "未授权（root 会自动授予，失败请点下方按钮手动开启）"
        status.text = "浮窗服务：" + (if (FloatService.running) "运行中" else "未运行") +
            "\nRoot：" + rootText +
            "\n悬浮窗权限：" + overlay
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("run_on", true)) startFloat()
        updateStatus()
        status.postDelayed({ updateStatus() }, 1500)
    }
}
