package com.example.macrorec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.hypot

class MacroService : AccessibilityService() {

    companion object {
        @Volatile var instance: MacroService? = null
    }

    class Pt(val t: Long, val x: Float, val y: Float)
    class Stroke(val start: Long, val pts: List<Pt>) {
        val dur: Long get() = maxOf(pts.last().t, 1L)
    }

    private val RED = Color.rgb(255, 95, 95)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val prefs by lazy { getSharedPreferences("macro", Context.MODE_PRIVATE) }
    private val strokes = mutableListOf<Stroke>()

    private var root: DragBar? = null
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var rowIdle: LinearLayout
    private lateinit var rowSet: LinearLayout
    private lateinit var rowRec: LinearLayout
    private lateinit var rowPlay: LinearLayout
    private lateinit var tvRep: TextView
    private lateinit var tvInt: TextView
    private lateinit var tvRecTime: TextView
    private lateinit var tvRecCount: TextView
    private lateinit var tvPlay: TextView
    private lateinit var btnPause: TextView
    private var catcher: View? = null
    private lateinit var catcherLp: WindowManager.LayoutParams

    private var barShown = false
    private var collapsed = false
    private var settingsOpen = false
    private var recording = false
    private var paused = false
    private var playing = false
    private var recStart = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var repeatTarget = 0
    private var intervalMs = 500L
    private var loops = 0
    private var playStart = 0L
    private val cpts = mutableListOf<Pt>()
    private var curDown = 0L

    // ================= دورة حياة الخدمة =================
    override fun onServiceConnected() {
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (root != null) hideBar()
        load()
        build()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        try { hideBar() } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        try { hideBar() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun isBarShown() = barShown

    fun showBar() {
        if (barShown) return
        root?.let {
            wm.addView(it, lp)
            barShown = true
            updateUi()
        }
    }

    fun hideBar() {
        if (recording) stopRecord()
        if (playing) stopPlay()
        root?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        barShown = false
    }

    // ================= الواجهة =================
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(t: String, size: Float = 18f, color: Int = Color.WHITE, onClick: () -> Unit = {}) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color); gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { onClick() }
        }

    private fun hor(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    private inner class DragBar(c: Context) : LinearLayout(c) {
        private var sx = 0f
        private var sy = 0f
        private var ox = 0
        private var oy = 0
        private var drag = false

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; drag = false }
                MotionEvent.ACTION_MOVE -> if (!drag && hypot(e.rawX - sx, e.rawY - sy) > dp(10)) drag = true
            }
            return drag
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y; drag = false }
                MotionEvent.ACTION_MOVE -> {
                    if (!drag && hypot(e.rawX - sx, e.rawY - sy) > dp(10)) drag = true
                    if (drag) {
                        lp.x = ox + (e.rawX - sx).toInt()
                        lp.y = oy + (e.rawY - sy).toInt()
                        try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = false
            }
            return true
        }
    }

    private fun build() {
        tvRep = tv("∞", 15f)
        tvInt = tv("500", 15f)
        tvRecTime = tv("00:00", 16f)
        tvRecCount = tv("حركات: 0", 12f)
        tvPlay = tv("", 14f)
        btnPause = tv("⏸", 20f) { togglePause() }
        bubble = tv("«", 22f) { collapsed = false; updateUi() }

        rowIdle = hor(
            tv("»", 22f) { collapsed = true; updateUi() },
            tv("✕", 20f) { hideBar() },
            tv("⏺", 20f, RED) { startRecord() },
            tv("▶", 22f) { startPlay() },
            tv("⚙", 20f) { settingsOpen = !settingsOpen; updateUi() }
        )

        rowSet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hor(
                tv("التكرار", 13f),
                tv("−", 20f) { chRep(-1) }, tvRep, tv("+", 20f) { chRep(1) },
                tv("∞", 18f) { repeatTarget = 0; setLabels(); save() }
            ))
            addView(hor(
                tv("الفاصل ms", 13f),
                tv("−", 20f) { chInt(-100) }, tvInt, tv("+", 20f) { chInt(100) }
            ))
        }

        rowRec = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(hor(
                tv("●", 16f, RED), tvRecTime,
                tv("↶", 22f) { undo() }, btnPause,
                tv("⏹", 22f, RED) { stopRecord() }
            ))
            addView(tvRecCount)
        }

        rowPlay = hor(tvPlay, tv("⏹ إيقاف", 16f, RED) { stopPlay() })

        val r = DragBar(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(215, 52, 60, 92))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
            addView(bubble); addView(rowIdle); addView(rowSet); addView(rowRec); addView(rowPlay)
        }
        root = r

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(20); y = dp(160) }

        setLabels()
        updateUi()
    }

    private fun updateUi() {
        fun vis(v: View, b: Boolean) { v.visibility = if (b) View.VISIBLE else View.GONE }
        val idle = !recording && !playing
        vis(bubble, collapsed && idle)
        vis(rowIdle, !collapsed && idle)
        vis(rowSet, !collapsed && idle && settingsOpen)
        vis(rowRec, recording)
        vis(rowPlay, playing)
        btnPause.text = if (paused) "▶" else "⏸"
        tvRecCount.text = "حركات: ${strokes.size}" + if (paused) "  (متوقف مؤقتًا)" else ""
    }

    private fun setLabels() {
        tvRep.text = if (repeatTarget == 0) "∞" else repeatTarget.toString()
        tvInt.text = intervalMs.toString()
    }

    private fun chRep(d: Int) { repeatTarget = maxOf(0, repeatTarget + d); setLabels(); save() }
    private fun chInt(d: Int) { intervalMs = maxOf(0L, intervalMs + d); setLabels(); save() }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (recording) {
                val now = if (paused) pausedAt else SystemClock.uptimeMillis()
                tvRecTime.text = fmt(now - recStart - pausedTotal)
                tvRecCount.text = "حركات: ${strokes.size}" + if (paused) "  (متوقف مؤقتًا)" else ""
            }
            if (playing) {
                tvPlay.text = "⏱ ${fmt(SystemClock.uptimeMillis() - playStart)}\n🔁 $loops" +
                    if (repeatTarget > 0) " / $repeatTarget" else " / ∞"
            }
            if (recording || playing) main.postDelayed(this, 500)
        }
    }

    // ================= التسجيل =================
    private fun startRecord() {
        if (playing) stopPlay()
        strokes.clear()
        cpts.clear()
        recording = true; paused = false
        recStart = SystemClock.uptimeMillis(); pausedTotal = 0L
        addCatcher()
        updateUi()
        main.removeCallbacks(ticker); main.post(ticker)
    }

    private fun addCatcher() {
        val v = View(this)
        v.setBackgroundColor(Color.argb(20, 255, 0, 0))
        v.setOnTouchListener { _, e -> onCatch(e); true }
        catcherLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(v, catcherLp)
        catcher = v
        // نرفع القائمة فوق طبقة الالتقاط
        root?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            wm.addView(it, lp)
        }
    }

    private fun removeCatcher() {
        catcher?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        catcher = null
    }

    private fun onCatch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cpts.clear(); curDown = e.downTime
                cpts.add(Pt(0, e.rawX, e.rawY))
            }
            MotionEvent.ACTION_MOVE ->
                if (cpts.isNotEmpty()) cpts.add(Pt(e.eventTime - curDown, e.rawX, e.rawY))
            MotionEvent.ACTION_UP -> if (cpts.isNotEmpty()) {
                cpts.add(Pt(maxOf(e.eventTime - curDown, 1L), e.rawX, e.rawY))
                val s = Stroke(curDown - recStart - pausedTotal, cpts.toList())
                cpts.clear()
                strokes.add(s)
                updateUi()
                passThrough(s)
            }
            MotionEvent.ACTION_CANCEL -> cpts.clear()
        }
    }

    // يعيد الحركة على اللعبة بعد ما ترفع إصبعك عشان تشوف نتيجتها
    private fun passThrough(s: Stroke) {
        val v = catcher ?: return
        catcherLp.flags = catcherLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try { wm.updateViewLayout(v, catcherLp) } catch (_: Exception) { return }
        val restore: () -> Unit = {
            val c = catcher
            if (c != null && recording && !paused) {
                catcherLp.flags = catcherLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                try { wm.updateViewLayout(c, catcherLp) } catch (_: Exception) {}
            }
        }
        if (!dispatch(s, restore)) restore()
    }

    private fun togglePause() {
        if (!recording) return
        if (!paused) {
            paused = true; pausedAt = SystemClock.uptimeMillis()
            cpts.clear()
            removeCatcher()
        } else {
            pausedTotal += SystemClock.uptimeMillis() - pausedAt
            paused = false
            addCatcher()
        }
        updateUi()
    }

    private fun undo() {
        if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1)
        updateUi()
    }

    private fun stopRecord() {
        if (!recording) return
        if (paused) pausedTotal += SystemClock.uptimeMillis() - pausedAt
        removeCatcher()
        recording = false; paused = false
        if (strokes.isNotEmpty()) {
            val base = strokes.minOf { it.start }
            val fixed = strokes.map { Stroke(it.start - base, it.pts) }
            strokes.clear(); strokes.addAll(fixed)
        }
        save()
        toast("تم حفظ ${strokes.size} حركة")
        updateUi()
    }

    // ================= التشغيل =================
    private fun startPlay() {
        if (recording) stopRecord()
        if (strokes.isEmpty()) { toast("ما فيه تسجيل، اضغط ⏺ أول"); return }
        playing = true; loops = 0; playStart = SystemClock.uptimeMillis()
        updateUi()
        main.removeCallbacks(ticker); main.post(ticker)
        playStroke(0)
    }

    private fun stopPlay() {
        playing = false
        main.removeCallbacksAndMessages(null)
        updateUi()
    }

    private fun playStroke(i: Int) {
        if (!playing) return
        val s = strokes[i]
        val after: () -> Unit = {
            if (playing) {
                if (i + 1 < strokes.size) {
                    val gap = maxOf(strokes[i + 1].start - (s.start + s.dur), 0L)
                    main.postDelayed({ playStroke(i + 1) }, gap)
                } else {
                    loops++
                    if (repeatTarget > 0 && loops >= repeatTarget) {
                        val n = loops
                        stopPlay(); toast("انتهى: $n دورة")
                    } else {
                        main.postDelayed({ playStroke(0) }, intervalMs)
                    }
                }
            }
        }
        if (!dispatch(s, after)) { stopPlay(); toast("تعذر تنفيذ الحركة") }
    }

    @Suppress("DEPRECATION")
    private fun screen(): Pair<Int, Int> {
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    private fun path(s: Stroke, w: Int, h: Int): Path {
        val p = Path(); var lx = -1f; var ly = -1f; var first = true
        for (pt in s.pts) {
            val x = pt.x.coerceIn(0f, (w - 1).toFloat())
            val y = pt.y.coerceIn(0f, (h - 1).toFloat())
            if (first) { p.moveTo(x, y); first = false }
            else if (x != lx || y != ly) p.lineTo(x, y)
            lx = x; ly = y
        }
        return p
    }

    private fun dispatch(s: Stroke, done: () -> Unit): Boolean {
        val maxD = GestureDescription.getMaxGestureDuration()
        val (w, h) = screen()
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path(s, w, h), 0, minOf(s.dur, maxD)))
            .build()
        return dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done() }
            override fun onCancelled(d: GestureDescription?) { done() }
        }, null)
    }

    // ================= الحفظ =================
    private fun save() {
        val sb = StringBuilder()
        for (s in strokes) {
            sb.append(s.start).append('|')
            sb.append(s.pts.joinToString(";") { "${it.t}:${it.x}:${it.y}" })
            sb.append('\n')
        }
        prefs.edit()
            .putString("strokes", sb.toString())
            .putInt("rep", repeatTarget)
            .putLong("int", intervalMs)
            .apply()
    }

    private fun load() {
        try {
            repeatTarget = prefs.getInt("rep", 0)
            intervalMs = prefs.getLong("int", 500L)
            strokes.clear()
            val txt = prefs.getString("strokes", "") ?: ""
            for (line in txt.lines()) {
                if (line.isBlank()) continue
                val parts = line.split("|")
                val pts = parts[1].split(";").map {
                    val p = it.split(":")
                    Pt(p[0].toLong(), p[1].toFloat(), p[2].toFloat())
                }
                strokes.add(Stroke(parts[0].toLong(), pts))
            }
        } catch (_: Exception) { strokes.clear() }
    }

    private fun toast(m: String) = main.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
}
