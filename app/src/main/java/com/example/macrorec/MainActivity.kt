package com.example.macrorec

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var startBtn: TextView
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(40).toFloat()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        actionBar?.hide()
        window.statusBarColor = 0xFF2B3350.toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF36405F.toInt())
            setPadding(dp(28), dp(48), dp(28), dp(28))
        }

        val title = TextView(this).apply {
            text = "Macro Recorder"; textSize = 28f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        }
        status = TextView(this).apply {
            textSize = 14f; setTextColor(0xFFC9CFE6.toInt()); gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        val spacer = View(this)

        startBtn = TextView(this).apply {
            textSize = 18f; setTextColor(0xFF3B3B3B.toInt()); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; background = pill(0xFFF9E076.toInt())
            setOnClickListener { onStartClick() }
        }

        val help = TextView(this).apply {
            text = "كيف أستخدمه؟"; textSize = 14f; setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER; background = pill(0xFFEDEDF2.toInt())
            setOnClickListener { showHelp() }
        }
        val acc = TextView(this).apply {
            text = "إمكانية الوصول"; textSize = 14f; setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER; background = pill(0xFFEDEDF2.toInt())
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(help, LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(8) })
            addView(acc, LinearLayout.LayoutParams(0, dp(46), 1f))
        }

        root.addView(title)
        root.addView(status)
        root.addView(spacer, LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(startBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)).apply { bottomMargin = dp(16) })
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(24) })
        setContentView(root)
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun onStartClick() {
        val s = MacroService.instance
        if (s == null) {
            Toast.makeText(this, "فعّل خدمة Macro Recorder من إمكانية الوصول ثم ارجع واضغط START", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else if (s.isBarShown()) {
            s.hideBar(); refresh()
        } else {
            s.showBar(); refresh(); moveTaskToBack(true)
        }
    }

    private fun refresh() {
        val s = MacroService.instance
        status.text = if (s == null) "الخدمة غير مفعّلة ❌" else "الخدمة مفعّلة ✅"
        startBtn.text = if (s != null && s.isBarShown()) "STOP SERVICE" else "START SERVICE"
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle("طريقة الاستخدام")
            .setMessage(
                "1) اضغط START SERVICE وتظهر قائمة عائمة تقدر تسحبها لأي مكان.\n\n" +
                "2) افتح اللعبة واضغط ⏺ وسوّ حركاتك بإصبع واحد. اللعبة تستجيب بعد ما ترفع إصبعك.\n\n" +
                "3) اضغط ⏹ لإنهاء التسجيل. ↶ يحذف آخر حركة، و ⏸ يوقف التسجيل مؤقتًا وتلعب بحرية.\n\n" +
                "4) من ⚙ حدد عدد التكرار (∞ بلا نهاية) والفاصل بين الدورات.\n\n" +
                "5) اضغط ▶ للتشغيل، وتشوف الوقت وعدد الدورات.\n\n" +
                "» تصغّر القائمة، و ✕ تغلقها."
            )
            .setPositiveButton("تمام", null)
            .show()
    }
} 
