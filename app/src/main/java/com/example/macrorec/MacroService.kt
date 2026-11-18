package com.example.macrorec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
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
    private val strokes = java.util.Collections.synchronizedList(mutableListOf<Stroke>())

    private var root: DragBar? = null
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var bubble: TextView
    private lateinit var rowIdle: LinearLayout
    private lateinit var rowSet: LinearLayout
    private lateinit var rowRec: LinearLayout
    private lateinit var rowPlay: LinearLayout
    private lateinit var tvRep: TextView
    private lateinit var tvInt: TextView
    private lateinit var tvMode: TextView
    private lateinit var tvRecTime: TextView
    private lateinit var tvRecCount: TextView
    private lateinit var tvPlay: TextView
    private lateinit var btnPause: TextView
    private var catcher: View? = null
    private lateinit var catcherLp: WindowManager.LayoutParams
    @Volatile private var barRect = Rect()

    private var barShown = false
    private var collapsed = false
    private var settingsOpen = false
    @Volatile private var recording = false
    @Volatile private var paused = false
    private var playing = false
    private var recStart = 0L
    private var pausedAt = 0L
    private var pausedTotal = 0L
    private var repeatTarget = 0
    private var intervalMs = 500L
    private var liveMode = false
    private var useLive = false
    @Volatile private var liveRunning = false
    private var recProc: Process? = null
    private var loops = 0
    private var playStart = 0L
    private var clusters: List<List<Stroke>> = emptyList()
    private var prevEnd = 0L
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
                        post { updateBarRect() }
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
        tvMode = tv("", 14f) { toggleMode() }
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
            addView(hor(tv("الوضع", 13f), tvMode))
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

    private fun updateBarRect() {
        val r = root ?: return
        if (!barShown) return
        val l = IntArray(2); r.getLocationOnScreen(l)
        val m = dp(12)
        barRect = Rect(l[0] - m, l[1] - m, l[0] + r.width + m, l[1] + r.height + m)
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
        tvRecCount.text = "حركات: ${strokes.size}" + (if (paused) "  (متوقف مؤقتًا)" else "") +
            (if (useLive) "  ⚡" else "")
        root?.post { updateBarRect() }
    }

    private fun setLabels() {
        tvRep.text = if (repeatTarget == 0) "∞" else repeatTarget.toString()
        tvInt.text = intervalMs.toString()
        tvMode.text = if (liveMode) "⚡ فوري" else "طبقة شفافة"
    }

    private fun chRep(d: Int) { repeatTarget = maxOf(0, repeatTarget + d); setLabels(); save() }
    private fun chInt(d: Int) { intervalMs = maxOf(0L, intervalMs + d); setLabels(); save() }

    private fun toggleMode() {
        if (!liveMode) {
            if (!shizukuReady()) {
                toast("Shizuku غير جاهز: شغّله وامنحه الصلاحية من التطبيق أولًا")
                return
            }
            liveMode = true
        } else liveMode = false
        setLabels(); save()
    }

    private fun fmt(ms: Long): String {
        val s = maxOf(ms, 0L) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (recording) {
                val now = if (paused) pausedAt else SystemClock.uptimeMillis()
                tvRecTime.text = fmt(now - recStart - pausedTotal)
                tvRecCount.text = "حركات: ${strokes.size}" + (if (paused) "  (متوقف مؤقتًا)" else "") +
                    (if (useLive) "  ⚡" else "")
            }
            if (playing) {
                tvPlay.text = "⏱ ${fmt(SystemClock.uptimeMillis() - playStart)}\n🔁 $loops" +
                    if (repeatTarget > 0) " / $repeatTarget" else " / ∞"
            }
            if (recording || playing) main.postDelayed(this, 500)
        }
    }

    // ================= Shizuku =================
    private fun shizukuReady(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun sh(cmd: String): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    @Suppress("DEPRECATION")
    private fun screenInfo(): Triple<Int, Int, Int> {
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val dm = DisplayMetrics(); d.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, d.rotation)
    }

    // ================= التسجيل =================
    private fun startRecord() {
        if (playing) stopPlay()
        synchronized(strokes) { strokes.clear() }
        cpts.clear()
        recording = true; paused = false
        recStart = SystemClock.uptimeMillis(); pausedTotal = 0L
        useLive = liveMode && shizukuReady()
        if (liveMode && !useLive) toast("Shizuku غير جاهز، استخدمت الطبقة الشفافة")
        if (useLive) startLive() else addCatcher()
        updateUi()
        main.removeCallbacks(ticker); main.post(ticker)
    }

    private fun stopRecord() {
        if (!recording) return
        if (paused) pausedTotal += SystemClock.uptimeMillis() - pausedAt
        liveRunning = false
        try { recProc?.destroy() } catch (_: Throwable) {}
        recProc = null
        removeCatcher()
        recording = false; paused = false
        synchronized(strokes) {
            if (strokes.isNotEmpty()) {
                val base = strokes.minOf { it.start }
                val fixed = strokes.map { Stroke(it.start - base, it.pts) }
                strokes.clear(); strokes.addAll(fixed)
            }
        }
        save()
        toast("تم حفظ ${strokes.size} حركة")
        useLive = false
        updateUi()
    }

    private fun togglePause() {
        if (!recording) return
        if (!paused) {
            paused = true; pausedAt = SystemClock.uptimeMillis()
            cpts.clear()
            if (!useLive) removeCatcher()
        } else {
            pausedTotal += SystemClock.uptimeMillis() - pausedAt
            paused = false
            if (!useLive) addCatcher()
        }
        updateUi()
    }

    private fun undo() {
        synchronized(strokes) { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1) }
        updateUi()
    }

    // ---------- الوضع الشفاف ----------
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
        if (!dispatchList(listOf(s), s.start, restore)) restore()
    }

    // ---------- الوضع الفوري (Shizuku + getevent) ----------
    private fun startLive() {
        liveRunning = true
        Thread {
            try { runLive() }
            catch (e: Exception) {
                if (liveRunning) main.post { toast("خطأ بالتسجيل الفوري: ${e.message}"); stopRecord() }
            }
        }.start()
    }

    private fun runLive() {
        val p0 = sh("getevent -lp")
        val info = p0.inputStream.bufferedReader().readText(); p0.waitFor()
        var cur: String? = null; var dev: String? = null
        var cx = 0; var cy = 0; var mx = 0; var my = 0
        val rx = Regex("ABS_MT_POSITION_X.*max (\\d+)"); val ry = Regex("ABS_MT_POSITION_Y.*max (\\d+)")
        for (line in info.lines()) {
            val l = line.trim()
            if (l.startsWith("add device")) { cur = l.substringAfter(": ").trim(); cx = 0; cy = 0 }
            rx.find(l)?.let { cx = it.groupValues[1].toInt() }
            ry.find(l)?.let { cy = it.groupValues[1].toInt() }
            if (dev == null && cx > 0 && cy > 0) { dev = cur; mx = cx; my = cy }
        }
        if (dev == null) { main.post { toast("ما لقيت شاشة اللمس"); stopRecord() }; return }

        var si = screenInfo()
        fun conv(x: Int, y: Int): Pair<Float, Float> {
            val (w, h, rot) = si
            val fx = x / mx.toFloat(); val fy = y / my.toFloat()
            return when (rot) {
                0 -> Pair(fx * w, fy * h)
                1 -> Pair(fy * w, (1 - fx) * h)
                2 -> Pair((1 - fx) * w, (1 - fy) * h)
                else -> Pair((1 - fy) * w, fx * h)
            }
        }

        val p = sh("getevent -lt $dev"); recProc = p
        val re = Regex("\\[\\s*(\\d+)\\.(\\d+)\\]\\s+(?:/dev/\\S+:\\s+)?(EV_\\w+)\\s+(\\w+)\\s+(\\w+)")
        val N = 10
        val x = IntArray(N); val y = IntArray(N)
        val act = BooleanArray(N); val down = BooleanArray(N); val up = BooleanArray(N)
        val dirty = BooleanArray(N); val skip = BooleanArray(N)
        val st = LongArray(N); val raw = arrayOfNulls<MutableList<Pt>>(N)
        var slot = 0

        fun flush(t: Long) {
            for (s in 0 until N) {
                if (down[s]) {
                    si = screenInfo()
                    act[s] = true; down[s] = false; dirty[s] = true
                    raw[s] = mutableListOf(); st[s] = t
                    val (sx, sy) = conv(x[s], y[s])
                    // لمسات القائمة العائمة وأثناء الإيقاف المؤقت ما تنحفظ
                    skip[s] = paused || barRect.contains(sx.toInt(), sy.toInt())
                }
                if (act[s] && dirty[s]) {
                    val (sx, sy) = conv(x[s], y[s])
                    raw[s]!!.add(Pt(t - st[s], sx, sy))
                }
                dirty[s] = false
                if (up[s]) {
                    if (act[s] && !skip[s] && raw[s]!!.isNotEmpty()) {
                        strokes.add(Stroke(st[s], raw[s]!!.toList()))
                    }
                    act[s] = false; up[s] = false
                }
            }
        }

        val reader = p.inputStream.bufferedReader()
        while (liveRunning) {
            val line = reader.readLine() ?: break
            val m = re.find(line) ?: continue
            val t = m.groupValues[1].toLong() * 1000 + m.groupValues[2].toLong() / 1000
            val type = m.groupValues[3]; val code = m.groupValues[4]; val v = m.groupValues[5]
            if (type == "EV_ABS") when (code) {
                "ABS_MT_SLOT" -> slot = v.toLong(16).toInt().coerceIn(0, N - 1)
                "ABS_MT_TRACKING_ID" -> if (v.equals("ffffffff", true)) up[slot] = true else down[slot] = true
                "ABS_MT_POSITION_X" -> { x[slot] = v.toLong(16).toInt(); dirty[slot] = true }
                "ABS_MT_POSITION_Y" -> { y[slot] = v.toLong(16).toInt(); dirty[slot] = true }
            } else if (type == "EV_SYN" && code == "SYN_REPORT") flush(t)
        }
    }

    // ================= التشغيل =================
    private fun buildClusters(list: List<Stroke>): List<List<Stroke>> {
        val maxD = GestureDescription.getMaxGestureDuration()
        val maxN = GestureDescription.getMaxStrokeCount()
        val out = mutableListOf<MutableList<Stroke>>()
        var cs = 0L; var ce = 0L
        for (s in list.sortedBy { it.start }) {
            val c = out.lastOrNull()
            val e = s.start + minOf(s.dur, maxD)
            if (c != null && s.start < ce && c.size < maxN && e - cs <= maxD) { c.add(s); ce = maxOf(ce, e) }
            else { out.add(mutableListOf(s)); cs = s.start; ce = e }
        }
        return out
    }

    private fun startPlay() {
        if (recording) stopRecord()
        val list = synchronized(strokes) { strokes.toList() }
        if (list.isEmpty()) { toast("ما فيه تسجيل، اضغط ⏺ أول"); return }
        clusters = buildClusters(list)
        playing = true; loops = 0; prevEnd = 0L; playStart = SystemClock.uptimeMillis()
        updateUi()
        main.removeCallbacks(ticker); main.post(ticker)
        runCluster(0)
    }

    private fun stopPlay() {
        playing = false
        main.removeCallbacksAndMessages(null)
        updateUi()
    }

    private fun runCluster(i: Int) {
        if (!playing) return
        val maxD = GestureDescription.getMaxGestureDuration()
        val c = clusters[i]
        val cs = c.minOf { it.start }
        val ce = c.maxOf { it.start + minOf(it.dur, maxD) }
        val gap = if (i == 0) 0L else maxOf(cs - prevEnd, 0L)
        main.postDelayed({
            if (!playing) return@postDelayed
            val after: () -> Unit = { prevEnd = ce; nextCluster(i) }
            if (!dispatchList(c, cs, after)) { stopPlay(); toast("تعذر تنفيذ الحركة") }
        }, gap)
    }

    private fun nextCluster(i: Int) {
        if (!playing) return
        if (i + 1 < clusters.size) { runCluster(i + 1); return }
        loops++
        if (repeatTarget > 0 && loops >= repeatTarget) {
            val n = loops
            stopPlay(); toast("انتهى: $n دورة"); return
        }
        prevEnd = 0L
        main.postDelayed({ runCluster(0) }, intervalMs)
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

    private fun dispatchList(list: List<Stroke>, base: Long, done: () -> Unit): Boolean {
        val maxD = GestureDescription.getMaxGestureDuration()
        val (w, h, _) = screenInfo()
        val b = GestureDescription.Builder()
        for (s in list) b.addStroke(
            GestureDescription.StrokeDescription(path(s, w, h), s.start - base, minOf(s.dur, maxD)))
        return dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done() }
            override fun onCancelled(d: GestureDescription?) { done() }
        }, null)
    }

    // ================= الحفظ =================
    private fun save() {
        val sb = StringBuilder()
        val list = synchronized(strokes) { strokes.toList() }
        for (s in list) {
            sb.append(s.start).append('|')
            sb.append(s.pts.joinToString(";") { "${it.t}:${it.x}:${it.y}" })
            sb.append('\n')
        }
        prefs.edit()
            .putString("strokes", sb.toString())
            .putInt("rep", repeatTarget)
            .putLong("int", intervalMs)
            .putBoolean("live", liveMode)
            .apply()
    }

    private fun load() {
        try {
            repeatTarget = prefs.getInt("rep", 0)
            intervalMs = prefs.getLong("int", 500L)
            liveMode = prefs.getBoolean("live", false)
            synchronized(strokes) {
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
            }
        } catch (_: Exception) { synchronized(strokes) { strokes.clear() } }
    }

    private fun toast(m: String) = main.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
}
