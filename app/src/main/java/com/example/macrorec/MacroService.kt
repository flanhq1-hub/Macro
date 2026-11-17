package com.example.macrorec

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MacroService : AccessibilityService() {

    class Pt(val t: Long, val x: Float, val y: Float)
    class Stroke(val start: Long, val pts: List<Pt>) {
        val dur: Long get() = maxOf(pts.last().t, 1L)
    }
    private enum class Mode { IDLE, REC, PLAY }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val strokes = java.util.Collections.synchronizedList(mutableListOf<Stroke>())

    private lateinit var panel: LinearLayout
    private lateinit var lp: WindowManager.LayoutParams
    private lateinit var handle: TextView
    private lateinit var bRec: Button
    private lateinit var bPlay: Button
    private lateinit var bClear: Button
    private lateinit var repBox: LinearLayout
    private lateinit var tvRep: TextView
    @Volatile private var panelRect = Rect()

    @Volatile private var recording = false
    private var recProc: Process? = null
    private var playing = false
    private var repeatTarget = 0
    private var loops = 0
    private var clusters: List<List<Stroke>> = emptyList()
    private var prevEnd = 0L

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildPanel()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun mkBtn(t: String, f: () -> Unit) = Button(this).apply {
        text = t; textSize = 12f; isAllCaps = false
        minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setOnClickListener { f() }
    }

    private fun buildPanel() {
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(170, 20, 20, 20))
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        handle = TextView(this).apply {
            text = "⠿ اسحب"; setTextColor(Color.WHITE); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
            setOnTouchListener(object : View.OnTouchListener {
                var sx = 0f; var sy = 0f; var ox = 0; var oy = 0
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y }
                        MotionEvent.ACTION_MOVE -> {
                            lp.x = ox + (e.rawX - sx).toInt(); lp.y = oy + (e.rawY - sy).toInt()
                            wm.updateViewLayout(panel, lp); updateRect()
                        }
                    }
                    return true
                }
            })
        }
        bRec = mkBtn("⏺ تسجيل") { if (recording) stopRecord() else startRecord() }
        bPlay = mkBtn("▶ تشغيل") { if (playing) stopPlay() else startPlay() }
        bClear = mkBtn("🗑 مسح") { strokes.clear(); toast("تم المسح") }
        tvRep = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0); text = "∞"
        }
        val minus = mkBtn("−") { repeatTarget = maxOf(0, repeatTarget - 1); showRep() }
        val plus = mkBtn("+") { repeatTarget += 1; showRep() }
        val label = TextView(this).apply {
            text = "التكرار"; setTextColor(Color.WHITE); textSize = 11f; gravity = Gravity.CENTER
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            addView(minus); addView(tvRep); addView(plus)
        }
        repBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; addView(label); addView(row)
        }
        panel.addView(handle); panel.addView(bRec); panel.addView(bPlay)
        panel.addView(repBox); panel.addView(bClear)

        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = dp(120) }
        wm.addView(panel, lp)
        panel.viewTreeObserver.addOnGlobalLayoutListener { updateRect() }
        setMode(Mode.IDLE)
    }

    private fun updateRect() {
        val l = IntArray(2); panel.getLocationOnScreen(l)
        val m = dp(12)
        panelRect = Rect(l[0] - m, l[1] - m, l[0] + panel.width + m, l[1] + panel.height + m)
    }

    private fun showRep() { tvRep.text = if (repeatTarget == 0) "∞" else repeatTarget.toString() }

    private fun setMode(m: Mode) {
        main.post {
            bRec.visibility = if (m == Mode.PLAY) View.GONE else View.VISIBLE
            bPlay.visibility = if (m == Mode.REC) View.GONE else View.VISIBLE
            repBox.visibility = if (m == Mode.IDLE) View.VISIBLE else View.GONE
            bClear.visibility = if (m == Mode.IDLE) View.VISIBLE else View.GONE
            bRec.text = if (m == Mode.REC) "⏹ إيقاف" else "⏺ تسجيل"
            bPlay.text = if (m == Mode.PLAY) "⏹ إيقاف" else "▶ تشغيل"
            panel.post { updateRect() }
        }
    }

    private fun sh(cmd: String): Process {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        return m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
    }

    private fun startRecord() {
        val ok = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        if (!ok) { toast("فعّل Shizuku وامنحه الصلاحية من التطبيق أولًا"); return }
        if (playing) stopPlay()
        strokes.clear(); recording = true; setMode(Mode.REC)
        Thread {
            try { runRecorder() }
            catch (e: Exception) { if (recording) main.post { toast("خطأ بالتسجيل: ${e.message}"); stopRecord() } }
        }.start()
    }

    private fun stopRecord() {
        recording = false
        try { recProc?.destroy() } catch (_: Throwable) {}
        recProc = null
        setMode(Mode.IDLE)
        toast("تم حفظ ${strokes.size} حركة")
    }

    private fun screenInfo(): Triple<Int, Int, Int> {
        val d = (getSystemService(DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val dm = DisplayMetrics(); @Suppress("DEPRECATION") d.getRealMetrics(dm)
        return Triple(dm.widthPixels, dm.heightPixels, d.rotation)
    }

    private fun runRecorder() {
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

        fun conv(x: Int, y: Int): Pair<Float, Float> {
            val (w, h, rot) = screenInfo()
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
        var slot = 0; var origin = -1L

        fun flush(t: Long) {
            for (s in 0 until N) {
                if (down[s]) {
                    act[s] = true; down[s] = false; dirty[s] = true
                    raw[s] = mutableListOf(); st[s] = t
                    val (sx, sy) = conv(x[s], y[s])
                    skip[s] = panelRect.contains(sx.toInt(), sy.toInt())
                }
                if (act[s] && dirty[s]) {
                    val (sx, sy) = conv(x[s], y[s])
                    raw[s]!!.add(Pt(t - st[s], sx, sy))
                }
                dirty[s] = false
                if (up[s]) {
                    if (act[s] && !skip[s] && raw[s]!!.isNotEmpty()) {
                        if (origin < 0) origin = st[s]
                        strokes.add(Stroke(st[s] - origin, raw[s]!!.toList()))
                    }
                    act[s] = false; up[s] = false
                }
            }
        }

        val reader = p.inputStream.bufferedReader()
        while (recording) {
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

    private fun startPlay() {
        if (recording) stopRecord()
        val list = synchronized(strokes) { strokes.toList() }
        if (list.isEmpty()) { toast("ما فيه تسجيل"); return }
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
        clusters = out; playing = true; loops = 0; prevEnd = 0L
        setMode(Mode.PLAY)
        runCluster(0)
    }

    private fun stopPlay() {
        playing = false
        main.removeCallbacksAndMessages(null)
        setMode(Mode.IDLE)
    }

    private fun path(s: Stroke, w: Int, h: Int): Path {
        val p = Path(); var lx = -1f; var ly = -1f; var first = true
        for (pt in s.pts) {
            val x = pt.x.coerceIn(0f, (w - 1).toFloat()); val y = pt.y.coerceIn(0f, (h - 1).toFloat())
            if (first) { p.moveTo(x, y); first = false }
            else if (x != lx || y != ly) p.lineTo(x, y)
            lx = x; ly = y
        }
        return p
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
            val (w, h, _) = screenInfo()
            val b = GestureDescription.Builder()
            for (s in c) b.addStroke(
                GestureDescription.StrokeDescription(path(s, w, h), s.start - cs, minOf(s.dur, maxD)))
            val after = { prevEnd = ce; nextCluster(i) }
            val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { after() }
                override fun onCancelled(g: GestureDescription?) { after() }
            }, null)
            if (!ok) after()
        }, gap)
    }

    private fun nextCluster(i: Int) {
        if (!playing) return
        if (i + 1 < clusters.size) { runCluster(i + 1); return }
        loops++
        if (repeatTarget > 0 && loops >= repeatTarget) { stopPlay(); toast("انتهى التكرار"); return }
        prevEnd = 0L
        main.postDelayed({ runCluster(0) }, 300)
    }

    private fun toast(m: String) = main.post { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() {
        recording = false; try { recProc?.destroy() } catch (_: Throwable) {}
        playing = false; main.removeCallbacksAndMessages(null)
        try { wm.removeView(panel) } catch (_: Exception) {}
        super.onDestroy()
    }
}
