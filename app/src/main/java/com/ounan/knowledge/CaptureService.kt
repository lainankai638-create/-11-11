package com.ounan.knowledge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * User-started, visible and temporary text capture.
 * One MediaProjection permission -> one createVirtualDisplay call.
 * Screenshots never hit disk; only OCR text is saved.
 */
class CaptureService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val database by lazy { KnowledgeDb(this) }
    private var workerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    private var recognizer: TextRecognizer? = null
    private val busy = AtomicBoolean(false)
    private var lastScan = 0L
    private var lastText = ""
    private var saved = 0
    private val expire = Runnable { stopSelf() }
    private var stopped = false
    private var screenOffRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) stopSelf()
        }
    }
    private val privateKeywords = listOf(
        "验证码", "短信验证", "支付密码", "银行卡号", "身份证号",
        "password", "verification code", "one-time password", "2fa", "recovery phrase",
        "助记词", "密钥", "私钥", "登录密码"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START || running) return START_NOT_STICKY
        stopped = false
        createChannel()
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
            // Activity.RESULT_OK == -1. Never use -1 as an invalid-code
            // sentinel: doing so silently terminates a successful capture.
            val code = intent.getIntExtra(EXTRA_CODE, Int.MIN_VALUE)
            val data = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(EXTRA_DATA)
            }
            if (code != android.app.Activity.RESULT_OK || data == null) {
                android.util.Log.w("OunanCapture", "No valid media-projection result: code=$code dataPresent=${data != null}")
                stopSelf()
                return START_NOT_STICKY
            }
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
            screenOffRegistered = true
            startCapture(code, data)
            running = true
            main.postDelayed(expire, MAX_DURATION_MS)
        } catch (t: Throwable) {
            android.util.Log.e("OunanCapture", "Capture startup failed", t)
            stopSelf()
        }
        return START_NOT_STICKY // never automatically restart without user consent
    }

    private fun startCapture(code: Int, data: Intent) {
        workerThread = HandlerThread("ou-nan-ocr-worker").apply { start() }
        worker = Handler(workerThread!!.looper)
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val width: Int
        val height: Int
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            width = bounds.width()
            height = bounds.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            width = dm.widthPixels
            height = dm.heightPixels
        }
        val density = resources.displayMetrics.densityDpi
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // MediaProjection must be obtained after this foreground service starts.
        projection = manager.getMediaProjection(code, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The user, the OS lock screen or the status-bar chip ended projection.
                main.post { stopSelf() }
            }
        }, main)
        reader!!.setOnImageAvailableListener({ r ->
            val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val now = System.currentTimeMillis()
                if (MainActivity.visible || now - lastScan < SCAN_INTERVAL_MS || !busy.compareAndSet(false, true)) {
                    return@setOnImageAvailableListener
                }
                lastScan = now
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val paddedWidth = image.width + (rowStride - pixelStride * image.width) / pixelStride
                val temp = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                temp.copyPixelsFromBuffer(buffer)
                val bmp = Bitmap.createBitmap(temp, 0, 0, image.width, image.height)
                temp.recycle()
                val input = InputImage.fromBitmap(bmp, 0)
                val ocr = recognizer
                if (ocr == null) {
                    bmp.recycle()
                    busy.set(false)
                    return@setOnImageAvailableListener
                }
                ocr.process(input)
                    .addOnSuccessListener { result ->
                        val text = result.text.trim()
                        val queue = worker
                        if (queue != null) queue.post { storeIfUseful(text) }
                    }
                    .addOnFailureListener { android.util.Log.w("OunanCapture", "OCR frame failed", it) }
                    .addOnCompleteListener {
                        bmp.recycle()
                        busy.set(false)
                    }
            } catch (t: Throwable) {
                busy.set(false)
                android.util.Log.w("OunanCapture", "Frame decode failed", t)
            } finally {
                image.close()
            }
        }, worker)
        // Android 14+: exactly one createVirtualDisplay per consent / MediaProjection object.
        display = projection!!.createVirtualDisplay(
            "OunanKnowledgeOCR", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, worker
        )
    }

    private fun storeIfUseful(text: String) {
        // Prevent generic screenshots from entering the AI-searchable notes when the
        // user is in a dedicated chat archive session, or if the enabled scoped
        // accessibility service recently observed WeChat. Not a perfect app detector.
        val now = System.currentTimeMillis()
        if (getSharedPreferences("wechat_capture", MODE_PRIVATE).getLong("expires", 0L) > now) return
        val lastWeChat = getSharedPreferences("screen_guard", MODE_PRIVATE).getLong("last_wechat_event", 0L)
        if (now - lastWeChat in 0L..30_000L) return
        if (stopped || saved >= MAX_SAVED || text.length < MIN_CHARS) return
        if (privateKeywords.any { text.contains(it, ignoreCase = true) }) return
        // Screen navigation often changes a line or two. Keep only substantial differences.
        if (similarity(text, lastText) > 0.82) return
        lastText = text
        try {
            if (database.insert(text, "自动采集 · 来源应用未识别", minChars = MIN_CHARS)) saved++
        } catch (e: Exception) {
            android.util.Log.e("OunanCapture", "Local database write failed", e)
        }
        if (saved >= MAX_SAVED) main.post { stopSelf() }
    }

    private fun similarity(a: String, b: String): Double {
        if (b.isBlank()) return 0.0
        val left = a.lines().map { it.trim() }.filter { it.length > 5 }.toSet()
        val right = b.lines().map { it.trim() }.filter { it.length > 5 }.toSet()
        if (left.isEmpty() || right.isEmpty()) return 0.0
        return left.intersect(right).size.toDouble() / minOf(left.size, right.size)
    }

    private fun notification(): Notification {
        val stopIntent = Intent(this, CaptureService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, 123, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 124, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("欧南知识中枢正在识别屏幕")
            .setContentText("仅本地 OCR · 最长 10 分钟 · 点此管理")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "立即停止", pending).build())
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "屏幕采集会话", NotificationManager.IMPORTANCE_LOW).apply {
                description = "会话运行状态与立即停止入口"
            })
        }
    }

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacks(expire)
        if (screenOffRegistered) {
            try { unregisterReceiver(screenOffReceiver) } catch (_: Exception) {}
            screenOffRegistered = false
        }
        try { reader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { display?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        try { recognizer?.close() } catch (_: Exception) {}
        try { workerThread?.quitSafely() } catch (_: Exception) {}
        try { database.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.ounan.knowledge.START_CAPTURE"
        const val ACTION_STOP = "com.ounan.knowledge.STOP_CAPTURE"
        const val EXTRA_CODE = "projectionResultCode"
        const val EXTRA_DATA = "projectionResultData"
        private const val CHANNEL_ID = "ounan_screen_capture"
        private const val NOTIFICATION_ID = 2301
        private const val SCAN_INTERVAL_MS = 5_000L
        private const val MAX_DURATION_MS = 10 * 60 * 1000L
        private const val MIN_CHARS = 80
        private const val MAX_SAVED = 50
        @Volatile var running = false
    }
}
