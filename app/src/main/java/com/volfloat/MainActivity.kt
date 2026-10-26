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
        selected.addAll(prefs.getStringSet("apps", emptySet()) ?: emptySet())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), 0)
        }
        status = TextView(this).apply { textSize = 15f }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "勾选的应用在前台时才显示音量浮窗；拖动浮窗中间的数字可以移动位置。"
            textSize = 13f
            setPadding(0, dp(4), 0, dp(4))
        })
        root.addView(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        status.text = when {
            VolService.connected -> "无障碍服务：运行中"
            enabled.contains("$packageName/") ->
                "无障碍服务：设置里显示已开启，但没有在运行（关掉再打开一次；电量设为「无限制」）"
            else -> "无障碍服务：未开启（请到无障碍设置里打开「音量浮窗」）"
        }
    }
}
