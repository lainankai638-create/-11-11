package com.ounan.knowledge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Compact visible bubble. A user tap expands actions; no automated scrolling/tapping. */
class SpiderOverlay(
    private val ctx: Context,
    private val onStop: () -> Unit,
    private val onPause: () -> Unit,
    private val onRequest: (String) -> Unit = {}
) {
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val h = Handler(Looper.getMainLooper())
    private var root: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var trace: TraceView? = null
    private var badge: TextView? = null
    private var detail: TextView? = null
    private var actions: LinearLayout? = null
    private var pauseLabel: TextView? = null
    private var running = true
    private var count = 0
    private var isPaused = false
    private var ongoing = false
    private var stopLabel: TextView? = null
    private var startLabel: TextView? = null
    private var scanLabel: TextView? = null
    private val dp get() = ctx.resources.displayMetrics.density
    private fun d(n: Int) = (n*dp + .5f).toInt()
    private fun bg(): GradientDrawable = GradientDrawable().apply {
        setColor(Color.argb(237, 13, 28, 49)); cornerRadius=d(16).toFloat()
        setStroke(d(1), Color.argb(170, 69, 183, 228))
    }
    private fun label(text: String, size: Float = 13f) = TextView(ctx).apply {
        this.text = text; textSize=size; setTextColor(Color.WHITE)
        setPadding(d(10),d(9),d(10),d(9))
    }
    fun show(): Boolean {
        if (root != null) return true
        val lineCanvas = TraceView(ctx)
        val paintParams=WindowManager.LayoutParams(-1,-1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT)
        try { wm.addView(lineCanvas, paintParams); trace = lineCanvas }
        catch (_: Exception) { return false }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = bg()
        }
        val top=label("🕷 ·",13f)
        top.setOnClickListener { toggleMenu() }
        top.setOnTouchListener(object: View.OnTouchListener {
            var x0=0f; var y0=0f; var p0x=0; var p0y=0; var dragged=false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                val lp=params ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        x0=event.rawX; y0=event.rawY; p0x=lp.x; p0y=lp.y; dragged=false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx=event.rawX-x0; val dy=event.rawY-y0
                        if (abs(dx)>d(8) || abs(dy)>d(8)) dragged=true
                        if(dragged) {
                            lp.x=(p0x-dx.toInt()).coerceIn(0,ctx.resources.displayMetrics.widthPixels-d(55))
                            lp.y=(p0y+dy.toInt()).coerceIn(0,ctx.resources.displayMetrics.heightPixels-d(95))
                            runCatching { wm.updateViewLayout(panel,lp) }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> { if(!dragged) toggleMenu(); return true }
                    else -> return true
                }
            }
        })
        badge=top
        panel.addView(top)
        val menu=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE }
        detail=label("待机 · 点击启动",11f).also { menu.addView(it) }
        fun action(text: String, click: () -> Unit): TextView {
            val b=label(text,12f).apply { setOnClickListener { click(); collapse() } }
            menu.addView(b); return b
        }
        pauseLabel=action("⏸ 暂停 / 继续") { onPause() }
        stopLabel=action("■ 保存并停止") { onStop() }
        startLabel=action("▶ 开始采集此 APP（直到手动停止）") { onRequest("start_continuous") }
        scanLabel=action("↻ 手动补扫当前页") { onRequest("scan_now") }
        action("💬 微信节点采集（可用时）") { onRequest("wechat") }
        action("▣ 微信 OCR（推荐）") { onRequest("wechat_ocr") }
        action("▤ 打开知识库") { onRequest("library") }
        actions=menu
        panel.addView(menu)
        val p=WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.END; x=d(6); y=d(130)
        }
        return try { wm.addView(panel,p); root=panel; params=p; update(false,false,0L,0,""); true }
            catch (_:Exception) { close(); false }
    }
    fun setIdle(reason: String = "待机 · 不抓取文字；请到阅读页面手动点击开始") {
        running=false; count=0; isPaused=false; ongoing=false
        trace?.highlight(emptyList())
        update(false,false,0L,0,reason)
    }
    fun update(isWechat:Boolean, paused:Boolean, remainingSec:Long, count:Int, status:String, continuous:Boolean=false) {
        ongoing=continuous
        running=continuous || remainingSec>0L; this.count=count; isPaused=paused
        badge?.text=if(!running) "🕷" else "🕷 ${count.coerceAtLeast(0)}"
        detail?.text=if(!running) status.take(110) else
            "${if(isWechat) "微信" else "知识"} · ${if(paused) "已暂停" else "持续采集中"} · ${if(continuous) "手动停止" else "${remainingSec}s"}\n${status.take(60)}"
        pauseLabel?.visibility=if(running) View.VISIBLE else View.GONE
        stopLabel?.visibility=if(running) View.VISIBLE else View.GONE
        startLabel?.visibility=if(running) View.GONE else View.VISIBLE
        scanLabel?.visibility=if(running) View.VISIBLE else View.GONE
    }
    fun highlight(rects: List<Rect>) { trace?.highlight(rects) }
    fun trace(rects: List<Rect>) = highlight(rects)
    private fun toggleMenu() {
        val menu=actions ?: return
        menu.visibility=if(menu.visibility==View.VISIBLE) View.GONE else View.VISIBLE
    }
    private fun collapse() { actions?.visibility=View.GONE }
    fun close() {
        root?.let { runCatching { wm.removeViewImmediate(it) } }
        root=null; actions=null; badge=null; detail=null; pauseLabel=null; stopLabel=null; startLabel=null; scanLabel=null; params=null
        trace?.let { runCatching { wm.removeViewImmediate(it) } }; trace=null
    }
    private class TraceView(ctx: Context) : View(ctx) {
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = ctx.resources.displayMetrics.density * 2.5f
            color = Color.rgb(63, 210, 253)
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL; color = Color.rgb(27, 168, 227)
        }
        private var rects: List<Rect> = emptyList()
        private var index = 0
        private val h = Handler(Looper.getMainLooper())
        private val clear = Runnable { rects = emptyList(); invalidate() }
        private val step = object : Runnable {
            override fun run() {
                if (index < rects.lastIndex) {
                    index++
                    invalidate()
                    h.postDelayed(this, 175L)
                } else h.postDelayed(clear, 750L)
            }
        }
        fun highlight(newRects: List<Rect>) {
            rects = newRects.map { Rect(it) }
            index = 0
            invalidate(); h.removeCallbacks(clear); h.removeCallbacks(step)
            if (rects.isNotEmpty()) h.postDelayed(step, 175L)
        }
        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (rects.isEmpty()) return
            val r = rects[index.coerceIn(0, rects.lastIndex)]
            c.drawRoundRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat(), 9f, 9f, stroke)
            val cx = r.centerX().toFloat(); val cy = r.centerY().toFloat()
            val d = 10f * resources.displayMetrics.density
            // Four animated legs point to the text that was actually read.
            for (a in listOf(-1f, 1f)) for (b in listOf(-1f, 1f)) {
                val p = Path().apply { moveTo(cx + a*d*.2f, cy + b*d*.2f); lineTo(cx + a*d, cy + b*d); lineTo(cx + a*d*1.5f, cy + b*d) }
                c.drawPath(p, stroke)
            }
            c.drawCircle(cx, cy, 3.5f * resources.displayMetrics.density, fill)
            if (index > 0) {
                val prev = rects[index - 1]
                c.drawLine(prev.centerX().toFloat(), prev.centerY().toFloat(), cx, cy, stroke)
            }
        }
        override fun onDetachedFromWindow() {
            h.removeCallbacks(clear); h.removeCallbacks(step)
            super.onDetachedFromWindow()
        }
    }
}
