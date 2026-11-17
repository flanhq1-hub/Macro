package com.example.macrorec

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            layoutDirection = LinearLayout.LAYOUT_DIRECTION_RTL
        }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, 30) }
        root.addView(status)
        fun add(t: String, f: () -> Unit) = root.addView(Button(this).apply {
            text = t; setOnClickListener { f() }
        })
        add("1) افتح Shizuku (أو حمّله)") {
            val i = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            startActivity(i ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=moe.shizuku.privileged.api")))
        }
        add("2) امنح هذا التطبيق صلاحية Shizuku") {
            try { if (Shizuku.pingBinder()) Shizuku.requestPermission(1) } catch (_: Throwable) {}
        }
        add("3) فعّل خدمة إمكانية الوصول") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        add("تحديث الحالة") { refresh() }
        setContentView(root)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        val granted = running && try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        val acc = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        status.text = "Shizuku يعمل: ${if (running) "✅" else "❌"}\n" +
            "صلاحية Shizuku: ${if (granted) "✅" else "❌"}\n" +
            "خدمة إمكانية الوصول: ${if (acc) "✅" else "❌"}\n\n" +
            "لما تصير الثلاثة ✅ تظهر القائمة العائمة فوق أي لعبة."
    }
}
