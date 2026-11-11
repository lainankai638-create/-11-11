package com.ounan.knowledge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit consent per session, visible media-projection foreground notification,
 * local-only Chinese OCR. Does not access the private WeChat database or auto-scroll.
 * Accessibility spider shows progress and stop/pause; the captured frames never hit disk.
 */
class WeChatOcrService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("spider_session", Context.MODE_PRIVATE) }
    private val guard by lazy { getSharedPreferences("screen_guard", Context.MODE_PRIVATE) }
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var recognizer: TextRecognizer? = null
    private val busy = AtomicBoolean(false)
    private var chat = ""
    private var thisSession = 0L
    private var stopping = false
    private var accepted = 0
    private var lastFrame = 0L
    private var stopReason = "服务运行中"
    private var gotFirstFrame = false
    private var gotFirstResult = false
    private var initStep = "尚未初始化"
    private val diag by lazy { getSharedPreferences("ocr_startup_diagnostics", Context.MODE_PRIVATE) }

    private val heartbeat = object : Runnable {
        override fun run() {
            if (thisSession != 0L && !stopping) {
                OcrLifecycleLog.note(this@WeChatOcrService,
                    "OCR心跳：前台界面=${MainActivity.visible}；已收首帧=$gotFirstFrame；已获结果=$gotFirstResult")
                main.postDelayed(this, 4000L)
            }
        }
    }

    /** Stores only lifecycle stages / exception type, NEVER screenshot, chat title or OCR text. */
    private fun step(label: String) {
        initStep = label
        trace("准备：$label")
    }

    private fun sanitizedError(t: Throwable): String {
        val own = t.stackTrace.firstOrNull { it.className.contains("WeChatOcrService") }
        val where = if (own != null) "${own.methodName}:${own.lineNumber}" else "系统调用"
        // Only system exception class, short system message and line; never captured content.
        val message = t.message.orEmpty().replace(Regex("[\r\n\t]"), " ").take(80)
        return "步骤=$initStep | ${t.javaClass.simpleName} | 位置=$where" +
            (if (message.isBlank()) "" else " | $message")
    }

    private fun trace(stage: String, reason: String = "") {
        diag.edit().putString("phase", stage).putString("reason", reason.take(200))
            .putLong("updated_at", System.currentTimeMillis()).commit()
        OcrLifecycleLog.note(this, "OCR阶段：$stage" + if (reason.isBlank()) "" else "；$reason")
        android.util.Log.i("OunanWeChatOCR", "stage=$stage cause=${reason.take(120)}")
    }

    override fun onCreate() {
        super.onCreate()
        trace("服务对象已创建")
    }
    private val stitcher = ChatPageStitcher()
    private val collected = ArrayList<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopReason = "用户点击结束"
            trace("结束请求", stopReason)
            // A stale notification can start a new service solely to stop it.
            // That instance has no sessionId, so onDestroy's normal session
            // comparison cannot clear the old "recording" UI flag.
            if (thisSession == 0L && prefs.getString("mode", "") == "wechat_ocr") {
                prefs.edit().putLong("id", 0L).remove("mode").remove("chat")
                    .remove("trace_rects").remove("trace_id")
                    .putBoolean("paused", false)
                    .putString("status", "采集服务已停止；旧状态已清理").commit()
            }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || thisSession != 0L) {
            trace("启动请求无效", "action 或会话状态不符合要求")
            return START_NOT_STICKY
        }
        trace("已收到 OCR 启动请求")
        chat = intent.getStringExtra(EXTRA_CHAT)?.trim().orEmpty().take(120)
        thisSession = prefs.getLong("id", 0L)
        if (chat.isEmpty() || thisSession == 0L || prefs.getString("mode", "") != "wechat_ocr") {
            stopReason = "未取得有效采集会话"
            trace("启动失败", stopReason)
            stopSelf(); return START_NOT_STICKY
        }
        activeSessionId = thisSession
        try {
            step("创建通知渠道")
            channel()
            step("启动媒体投射前台服务")
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(ID, notification())
            trace("前台服务运行中")
            step("读取系统录屏授权令牌")
            // Android Activity.RESULT_OK is -1, NOT 0.  The old code used
            // -1 as the "missing" sentinel and accidentally rejected every
            // successful screen-capture grant before MediaProjection even ran.
            val code = intent.getIntExtra(EXTRA_CODE, Int.MIN_VALUE)
            val token = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(EXTRA_DATA)
            }
            require(code == android.app.Activity.RESULT_OK) { "系统未返回有效的录屏授权结果（resultCode=$code）" }
            require(token != null) { "系统录屏授权数据为空" }
            trace("授权令牌已交给服务")
            status("正在等待微信屏幕，OCR 每 3 秒尝试一次")
            step("构建屏幕采集管线")
            capture(code, token)
            trace("画面通道已建立，等待首帧")
            main.post(heartbeat)
            val remain = (prefs.getLong("expiry", 0L) - System.currentTimeMillis()).coerceAtLeast(0L)
            main.postDelayed({
                stopReason = "5 分钟采集时限已到"
                trace("自动到时结束")
                stopSelf()
            }, remain.coerceAtMost(5 * 60_000L))
        } catch (t: Throwable) {
            val detail = sanitizedError(t)
            stopReason = "启动异常：${t.javaClass.simpleName}"
            trace("启动失败", detail)
            status("OCR 启动失败：${t.javaClass.simpleName}；请到微信档案查看启动诊断")
            android.util.Log.e("OunanWeChatOCR", "start error", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun capture(code: Int, token: Intent) {
        step("创建 OCR 后台线程")
        workerThread = HandlerThread("ounan-wechat-ocr").apply { start() }
        worker = Handler(workerThread!!.looper)
        step("读取屏幕尺寸")
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val r = wm.maximumWindowMetrics.bounds; width = r.width(); height = r.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            width = dm.widthPixels; height = dm.heightPixels
        }
        // OEM/windowing implementations can occasionally report an unusable bounds size.
        val safeWidth = if (width > 0) width else resources.displayMetrics.widthPixels
        val safeHeight = if (height > 0) height else resources.displayMetrics.heightPixels
        require(safeWidth > 0 && safeHeight > 0) { "Invalid screen bounds" }
        val density = resources.configuration.densityDpi.takeIf { it > 0 }
            ?: resources.displayMetrics.densityDpi.coerceAtLeast(1)
        step("创建画面缓冲区")
        reader = ImageReader.newInstance(safeWidth, safeHeight, PixelFormat.RGBA_8888, 2)
        step("初始化中文 OCR 模型")
        recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        step("取得系统 MediaProjection")
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(code, token)
            ?: throw IllegalStateException("System returned null projection")
        step("注册录屏停止回调")
        projection!!.registerCallback(object: MediaProjection.Callback() {
            override fun onStop() {
                OcrLifecycleLog.note(this@WeChatOcrService, "MediaProjection.onStop 已回调")
                if (!stopping) main.post {
                    if (!stopping) {
                        stopReason = "系统已终止录屏会话（可能选择单个 APP、切换应用或录屏冲突）"
                        trace("系统终止屏幕采集", stopReason)
                        stopSelf()
                    }
                }
            }
        }, main)
        step("注册图像回调")
        reader!!.setOnImageAvailableListener({ imageReader ->
            val image = try { imageReader.acquireLatestImage() } catch (_: Exception) { null }
                ?: return@setOnImageAvailableListener
            try {
                if (!gotFirstFrame) {
                    gotFirstFrame = true
                    trace("已收到首帧屏幕画面")
                }
                val now = System.currentTimeMillis()
                if (stopping || MainActivity.visible || prefs.getBoolean("paused", false) ||
                    now - lastFrame < 3000L || !busy.compareAndSet(false, true)) return@setOnImageAvailableListener
                // No OCR if system reported a different foreground application recently.
                val lastPkg = guard.getString("last_package", "") ?: ""
                val lastTime = guard.getLong("last_package_at", 0L)
                if (now - lastTime in 0L..8000L && lastPkg.isNotBlank() && lastPkg != "com.tencent.mm") {
                    status("当前不是微信；已暂停识别")
                    busy.set(false)
                    return@setOnImageAvailableListener
                }
                lastFrame = now
                val plane = image.planes[0]
                val stride = plane.rowStride
                val pixel = plane.pixelStride
                val padded = image.width + (stride - pixel * image.width) / pixel
                val temp = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
                temp.copyPixelsFromBuffer(plane.buffer)
                val bmp = Bitmap.createBitmap(temp, 0, 0, image.width, image.height)
                temp.recycle()
                val input = InputImage.fromBitmap(bmp, 0)
                recognizer!!.process(input)
                    .addOnSuccessListener { result ->
                        if (!gotFirstResult) {
                            gotFirstResult = true
                            trace("OCR 已成功运行", "本帧识别到 ${result.textBlocks.sumOf { it.lines.size }} 行")
                        }
                        acceptResult(result, image.height)
                    }
                    .addOnFailureListener {
                        trace("OCR 处理错误", it.javaClass.simpleName)
                        status("中文 OCR 识别异常，请查看启动诊断")
                    }
                    .addOnCompleteListener { bmp.recycle(); busy.set(false) }
            } catch (e: Exception) {
                busy.set(false)
                trace("读取画面失败", e.javaClass.simpleName)
                status("读取画面失败：${e.javaClass.simpleName}")
            } finally { image.close() }
        }, worker)
        // Android 14+: the consent token and MediaProjection instance are single-use.
        step("创建虚拟显示屏")
        display = projection!!.createVirtualDisplay("OunanWeChatLocalOCR", safeWidth, safeHeight,
            density, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, worker)
            ?: throw IllegalStateException("System returned null virtual display")
    }

    private fun acceptResult(result: Text, screenHeight: Int) {
        if (stopping || prefs.getLong("id", 0L) != thisSession || prefs.getBoolean("paused", false)) return
        val all = result.textBlocks.flatMap { it.lines }
        if (all.isEmpty()) { status("录屏已启动，但画面无可识别文字（检查录屏范围）"); return }
        // Verify the user-supplied title in the upper portion on EACH frame.
        val header = all.filter { (it.boundingBox?.centerY() ?: screenHeight) < screenHeight * 0.25 }
            .joinToString("") { it.text }
        if (!OcrTextRules.titleMatches(chat, header)) {
            status("识别到 ${all.size} 行，但顶部没有匹配“${chat.take(15)}”；未保存")
            return
        }
        val ordered = all.sortedWith(compareBy({ it.boundingBox?.top ?: 0 }, { it.boundingBox?.left ?: 0 }))
        val screenWidth = reader?.width ?: resources.displayMetrics.widthPixels
        val rows = ArrayList<Pair<String, Rect>>()
        for (line in ordered) {
            val box = line.boundingBox ?: continue
            if (box.top < screenHeight * 0.19 || box.bottom > screenHeight * 0.93) continue
            val text = line.text.replace(Regex("\\s+"), " ").trim()
            if (text.isBlank() || text.length > 600 || excluded.any { text.contains(it, true) }) continue
            val side = if (box.centerX() < screenWidth * 0.46) "左侧" else if (box.centerX() > screenWidth * 0.54) "右侧" else "中间"
            rows.add("[$side·待核对] $text" to Rect(box))
        }
        if (rows.isEmpty()) { status("标题匹配，但聊天区没有识别到文字"); return }
        val result = stitcher.accept(rows.map { it.first })
        if (result.gap && collected.isNotEmpty()) {
            collected.add("【⚠ 连续性待核对：两次画面未找到共同文字，请检查是否滑动过快】")
        }
        val added = result.append.size
        val highlighted = rows.takeLast(added).map { it.second }
        collected.addAll(result.append)
        accepted = stitcher.totalLines
        if (accepted >= 500) main.post { stopSelf() }
        if (highlighted.isNotEmpty()) {
            // Only anonymous screen rectangles are passed to the floating visualizer.
            prefs.edit().putString("trace_rects", highlighted.take(12).joinToString(";") {
                "${it.left},${it.top},${it.right},${it.bottom}"
            }).putLong("trace_id", System.currentTimeMillis()).apply()
        }
        status(if (added > 0) "本屏新增 $added 行 · 共 $accepted 行 · 待核对衔接 ${stitcher.possibleGaps} 处"
            else "聊天标题已匹配 · 当前画面无新增行")
    }

    private fun status(text: String) {
        if (thisSession == 0L || prefs.getLong("id", 0L) != thisSession) return
        prefs.edit().putInt("lines", accepted).putString("status", text.take(150)).apply()
    }

    private fun channel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.createNotificationChannel(NotificationChannel(CHANNEL, "欧南微信 OCR", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(): Notification {
        val stop = PendingIntent.getService(this, 800, Intent(this, WeChatOcrService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 801, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("欧南微信 OCR 蜘蛛正在采集")
            .setContentText("仅本机识别 · 5 分钟自动停止 · 可随时结束")
            .setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, "停止采集", stop).build()).build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        OcrLifecycleLog.note(this, "OCR服务 onTaskRemoved；不会主动停止采集")
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        OcrLifecycleLog.note(this, "OCR服务 onTrimMemory=$level")
        super.onTrimMemory(level)
    }

    override fun onDestroy() {
        OcrLifecycleLog.note(this, "OCR服务 onDestroy；停止原因=$stopReason")
        main.removeCallbacks(heartbeat)
        if (stopping) { super.onDestroy(); return }
        stopping = true
        val phase = diag.getString("phase", "").orEmpty()
        // Preserve the actual failure reason rather than reporting "0 rows" after an immediate exit.
        if (phase != "启动失败" && phase != "系统终止屏幕采集" &&
            phase != "OCR 处理错误" && phase != "读取画面失败") {
            trace("采集已结束", stopReason)
        }
        runCatching { reader?.setOnImageAvailableListener(null, null) }
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        runCatching { recognizer?.close() }
        runCatching { workerThread?.quitSafely() }
        var ok = false
        if (collected.isNotEmpty()) {
            val body = "欧南微信 OCR 蜘蛛 · 本地屏幕文字\n采集于：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}\n注意：OCR 可能有错字、漏字或顺序错误；左右位置并非已确认的发送人，消息原始时间未验证。\n\n" + collected.joinToString("\n")
            ok = runCatching { WeChatArchive(this).use { it.insert(chat, body, "本地录屏 OCR · 需核对") } }.getOrDefault(false)
        }
        if (prefs.getLong("id", 0L) == thisSession) {
            prefs.edit().putLong("id", 0L).remove("chat").remove("mode")
                .putBoolean("paused", false).putInt("lines", accepted)
                .remove("trace_rects").remove("trace_id")
                .putString("status", if (ok) "已保存微信 OCR 加密档案（$accepted 行）"
                    else if (accepted == 0 && !gotFirstResult) "OCR 未能完整启动或没有识别结果；请查看微信档案中的「OCR 启动诊断」"
                    else "本次未保存：没有匹配聊天标题或没有新增文字")
                .apply()
        }
        activeSessionId = 0L
        collected.clear(); stitcher.reset(); chat = ""; thisSession = 0L
        super.onDestroy()
    }

    companion object {
        // Process-local truth; persisted session IDs can outlive a crashed service.
        @Volatile var activeSessionId: Long = 0L
            private set
        const val ACTION_START = "com.ounan.secondbrain.WECHAT_OCR_START"
        const val ACTION_STOP = "com.ounan.secondbrain.WECHAT_OCR_STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val EXTRA_CHAT = "chat"
        private const val ID = 2302
        private const val CHANNEL = "ounan_wechat_ocr"
        private val excluded = listOf("欧南采集蜘蛛", "微信档案", "已识别", "保存并停止", "暂停", "继续", "验证码", "登录密码", "助记词", "私钥", "支付密码", "银行卡号", "recovery phrase", "password")
    }
}
