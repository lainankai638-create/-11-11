package com.ounan.knowledge

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityWindowInfo
import android.content.Intent
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Explicit, visible accessibility reading session. No automatic clicks, scrolls,
 * hidden history access or network transfers. Text is saved only on session end.
 * The floating overlay uses TYPE_ACCESSIBILITY_OVERLAY, not SYSTEM_ALERT_WINDOW.
 */
class WeChatReaderService : AccessibilityService() {
    private val ui = Handler(Looper.getMainLooper())
    private val settings by lazy { getSharedPreferences("spider_session", Context.MODE_PRIVATE) }
    private val notes by lazy { KnowledgeDb(this) }
    private val chats by lazy { WeChatArchive(this) }
    private var spider: SpiderOverlay? = null
    private var sessionId = 0L
    private var mode = ""
    private var chat = ""
    private var expiry = 0L
    private var paused = false
    private var lastScanAt = 0L
    private var lastSeenPackage = ""
    // Only assigned when the user explicitly starts from the floating bubble OVER the target APP.
    // Showing the spider in Ounan itself is standby-only and must never arm a session.
    private var captureTargetPackage = ""
    private var bufferFull = false
    private var bufferChars = 0
    private val seen = LinkedHashSet<String>()
    private val document = ArrayList<String>()
    private val chatStitcher = ChatPageStitcher()
    private val shortcut by lazy { getSharedPreferences("spider_shortcut", Context.MODE_PRIVATE) }
    private var lastReason = "等待打开需要阅读的 APP"
    private var lastPreview = ""
    private var lastOcrTrace = 0L
    private var lastDiagnosticScope = ""
    private val ticker = object : Runnable {
        override fun run() {
            try {
                updateSession()
                // The user starts an explicit knowledge session; from then on scrolling,
                // comments and new views are collected until they press Stop / Pause.
                if (sessionId != 0L && !paused && (mode == "wechat" || mode == "fragment")) scanActiveApp()
                if (sessionId == 0L) updateStandby()
            } catch (e: Exception) {
                lastReason = "扫描异常：${e.javaClass.simpleName}"
                updateBubble()
            }
            ui.postDelayed(this, 1700L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        OcrLifecycleLog.note(this, "无障碍服务 onServiceConnected")
        val persisted = settings.getLong("id", 0L)
        if (settings.getString("mode", "") == "wechat_ocr" && persisted != 0L &&
            WeChatOcrService.activeSessionId != persisted &&
            System.currentTimeMillis() - persisted > 15_000L) {
            settings.edit().putLong("id", 0L).remove("mode").remove("chat")
                .remove("trace_rects").remove("trace_id")
                .putBoolean("paused", false)
                .putString("status", "录屏服务已结束，旧采集状态已清除").commit()
        }
        // A resurrected accessibility service has lost its in-memory text buffer.
        // Never silently resume a previous knowledge session and lock the wrong foreground APP.
        if (settings.getLong("id", 0L) != 0L && settings.getString("mode", "") == "fragment") {
            settings.edit().putLong("id", 0L).remove("mode").remove("target_pkg")
                .putBoolean("paused", false)
                .putString("status", "服务重新连接，采集已结束；请在目标 APP 的悬浮蜘蛛中重新开始")
                .commit()
        }
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    private fun updateStandby() {
        if (sessionId != 0L || settings.getLong("id", 0L) != 0L) return
        if (!shortcut.getBoolean("enabled", false)) {
            if (sessionId == 0L) { spider?.close(); spider=null }
            return
        }
        if (spider == null) {
            val overlay = makeOverlay()
            if (overlay.show()) { spider = overlay; overlay.setIdle() }
        } else spider?.setIdle()
    }

    private fun makeOverlay(): SpiderOverlay = SpiderOverlay(this,
        onStop = { requestStop() }, onPause = { togglePause() },
        onRequest = { action ->
            if (action == "start_continuous") {
                if (sessionId == 0L && settings.getLong("id", 0L) == 0L) {
                    if (CaptureService.running) {
                        Toast.makeText(this, "请先结束旧屏幕 OCR 采集", Toast.LENGTH_LONG).show()
                    } else {
                        // Resolve the underlying APP only NOW, when the user taps the bubble
                        // while reading it. Nothing was read during standby.
                        val target = foregroundPageRootForStart()?.packageName?.toString().orEmpty()
                        if (!isKnowledgeTargetAllowed(target)) {
                            lastReason = "未找到可采集的阅读 APP，请先打开知乎等正文页面再点击开始"
                            spider?.setIdle(lastReason)
                            Toast.makeText(this, lastReason, Toast.LENGTH_LONG).show()
                        } else {
                            val now = System.currentTimeMillis()
                            settings.edit().putLong("id", now).putString("mode", "fragment")
                                .putString("target_pkg", target).putLong("expiry", 0L)
                                .putBoolean("paused", false).putInt("lines", 0)
                                .putString("status", "锁定 $target · 持续采集中，直到手动停止").commit()
                            updateSession()
                            ui.postDelayed({
                                if (sessionId != 0L && mode == "fragment" && captureTargetPackage == target)
                                    scanActiveApp()
                            }, 650L)
                        }
                    }
                } else if (sessionId != 0L && mode == "fragment" && paused) togglePause()
            } else if (action == "scan_now") {
                if (sessionId != 0L && mode == "fragment" && !paused) {
                    lastScanAt=0L; scanActiveApp()
                } else Toast.makeText(this, "请先启动知识采集会话", Toast.LENGTH_SHORT).show()
            } else {
                val target = Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(MainActivity.EXTRA_SPIDER_SHORTCUT, action)
                runCatching { startActivity(target) }
                    .onFailure { Toast.makeText(this, "请打开欧南 APP 选择采集模式", Toast.LENGTH_LONG).show() }
            }
        })

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (settings.getString("mode", "") == "wechat_ocr" && settings.getLong("id", 0L) != 0L) {
            val src = event.packageName?.toString().orEmpty()
            val scope = when (src) {
                packageName -> "欧南应用"
                "com.android.systemui" -> "系统界面"
                "com.tencent.mm" -> "微信"
                "" -> "未知"
                else -> "其他应用"
            }
            if (scope != lastDiagnosticScope) {
                lastDiagnosticScope = scope
                OcrLifecycleLog.note(this, "无障碍事件：$scope")
            }
        }
        if (sessionId != 0L) {
            val from = event.packageName?.toString().orEmpty()
            if (from.isNotBlank() && from != packageName && from != "com.android.systemui") {
                getSharedPreferences("screen_guard", Context.MODE_PRIVATE).edit()
                    .putString("last_package", from).putLong("last_package_at", System.currentTimeMillis()).apply()
            }
        }
        updateSession()
        if (sessionId != 0L && !paused && (mode == "wechat" || mode == "fragment") &&
            System.currentTimeMillis() - lastScanAt > 900L) scanActiveApp(event)
    }

    private fun updateSession() {
        val id = settings.getLong("id", 0L)
        if (id == 0L) {
            if (sessionId != 0L) finishSession("手动停止")
            return
        }
        if (id != sessionId) {
            if (sessionId != 0L) finishSession("切换会话")
            sessionId = id
            mode = settings.getString("mode", "fragment") ?: "fragment"
            chat = settings.getString("chat", "")?.trim().orEmpty()
            expiry = settings.getLong("expiry", 0L)
            seen.clear(); document.clear(); chatStitcher.reset(); lastSeenPackage = ""
            captureTargetPackage = if (mode == "fragment") settings.getString("target_pkg", "").orEmpty() else ""
            bufferFull=false; bufferChars=0
            lastScanAt = 0L; lastOcrTrace = 0L
            lastReason = if (mode == "fragment") "已锁定 $captureTargetPackage · 翻页和评论自动追加，点停止才保存" else "已启动，请打开目标 APP 并手动浏览"
            val overlay = spider ?: makeOverlay()
            if (!overlay.show()) {
                lastReason = "系统未允许显示可见悬浮窗，已拒绝启动采集"
                settings.edit().putLong("id", 0L).putString("status", lastReason).apply()
                sessionId = 0L
                return
            }
            spider = overlay
            updateBubble()
        }
        paused = settings.getBoolean("paused", false)
        if (expiry > 0L && System.currentTimeMillis() > expiry) {
            finishSession("限时会话到期")
        } else {
            updateBubble()
        }
    }

    /** Prefer an actual application window. Our own TYPE_ACCESSIBILITY_OVERLAY may become
     * rootInActiveWindow after a user taps the spider bubble. Never read overlay text as chat.
     * An event source is used only if Android says it came from the target package.
     */
    private fun selectPageRoot(event: AccessibilityEvent?): AccessibilityNodeInfo? {
        val desired = when {
            mode == "wechat" -> "com.tencent.mm"
            mode == "fragment" && captureTargetPackage.isNotBlank() -> captureTargetPackage
            else -> null
        }
        fun allowed(node: AccessibilityNodeInfo?): Boolean {
            val pkg = node?.packageName?.toString().orEmpty()
            return pkg.isNotBlank() && pkg != packageName && pkg != "com.android.systemui" &&
                (desired == null || pkg == desired)
        }
        val appWindows = runCatching {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { window -> window.root?.let { root -> window to root } }
                .filter { (_, root) -> allowed(root) }
        }.getOrDefault(emptyList())
        val candidate = appWindows.firstOrNull { (window, _) -> window.isActive || window.isFocused }
            ?: appWindows.firstOrNull()
        if (candidate != null) return candidate.second
        val active = rootInActiveWindow
        if (allowed(active)) return active
        val from = event?.packageName?.toString().orEmpty()
        if (from.isBlank() || from == packageName || from == "com.android.systemui" ||
            (desired != null && from != desired)) return null
        var node = event?.source ?: return null
        // Climb to its accessible root, rather than reading only one changed message bubble.
        for (depth in 0..30) {
            val parent = node.parent ?: break
            if (parent.packageName?.toString() != from) break
            node = parent
        }
        return node.takeIf { allowed(it) }
    }

    /** Standby does not traverse nodes or read any text. Only an explicit tap on the
     * floating Start action resolves the currently focused application window.
     * Never fall back to an old/background app window when Ounan itself is foreground.
     */
    private fun foregroundPageRootForStart(): AccessibilityNodeInfo? {
        val current = runCatching {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
                .sortedByDescending { it.layer }
                .mapNotNull { it.root }
                .firstOrNull { isKnowledgeTargetAllowed(it.packageName?.toString().orEmpty()) }
        }.getOrNull()
        if (current != null) return current
        return rootInActiveWindow?.takeIf {
            isKnowledgeTargetAllowed(it.packageName?.toString().orEmpty())
        }
    }

    private fun isKnowledgeTargetAllowed(pkg: String): Boolean {
        if (pkg.isBlank() || pkg == packageName || pkg == "com.android.systemui" ||
            pkg == "com.tencent.mm") return false
        if (sensitivePackages.any { pkg.contains(it, true) }) return false
        // The home screen is not a reading APP; do not begin harvesting launcher text.
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePkg = runCatching { packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName }
            .getOrNull()
        if (homePkg == pkg) return false
        return true
    }

    private fun scanActiveApp(event: AccessibilityEvent? = null) {
        if (sessionId == 0L || paused) return
        val now = System.currentTimeMillis()
        if (now - lastScanAt < 950L) return
        lastScanAt = now
        if ((getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked) {
            lastReason = "锁屏期间暂停读取"; lastPreview = ""; updateBubble(); return
        }
        if (mode == "fragment" && captureTargetPackage.isBlank()) {
            lastReason = "未从悬浮蜘蛛锁定目标，保持待机"
            updateBubble(); return
        }
        val root = selectPageRoot(event)
        if (root == null) {
            lastReason = if (mode == "wechat")
                "未选中微信可读取窗口；请使用菜单中的「微信 OCR」（需录屏授权）"
                else "未取得目标 APP 页面结构，可试试截图 OCR"
            lastPreview = ""; updateBubble(); return
        }
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg.isBlank() || pkg == packageName || pkg == "com.android.systemui") {
            lastReason = "当前读取的是系统/悬浮窗，目标应用窗口未被选中"
            updateBubble(); return
        }
        if (mode == "wechat" && pkg != "com.tencent.mm") {
            lastReason = "微信模式：请进入微信聊天窗口"; lastPreview = ""; updateBubble(); return
        }
        // Ordinary knowledge capture should never accidentally collect messages.
        if (mode == "fragment" && (pkg == "com.tencent.mm" || sensitivePackages.any { pkg.contains(it, true) })) {
            lastReason = "此 APP 已排除；私人聊天请用微信专用模式"; lastPreview = ""; updateBubble(); return
        }
        // A knowledge session MUST have a target locked by the explicit floating-bubble click.
        // Never choose an arbitrary foreground APP after Ounan has been opened.
        if (mode == "fragment" && (captureTargetPackage.isBlank() || captureTargetPackage != pkg)) {
            lastReason = "目标 APP 未匹配（$captureTargetPackage）；等待返回阅读页面，不采集其他应用"
            updateBubble(); return
        }
        if (mode == "fragment" && bufferFull) {
            lastReason = "本次缓存已满，请点击保存并停止；不会静默截断原文"
            updateBubble(); return
        }
        val nodes = ArrayList<Pair<String, Rect>>()
        val total = intArrayOf(0)
        walk(root, nodes, 0, total)
        if (nodes.isEmpty()) {
            lastReason = if (mode == "fragment") "当前页无可读取文字节点；这类页面建议复制或截图" else "微信窗口可访问节点 ${total[0]}，但文字为 0；试用微信 OCR 蜘蛛"
            lastPreview = ""; updateBubble(); return
        }
        if (mode == "wechat") {
            // Chat titles may have parenthesized nicknames, emojis or split TextViews.
            val h = resources.displayMetrics.heightPixels
            val header = nodes.filter { it.second.centerY() < h * .24 }
                .sortedWith(compareBy({ it.second.top }, { it.second.left }))
                .joinToString("") { it.first }
            if (!OcrTextRules.titleMatches(chat, header)) {
                lastReason = "顶部聊天标题不匹配“${chat.take(12)}”；请核对或改用微信 OCR"
                lastPreview = ""; updateBubble(); return
            }
        }
        val source = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg).take(80)
        var added = 0
        var lastAccepted = ""
        val newly = ArrayList<Rect>()
        if (pkg != lastSeenPackage) {
            document.add("\n## 来源：$source ($pkg) · ${clock(now)}\n")
            lastSeenPackage = pkg
        }
        val orderedNodes = nodes.sortedWith(compareBy({ it.second.top }, { it.second.left }))
        val viewport = ArrayList<Pair<String, Rect>>()
        for ((t, rect) in orderedNodes) {
            val safe = t.trim().replace(Regex("\\s+"), " ")
            if (safe.isBlank() || safe.length > 1000 || privateWords.any { safe.contains(it, true) }) continue
            if (mode == "wechat") {
                val h=resources.displayMetrics.heightPixels
                if (rect.top < h*.17 || rect.bottom > h*.94) continue
                if (OcrTextRules.titleKey(safe) == OcrTextRules.titleKey(chat)) continue
                // Visual side is an unverified hint; preserve text without claiming a sender.
                val w=resources.displayMetrics.widthPixels
                val side=if (rect.centerX() < w*.45) "左侧" else if (rect.centerX()>w*.55) "右侧" else "中间"
                viewport.add("[$side·待核对] $safe" to rect)
            } else {
                // Manual knowledge capture uses complete sentences/paragraphs as evidence.
                // Short menu/button labels have near-zero weight and are not archived alone.
                if (FragmentQuality.score(safe) < 5) continue
                val key = "$pkg|$safe"
                if (seen.add(key)) {
                    if (bufferChars + safe.length > 180_000) {
                        bufferFull = true
                        lastReason = "累计文字已到缓存上限，请手动停止保存"
                        break
                    }
                    document.add(safe); bufferChars+=safe.length; newly.add(rect)
                    lastAccepted=safe.take(26); added++
                }
            }
        }
        if (mode == "wechat" && viewport.isNotEmpty()) {
            val delta = chatStitcher.accept(viewport.map { it.first })
            if (delta.gap && document.isNotEmpty())
                document.add("【⚠ 连续性待核对：本屏与上一屏没有匹配的重叠行】")
            document.addAll(delta.append)
            added = delta.append.size
            newly.addAll(viewport.takeLast(added).map { it.second })
            lastAccepted = delta.append.lastOrNull()?.take(26).orEmpty()
        }
        if (added > 0) {
            lastPreview = lastAccepted
            lastReason = "手动采集新增 $added 段 · 累计 ${if (mode == "wechat") chatStitcher.totalLines else seen.size} 段"
            spider?.trace(newly.take(12))
        } else {
            lastReason = if (bufferFull) "缓存已满，请点击停止保存" else "正在持续监听页面变化 · 本次没有新增合格段落"
        }
        updateBubble()
    }

    /** Walk descendants even if a parent reports invisible: some WeChat builds
     * expose text on visible children under a non-visible container node. */
    private fun walk(node: AccessibilityNodeInfo, out: MutableList<Pair<String, Rect>>, depth: Int, total: IntArray) {
        if (depth > 32 || out.size > 300 || total[0] > 1500 || node.isPassword || node.isEditable) return
        total[0]++
        if (node.isVisibleToUser) {
            (node.text?.toString() ?: node.contentDescription?.toString())?.trim()?.let { line ->
                if (line.length in 1..1000) {
                    val r = Rect(); node.getBoundsInScreen(r)
                    if (!r.isEmpty) out.add(line to r)
                }
            }
        }
        for (i in 0 until minOf(node.childCount, 110)) {
            node.getChild(i)?.let { walk(it, out, depth + 1, total) }
        }
    }

    private fun updateBubble() {
        val sec = if (mode == "fragment" && sessionId != 0L) 0L
                  else ((expiry - System.currentTimeMillis()) / 1000).coerceAtLeast(0L)
        if (mode == "wechat_ocr") {
            val newId = settings.getLong("trace_id", 0L)
            if (!paused && newId > 0L && newId != lastOcrTrace) {
                lastOcrTrace = newId
                val rectangles = settings.getString("trace_rects", "").orEmpty()
                    .split(';').mapNotNull { entry ->
                        val nums = entry.split(',').mapNotNull { it.toIntOrNull() }
                        if (nums.size == 4) Rect(nums[0], nums[1], nums[2], nums[3]) else null
                    }
                spider?.trace(rectangles)
            }
            spider?.update(true, paused, sec, settings.getInt("lines", 0),
                settings.getString("status", "正在启动本地 OCR") ?: "正在启动本地 OCR")
            return
        }
        val count=if(mode == "wechat") chatStitcher.totalLines else seen.size
        spider?.update(mode == "wechat", paused, sec, count, lastReason,
            continuous = mode == "fragment" && sessionId != 0L)
        settings.edit().putInt("lines", count).putString("status", lastReason.take(130)).apply()
    }

    private fun requestStop() {
        if (mode == "wechat_ocr") {
            val id = settings.getLong("id", 0L)
            settings.edit().putBoolean("paused", true).apply()
            val stopped = runCatching { stopService(Intent(this, WeChatOcrService::class.java)) }
                .getOrDefault(false)
            if (!stopped) {
                settings.edit().putLong("id", 0L).remove("mode").remove("chat")
                    .remove("trace_rects").remove("trace_id")
                    .putBoolean("paused", false)
                    .putString("status", "录屏服务未运行；已清除旧状态").commit()
            }
            ui.postDelayed({
                if (id != 0L && settings.getLong("id", 0L) == id) {
                    settings.edit().putLong("id", 0L).remove("mode").remove("chat")
                        .remove("trace_rects").remove("trace_id")
                        .putBoolean("paused", false)
                        .putString("status", "已停止微信 OCR，并清理残留状态").commit()
                }
                updateSession()
            }, 1000L)
            return
        }
        settings.edit().putLong("id", 0L).apply()
        finishSession("用户点击蜘蛛停止")
    }
    private fun togglePause() {
        paused = !paused
        settings.edit().putBoolean("paused", paused).apply()
        lastReason = if (paused) "已暂停（保留已采集内容），点击继续" else "持续采集中 · 翻页和评论自动追加"
        updateBubble()
    }
    private fun finishSession(reason: String) {
        if (sessionId == 0L) return
        if (mode == "wechat_ocr") {
            // OCR is an independently user-authorized foreground service.
            // Accessibility rebind/teardown must NEVER silently end MediaProjection.
            OcrLifecycleLog.note(this, "无障碍采集会话已结束；OCR服务保持独立；原因=${reason.take(30)}")
            spider?.close(); spider = null; sessionId = 0L
            seen.clear(); chatStitcher.reset(); document.clear(); mode = ""; chat = ""; lastPreview = ""
            updateStandby()
            return
        }
        val oldMode = mode
        val oldChat = chat
        val count = if (mode == "wechat") chatStitcher.totalLines else seen.size
        val content = buildString {
            append("欧南采集蜘蛛 · 屏幕可见文字记录\n")
            append("保存于：${clock(System.currentTimeMillis())}\n")
            append("注意：此文档只包含采集会话中可访问的屏幕文字，不保证内容完整、消息归属或原始时间。\n")
            append("会话结束原因：$reason\n\n")
            append(document.joinToString("\n"))
        }
        // SQLite note insertion limits each note to 30k characters. Split on paragraph
        // boundaries rather than silently truncating large scroll sessions.
        val savedDocs = if (count == 0) 0 else runCatching {
            if (oldMode == "wechat") {
                if (chats.insert(oldChat, content, "采集蜘蛛 · 微信可见文字")) 1 else 0
            } else {
                val pieces = ArrayList<String>()
                var chunk = StringBuilder()
                for (line in document) {
                    val candidate = line + "\n"
                    if (chunk.length + candidate.length > 26_000 && chunk.isNotEmpty()) {
                        pieces.add(chunk.toString()); chunk = StringBuilder()
                    }
                    if (candidate.length > 26_000) {
                        candidate.chunked(25_000).forEach { part ->
                            if (chunk.isNotEmpty()) { pieces.add(chunk.toString()); chunk=StringBuilder() }
                            pieces.add(part)
                        }
                    } else chunk.append(candidate)
                }
                if (chunk.isNotEmpty()) pieces.add(chunk.toString())
                var nSaved = 0
                for ((index, piece) in pieces.withIndex()) {
                    val doc = "欧南持续采集 · 共 ${pieces.size} 部分 · 第 ${index+1} 部分\n采集于：${clock(System.currentTimeMillis())}\n\n"+piece
                    if (notes.insert(doc,"持续蜘蛛 · 本机离线归档",minChars=5)) nSaved++
                }
                nSaved
            }
        }.getOrDefault(0)
        val success = savedDocs > 0
        settings.edit().putLong("id", 0L).remove("chat").remove("mode").remove("target_pkg")
            .putBoolean("paused", false).putInt("lines", count)
            .putString("status", if (success) "已生成 $savedDocs 份本地文档（$count 段）；请到知识库查看" else "没有可保存文字；请尝试手动复制粘贴").apply()
        // Keep the visible control available for the next deliberate reading session.
        spider?.setIdle(if (success) "已保存 $savedDocs 份文档；待机，不采集" else "待机 · 没有符合条件的文字")
        sessionId = 0L; seen.clear(); chatStitcher.reset(); document.clear(); bufferChars=0; bufferFull=false; captureTargetPackage=""; mode = ""; chat = ""; lastPreview = ""
        updateStandby()
        Toast.makeText(this, if (success) "蜘蛛已保存 $savedDocs 份文档，共 $count 段" else "本次没有生成文档", Toast.LENGTH_LONG).show()
    }
    private fun clock(t: Long) = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(t))
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        OcrLifecycleLog.note(this, "无障碍服务 onDestroy")
        finishSession("辅助服务停止")
        ui.removeCallbacks(ticker)
        spider?.close()
        runCatching { notes.close() }
        runCatching { chats.close() }
        super.onDestroy()
    }
    companion object {
        private val privateWords = listOf("验证码", "密码", "助记词", "恢复短语", "私钥", "银行卡", "身份验证", "登录验证", "OTP", "Password", "security code")
        private val sensitivePackages = listOf("bank", "wallet", "authenticator", "password", "pay", "alipay", "settings", "finance", "credential")
    }
}
