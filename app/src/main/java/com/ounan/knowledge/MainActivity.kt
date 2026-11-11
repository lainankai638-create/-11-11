package com.ounan.knowledge

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.provider.OpenableColumns
import android.widget.CheckBox
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.media.projection.MediaProjectionConfig
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONObject
import org.json.JSONArray

class MainActivity : Activity() {
    private lateinit var db: KnowledgeDb
    private lateinit var wechatDb: WeChatArchive
    private lateinit var chatBatchDb: ChatBatchArchive
    private lateinit var changeJournal: AdminChangeJournal
    private lateinit var admin: LocalKnowledgeAdmin
    private var agentSession = ""
    private var agentEndpoint = ""
    private var agentToken = "" // restored from locally encrypted Android Keystore storage
    private var agentBusy = false
    private var agentNetworkConsented = false
    private var agentStatus: TextView? = null
    private var agentAnswer: TextView? = null
    private var agentAudit: TextView? = null
    private var agentToolCount = 0
    private var agentReadCount = 0
    private var agentDbRequested = false
    private val agentToolEvents = mutableListOf<String>()
    private lateinit var agentTools: GptToolBridge
    private lateinit var agentChatDb: GptConversationDb
    private lateinit var agentSecureConnection: GptSecureConnection
    private var agentMessagesPanel: LinearLayout? = null
    private var agentMessagesScroll: ScrollView? = null
    private var adminSearchQuery = ""
    private var adminSearchSource = "all"
    private var privateExportPassword: CharArray? = null
    private var fragmentExportPassword: CharArray? = null
    private var pendingChatName = ""
    private var pendingTxtDocument: String? = null
    private var pendingAgentDraft: String? = null
    private var pendingWechatOcrChat = ""
    private lateinit var frame: FrameLayout
    private var page = "home"
    private val mainHandler = Handler(Looper.getMainLooper())
    private val blue = Color.rgb(42, 99, 210)
    private val ink = Color.rgb(28, 40, 60)
    private val gray = Color.rgb(94, 108, 128)
    private val bg = Color.rgb(243, 246, 252)
    private val white = Color.WHITE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = KnowledgeDb(this)
        wechatDb = WeChatArchive(this)
        chatBatchDb = ChatBatchArchive(this)
        changeJournal = AdminChangeJournal(this)
        admin = LocalKnowledgeAdmin(db, wechatDb, chatBatchDb, changeJournal)
        agentTools = GptToolBridge(admin)
        agentChatDb = GptConversationDb(this)
        agentSecureConnection = GptSecureConnection(this)
        agentSecureConnection.load()?.let { (url,secret) -> agentEndpoint=url; agentToken=secret }
        clearOrphanedOcrSession()
        createShell()
        showPage("home")
        consumeSharedIntent(intent)
        consumeSpiderShortcut(intent)
    }

    /** The persisted UI flag is not proof that a screen-capture service survived a reboot. */
    private fun clearOrphanedOcrSession() {
        val sp = getSharedPreferences("spider_session", MODE_PRIVATE)
        val id = sp.getLong("id", 0L)
        if (sp.getString("mode", "") == "wechat_ocr" && id != 0L &&
            WeChatOcrService.activeSessionId != id && System.currentTimeMillis() - id > 15_000L) {
            sp.edit().putLong("id", 0L).remove("mode").remove("chat")
                .remove("trace_rects").remove("trace_id")
                .putBoolean("paused", false)
                .putString("status", "系统重启或录屏服务已退出；旧的采集状态已清理")
                .commit()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSharedIntent(intent)
        consumeSpiderShortcut(intent)
    }

    private fun consumeSpiderShortcut(intent: Intent?) {
        val requested=intent?.getStringExtra(EXTRA_SPIDER_SHORTCUT) ?: return
        intent.removeExtra(EXTRA_SPIDER_SHORTCUT)
        mainHandler.post {
            when(requested) {
                "wechat" -> { showPage("wechat"); confirmSpider("wechat") }
                "wechat_ocr" -> { showPage("wechat"); confirmWechatOcrSpider() }
                "library" -> showPage("library")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (getSharedPreferences("spider_session", MODE_PRIVATE).getString("mode", "") == "wechat_ocr")
            OcrLifecycleLog.note(this, "Activity onResume：欧南界面进入前台")
        if (::frame.isInitialized && (page == "home" || page == "wechat")) showPage(page)
    }

    override fun onPause() {
        if (getSharedPreferences("spider_session", MODE_PRIVATE).getString("mode", "") == "wechat_ocr")
            OcrLifecycleLog.note(this, "Activity onPause：欧南界面离开前台")
        // Screenshot blocking is intentionally disabled in this personal test build.
        // Avoid displaying secrets while sharing the screen or sending screenshots.
        visible = false
        super.onPause()
    }

    private fun createShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(15))
            setBackgroundColor(white)
        }
        header.addView(text("欧南 · 个人知识中枢", 22f, ink, true))
        header.addView(text("本地知识 · 主动采集 · GPT 连续对话", 12f, gray))
        root.addView(header)

        frame = FrameLayout(this)
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(white)
        }
        listOf("采集" to "home", "知识库" to "library", "微信" to "wechat", "管理" to "admin", "研究" to "research", "设置" to "settings").forEach { (label, target) ->
            val item = TextView(this).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(blue)
                setPadding(dp(6), dp(15), dp(6), dp(15))
                setOnClickListener { showPage(target) }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(nav)
        setContentView(root)
    }

    private fun showPage(next: String) {
        page = next
        val scroll = ScrollView(this).apply { setFillViewport(true) }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(22))
        }
        scroll.addView(content)
        frame.removeAllViews()
        frame.addView(scroll)
        when (next) {
            "home" -> showHome(content)
            "library" -> showLibrary(content)
            "wechat" -> showWeChat(content)
            "ai" -> showAi(content)
            "admin" -> showAdmin(content)
            "agent" -> showAgent(content)
            "research" -> showResearch(content)
            "settings" -> showSettings(content)
        }
    }

    private fun showHome(container: LinearLayout) {
        addTitle(container, "信息入口", "看到有价值的内容，就把它存进自己的知识库。")
        val stat = card()
        stat.addView(text("知识库已有 ${db.count()} 条记录", 22f, ink, true))
        stat.addView(text("旧版 OCR：" + if (CaptureService.running) "正在识别屏幕" else "未运行", 13f, gray))
        stat.addView(text("知识碎片和微信档案保存在手机本地；仅你主动调用 GPT 时才会联网。", 12f, gray))
        container.addView(stat)

        val input = card()
        input.addView(text("主动收藏", 17f, ink, true))
        input.addView(text("抖音 AI 解析：先在抖音复制全文，再打开欧南点击「粘贴采集」。不自动监听剪贴板。", 13f, gray))
        addButton(input, "📋 粘贴采集 · 抖音 AI 解析 / 知识文章") { pasteCapture() }
        addButton(input, "＋ 手动保存文字") { manualEntry() }
        addButton(input, "▧ 单张知识截图识别") { importImage() }
        addButton(input, "▧ 多张聊天截图批量整理（过滤气泡外文字）") { startWeChatImageImport() }
        container.addView(input)

        val spiderPanel = card()
        spiderPanel.addView(text("🕷 蜘蛛 · 先待机，后开始采集", 18f, ink, true))
        spiderPanel.addView(text("第一步：在这里开启悬浮蜘蛛，它只显示按钮，不读取任何页面文字。第二步：切换到知乎等文章页面，点悬浮蜘蛛 →「开始采集此 APP」。之后滚动、翻页、打开评论持续追加，只有你手动「保存并停止」才结束。", 13f, gray))
        val sp = getSharedPreferences("spider_session", MODE_PRIVATE)
        val shortcutPrefs = getSharedPreferences("spider_shortcut", MODE_PRIVATE)
        val standby = shortcutPrefs.getBoolean("enabled", false)
        val activeSpider = sp.getLong("id", 0L) > 0L
        spiderPanel.addView(text("状态：" + if (activeSpider) "正在采集 · ${sp.getInt("lines", 0)} 段" else if (standby) "待机中 · 不读取、不保存" else "未开启悬浮按钮", 12f, if (activeSpider) blue else gray))
        if (activeSpider) addButton(spiderPanel, "■ 保存文档并停止蜘蛛") { stopSpider() }
        else if (!standby) addButton(spiderPanel, "🕷 开启悬浮蜘蛛（仅待机，不采集）") { enableSpiderStandby() }
        else addButton(spiderPanel, "🕷 悬浮蜘蛛已待机 · 请前往文章，点击蜘蛛开始") { toast("现在不采集。前往知乎等文章页，点击屏幕边缘的蜘蛛启动。") }
        spiderPanel.addView(text("开始采集时才锁定当前阅读 APP；离开它不收集其他 APP。结束后重新回到悬浮待机。无障碍不可读取的抖音 AI 解析，仍用粘贴采集。", 12f, gray))
        container.addView(spiderPanel)

        val fallback = card()
        fallback.addView(text("备选 · 图片 OCR 采集", 17f, ink, true))
        fallback.addView(text("对没有可访问文字结构的应用，可手动导入截图；也可试用旧版限时屏幕 OCR。该模式没有悬浮蜘蛛可视轨迹。", 12f, gray))
        if (CaptureService.running) addButton(fallback, "■ 停止旧版屏幕 OCR") { stopCapture() }
        else addButton(fallback, "开启旧版限时屏幕 OCR（备选）") { confirmCapture() }
        container.addView(fallback)

        container.addView(text("最近收藏", 18f, ink, true).apply { setPadding(dp(4), dp(18), 0, dp(8)) })
        val latest = db.list(limit = 5)
        if (latest.isEmpty()) container.addView(text("还没有内容。可以先复制一段文章，点“手动保存文字”试试看。", 14f, gray))
        latest.forEach { addNoteCard(container, it) }
    }

    private fun showLibrary(container: LinearLayout) {
        addTitle(container, "私人知识库", "在手机本地检索原文、标签和来源。")
        val search = EditText(this).apply {
            hint = "搜索关键词，例如：机器人、俄罗斯、经济"
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setBackgroundColor(white)
        }
        container.addView(search, LinearLayout.LayoutParams(-1, dp(54)))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(results)
        fun render(q: String) {
            results.removeAllViews()
            val notes = db.list(q, limit = 300)
            results.addView(text("${notes.size} 条结果（最多显示 300 条）", 12f, gray).apply { setPadding(dp(4), dp(15), 0, dp(8)) })
            notes.forEach { addNoteCard(results, it) }
            if (notes.isEmpty()) results.addView(text("没有匹配的内容", 14f, gray))
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        render("")
    }

    /** WeChat is an isolated encrypted archive. It is not exposed to any network. */
    private fun showWeChat(container: LinearLayout) {
        addTitle(container, "微信聊天留档", "只存经你授权采集、屏幕上可访问的文字；不破解微信数据库。")
        val session = card()
        val sp = getSharedPreferences("spider_session", MODE_PRIVATE)
        val isRunning = sp.getLong("id", 0L) > 0L
        val currentMode = sp.getString("mode", "")
        session.addView(text(if (isRunning) "● 蜘蛛正在执行：${when (currentMode) { "wechat" -> "微信留档"; "wechat_ocr" -> "微信 OCR"; else -> "知识碎片" }} · ${sp.getInt("lines", 0)} 段" else "○ 微信采集蜘蛛未启动", 15f, if (isRunning) blue else gray, true))
        session.addView(text("微信需要你手动输入准确聊天标题。进入对应聊天并自行滚动时，蜘蛛才会采集可访问的文字，显示扫描与未保存的原因；结束后生成一份加密的聊天文档。不会点击消息、翻页或访问微信内部数据库。", 13f, gray))
        session.addView(text("无障碍读取到 0 段时，请改用「微信 OCR 蜘蛛」：由你单独授权屏幕录制，在手机离线识别并去重，聊天标题匹配后才会保存。普通知识采集默认跳过微信。", 12f, gray))
        if (isRunning) addButton(session, "■ 保存并停止蜘蛛") { stopSpider() }
        else {
            addButton(session, "🕷 微信节点采集（受限时改用 OCR）") { confirmSpider("wechat") }
            addButton(session, "▧ 微信 OCR 蜘蛛（推荐 · 需要每次录屏授权）") { confirmWechatOcrSpider() }
        }
        addButton(session, "前往无障碍设置（启用欧南 · 可见采集蜘蛛）") {
            startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton(session, "🔎 查看 OCR 启动诊断（不含聊天文字）") {
            val d = getSharedPreferences("ocr_startup_diagnostics", MODE_PRIVATE)
            val phase = d.getString("phase", "尚未启动过 OCR").orEmpty()
            val reason = d.getString("reason", "").orEmpty()
            val time = d.getLong("updated_at", 0L)
            val formattedTime = if (time > 0L) SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(time)) else "未知"
            val detail = "阶段：$phase\n原因：${reason.ifBlank { "未记录额外信息" }}\n时间：$formattedTime\n\n最近服务事件（最晚记录在下方）：\n" +
                OcrLifecycleLog.read(this) + "\n\n仅记录状态、进程和时间，不含聊天正文、聊天标题或截图。"
            AlertDialog.Builder(this).setTitle("微信 OCR 跨应用诊断")
                .setMessage(detail)
                .setPositiveButton("复制诊断") { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("欧南 OCR 诊断", detail))
                    toast("已复制服务诊断；请粘贴给欧南分析")
                }
                .setNegativeButton("关闭", null).show()
        }
        // Screenshot-first workflow: manual imports are stable on Xiaomi phones.

        val manual = card()
        manual.addView(text("手动补充 / 截图识别", 17f, ink, true))
        manual.addView(text("一次选多张截图，逐行识别左右聊天，自动合并上下文并过滤非聊天界面文字；核对后保存应用内明文稿。", 12f, gray))
        addButton(manual, "＋ 手动录入聊天片段") { manualWechatEntry() }
        addButton(manual, "▧ 批量选择聊天截图 · 对齐去重 · 预览保存") { startWeChatImageImport() }
        container.addView(manual)

        val batches = card()
        batches.addView(text("批量截图整理档案 · 本地明文", 17f, ink, true))
        batches.addView(text("此区域不加密，仅保存在欧南的应用专属数据库；旧版加密备份不包含这里的内容。需要换机时请在档案详情中导出 TXT。", 12f, gray))
        val latestBatches = chatBatchDb.list(limit = 40)
        if (latestBatches.isEmpty()) batches.addView(text("暂无批量截图档案", 13f, gray))
        latestBatches.forEach { entry ->
            val date = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(entry.createdAt))
            val line = text("${entry.title} · ${entry.imageCount} 张 · $date", 14f, blue)
            line.setOnClickListener { showPlainChatEntry(entry) }
            batches.addView(line)
        }
        container.addView(batches)
        session.addView(text("实验功能 · 跨 APP 自动采集受部分系统限制，建议优先使用上方批量截图。", 12f, gray))
        container.addView(session)

        val backup = card()
        backup.addView(text("加密归档 · 换机可恢复", 17f, ink, true))
        backup.addView(text("微信档案和知识碎片分开加密，不向第三方模型自动提供。加密备份需要你自己记住至少 12 位备份口令；口令丢失无法恢复。强烈建议换机前导出并在另一台设备验证导入。", 12f, gray))
        addButton(backup, "导出加密微信留档备份") { askWeChatExport() }
        addButton(backup, "导入以前的加密微信留档") { startWeChatImport() }
        container.addView(backup)

        val search = EditText(this).apply { hint = "搜索联系人 / 关键词 / 标签"; setSingleLine(true) }
        container.addView(search)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(results)
        fun render(q: String) {
            results.removeAllViews()
            val entries = wechatDb.list(q, 2000)
            results.addView(text("显示 ${entries.size} 个页面片段（最近最多 2,000 条）", 12f, gray))
            if (entries.isEmpty()) results.addView(text("还没有留档内容", 14f, gray))
            entries.take(200).forEach { item ->
                val c = card()
                c.addView(text(item.conversation, 16f, ink, true))
                c.addView(text(item.body.replace("\n", " ").take(140), 13f, gray))
                val day = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(item.capturedAt))
                c.addView(text("${item.tags} · $day · ${item.method}", 11f, blue))
                c.setOnClickListener { showWeChatEntry(item) }
                results.addView(c)
            }
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render(s?.toString().orEmpty())
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        render("")
    }

    private fun isWeChatReaderEnabled(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val component = ComponentName(this, WeChatReaderService::class.java)
        return enabled.split(':').any {
            it.equals(component.flattenToString(), ignoreCase = true) ||
                it.equals(component.flattenToShortString(), ignoreCase = true)
        }
    }

    private fun enableSpiderStandby() {
        if (!isWeChatReaderEnabled()) {
            AlertDialog.Builder(this).setTitle("需要一次无障碍授权")
                .setMessage("请启用欧南可见采集蜘蛛；待机状态不读取任何正文。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往设置") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .show()
            return
        }
        getSharedPreferences("spider_shortcut", MODE_PRIVATE).edit()
            .putBoolean("enabled", true).apply()
        toast("悬浮蜘蛛将在几秒内出现；前往阅读页面后再点击它开始采集")
        showPage("home")
    }

    private fun confirmSpider(type: String) {
        // Knowledge capture is armed ONLY from the floating overlay over the target APP.
        if (type == "fragment") { enableSpiderStandby(); return }
        if (!isWeChatReaderEnabled()) {
            AlertDialog.Builder(this).setTitle("需要一次无障碍授权")
                .setMessage("请在系统辅助功能设置中开启“欧南 · 可见采集蜘蛛”。新版服务会显示悬浮控制条；没有手动开启限时会话时不读取或存储其他应用内容。升级后如权限未生效，可关闭再开启一次。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往设置") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
            return
        }
        val chatField = if (type == "wechat") EditText(this).apply { hint = "精确填写微信联系人或群聊标题"; setSingleLine(true) } else null
        AlertDialog.Builder(this)
            .setTitle(if (type == "wechat") "启动微信采集蜘蛛" else "启动知识采集蜘蛛")
            .setMessage(if (type == "wechat") "微信模式为 5 分钟限时采集；只保存你授权读取的可访问文字。" else "点击开启后，会持续读取同一个目标 APP 中的可访问文字；翻页和打开评论也会自动追加，直到你主动点击『保存并停止』。停止之前请勿进入隐私或支付页面；不自动滚动，不上传 GPT。")
            .apply { if (chatField != null) setView(chatField) }
            .setNegativeButton("取消", null)
            .setPositiveButton("确认开启") { _, _ ->
                val chatName = chatField?.text?.toString()?.trim().orEmpty()
                if (type == "wechat" && chatName.isBlank()) { toast("微信模式必须填写准确的聊天标题"); return@setPositiveButton }
                if (CaptureService.running) stopCapture()
                val prefs = getSharedPreferences("spider_session", MODE_PRIVATE)
                prefs.edit().putLong("id", System.currentTimeMillis()).putString("mode", type)
                    .putString("chat", chatName.take(120)).putLong("expiry", if (type == "wechat") System.currentTimeMillis() + 5L * 60_000L else 0L)
                    .putBoolean("paused", false).putInt("lines", 0).putString("status", "启动中，请切换到目标 APP")
                    .apply()
                toast("采集蜘蛛已申请启动，打开目标 APP，观察悬浮窗状态")
                showPage(if (type == "wechat") "wechat" else "home")
            }.show()
    }

    private fun confirmWechatOcrSpider() {
        if (!isWeChatReaderEnabled()) {
            AlertDialog.Builder(this).setTitle("需要可见蜘蛛服务")
                .setMessage("先去系统无障碍设置启用「欧南 · 可见采集蜘蛛」，才能显示可暂停和停止的悬浮窗。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往设置") { _, _ -> startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.show()
            return
        }
        if (getSharedPreferences("spider_session", MODE_PRIVATE).getLong("id", 0L) != 0L || CaptureService.running) {
            toast("请先停止当前采集会话")
            return
        }
        val title = EditText(this).apply { hint = "输入微信聊天顶部的完整标题"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("微信 OCR 蜘蛛 · 仅本地")
            .setMessage("""此模式每次需要安卓录屏授权。只在你自己打开目标聊天、顶部标题匹配时录入文字。

此功能将向系统申请共享整个屏幕（不是只共享欧南 APP）；如系统仍提供范围选择，请选择整个屏幕。可能捕获屏幕上所有显示内容，请勿访问银行、验证码或密码界面。文字不会联网，5 分钟自动结束。""")
            .setView(title)
            .setNegativeButton("取消", null)
            .setPositiveButton("确认并申请录屏授权") { _, _ ->
                val name = title.text.toString().trim()
                if (name.length < 2) { toast("请填写至少两个字的聊天标题"); return@setPositiveButton }
                pendingWechatOcrChat = name.take(120)
                val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(fullDisplayConsentIntent(mgr), REQ_WECHAT_OCR)
            }.show()
    }

    private fun stopSpider() {
        val sp = getSharedPreferences("spider_session", MODE_PRIVATE)
        if (sp.getString("mode", "") == "wechat_ocr") {
            // Stop the actual service, rather than creating a new service merely
            // to deliver ACTION_STOP after the previous one has already crashed.
            val id = sp.getLong("id", 0L)
            sp.edit().putBoolean("paused", true).apply() // cease OCR immediately
            val stopped = runCatching { stopService(Intent(this, WeChatOcrService::class.java)) }
                .getOrDefault(false)
            if (!stopped) {
                sp.edit().putLong("id", 0L).remove("mode").remove("chat")
                    .remove("trace_rects").remove("trace_id")
                    .putBoolean("paused", false)
                    .putString("status", "录屏服务未运行；已清理残留采集状态").commit()
            }
            frame.postDelayed({
                // Android service onDestroy normally clears this on completion.
                // Recover defensively if the service was already dead or cleanup failed.
                if (sp.getLong("id", 0L) == id && id != 0L) {
                    sp.edit().putLong("id", 0L).remove("mode").remove("chat")
                        .remove("trace_rects").remove("trace_id")
                        .putBoolean("paused", false)
                        .putString("status", "采集已停止；残留状态已清理，请核对归档").commit()
                }
                showPage(page)
            }, 1000L)
            toast("正在停止微信 OCR，请核对本次采集档案")
            return
        }
        sp.edit().putLong("id", 0L).apply()
        toast("已请求结束；蜘蛛将加密保存已读取的文字")
        frame.postDelayed({ showPage(page) }, 2000L)
    }

    private fun showWeChatEntry(item: WeChatArchive.Entry) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val name = EditText(this).apply { setText(item.conversation); setSingleLine(true); hint = "联系人或群聊" }
        val tags = EditText(this).apply { setText(item.tags); hint = "标签" }
        val notes = EditText(this).apply { setText(item.note); hint = "个人备注：消息发言人、时间、事件等可人工补充"; minLines = 2 }
        layout.addView(text("聊天名称", 12f, gray)); layout.addView(name)
        layout.addView(text("标签", 12f, gray)); layout.addView(tags)
        layout.addView(text("原始可见文字（本页只读，可在管理页编辑并保留历史）", 12f, gray))
        layout.addView(text(item.body, 14f, ink))
        addButton(layout, "📖 查看微信文字排版稿（只读）") {
            showFormattedDocument(DocumentFormatter.weChat(item.body), "微信留档 · 自动排版")
        }
        layout.addView(text("人工备注", 12f, gray)); layout.addView(notes)
        layout.addView(text("只代表留档时屏幕上的可访问文字；并未验证发送者或消息原始时间。", 12f, gray))
        val scroller = ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this).setTitle("聊天留档详情")
            .setView(scroller)
            .setNegativeButton("关闭", null)
            .setNeutralButton("删除") { _, _ ->
                AlertDialog.Builder(this).setTitle("确认删除这个聊天片段？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ -> admin.delete(LocalKnowledgeAdmin.WECHAT, item.id); showPage("wechat") }.show()
            }
            .setPositiveButton("保存备注") { _, _ ->
                toast(if (admin.edit(LocalKnowledgeAdmin.WECHAT, item.id, name.text.toString(), item.body, tags.text.toString(), notes.text.toString())) "备注和标签已更新，可从历史恢复" else "更新失败或存在重复记录")
                showPage("wechat")
            }.show()
    }

    private fun manualWechatEntry() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), 0) }
        val name = EditText(this).apply { hint = "联系人 / 群聊名"; setSingleLine(true) }
        val body = EditText(this).apply { hint = "粘贴或输入微信聊天文本"; minLines = 5; maxLines = 12 }
        root.addView(name); root.addView(body)
        AlertDialog.Builder(this).setTitle("手动留档聊天文字")
            .setView(root).setNegativeButton("取消", null)
            .setPositiveButton("加密保存") { _, _ ->
                toast(if (wechatDb.insert(name.text.toString(), body.text.toString(), "人工粘贴")) "已加密保存" else "内容不足、名称为空或重复")
                showPage("wechat")
            }.show()
    }
    private data class ChatImage(val uri: Uri, val name: String)

    private fun startWeChatImageImport() {
        val name = EditText(this).apply { hint = "联系人或群聊名（可自定义）"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("批量整理聊天截图")
            .setMessage("一次选择最多 30 张截图。逐行识别左右气泡，过滤顶部标题和底部输入栏；识别困难时自动展示待核对草稿，保存前可人工修正。\n\n本次新档案为应用内明文存储；旧加密档案不改变。")
            .setView(name).setNegativeButton("取消", null)
            .setPositiveButton("去相册多选") { _, _ ->
                pendingChatName = name.text.toString().trim()
                if (pendingChatName.isEmpty()) { toast("请填写聊天名称"); return@setPositiveButton }
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }, REQ_WECHAT_IMAGE)
            }.show()
    }

    private fun nameOfImage(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty().takeLast(45) }
    }

    private fun receiveChatImages(data: Intent) {
        val name = pendingChatName
        pendingChatName = ""
        if (name.isBlank()) { toast("请重新填写聊天名称"); return }
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clips ->
            for (i in 0 until clips.itemCount) uris.add(clips.getItemAt(i).uri)
        }
        data.data?.let { uris.add(it) }
        val selected = uris.distinct().take(30).map { ChatImage(it, nameOfImage(it)) }.toMutableList()
        if (selected.isEmpty()) { toast("没有选中图片"); return }
        // Screenshot filenames usually contain ordered capture timestamps.
        selected.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        showBatchOrderDialog(name, selected)
    }

    private fun showBatchOrderDialog(chatName: String, images: MutableList<ChatImage>) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        layout.addView(text("已选 ${images.size} 张，按文件名初排。长按无法排序时可用 ↑ ↓ 调整，确保从最早一屏到最新一屏。", 13f, gray))
        val top = EditText(this).apply { setText("8"); setSingleLine(true); inputType = InputType.TYPE_CLASS_NUMBER; hint = "顶部忽略百分比" }
        val bottom = EditText(this).apply { setText("11"); setSingleLine(true); inputType = InputType.TYPE_CLASS_NUMBER; hint = "底部忽略百分比" }
        layout.addView(text("排除页面顶部 %（默认 8）", 12f, gray)); layout.addView(top)
        layout.addView(text("排除页面底部 %（默认 11）", 12f, gray)); layout.addView(bottom)
        val strict = CheckBox(this).apply { text = "优先只保留确认的气泡（推荐；识别为零时自动给出待核对草稿）"; isChecked = true }
        layout.addView(strict)
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(rows)
        fun renderList() {
            rows.removeAllViews()
            for (i in images.indices) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(text("${i + 1}. ${images[i].name.takeLast(26)}", 12f, ink), LinearLayout.LayoutParams(0, -2, 1f))
                val up = Button(this).apply { text = "↑"; textSize = 13f; isAllCaps = false; isEnabled = i != 0 }
                up.setOnClickListener { if (i > 0) { java.util.Collections.swap(images, i, i - 1); renderList() } }
                row.addView(up, LinearLayout.LayoutParams(dp(43), dp(42)))
                val down = Button(this).apply { text = "↓"; textSize = 13f; isAllCaps = false; isEnabled = i < images.lastIndex }
                down.setOnClickListener { if (i < images.lastIndex) { java.util.Collections.swap(images, i, i + 1); renderList() } }
                row.addView(down, LinearLayout.LayoutParams(dp(43), dp(42)))
                rows.addView(row)
            }
        }
        renderList()
        val outer = ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this).setTitle("确认截图顺序及裁剪")
            .setView(outer).setNegativeButton("取消", null)
            .setPositiveButton("开始逐张识别") { _, _ ->
                val t = top.text.toString().toIntOrNull()?.coerceIn(0, 35) ?: 8
                val b = bottom.text.toString().toIntOrNull()?.coerceIn(0, 35) ?: 11
                processChatImageBatch(chatName, images.toList(), t, b, strict.isChecked)
            }.show()
    }

    /**
     * The first BitmapFactory pass reads ONLY width/height. In inJustDecodeBounds mode,
     * decodeStream correctly returns null, even for a valid picture. Never treat that
     * null as a file-read error. The dimensions in Options are the result of that pass.
     */
    private fun loadChatBitmap(uri: Uri): Bitmap {
        var initialError: Exception? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val headerStream = contentResolver.openInputStream(uri)
                ?: error("相册没有提供可读取的图片流")
            headerStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds) // Normal return value: null!
            }
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "未读取到图片尺寸" }
            var sample = 1
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 3000) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val pictureStream = contentResolver.openInputStream(uri)
                ?: error("相册无法再次打开图片")
            pictureStream.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)?.let { return it }
            }
            error("图片像素解码失败")
        } catch (e: Exception) {
            initialError = e
        }
        // Some document providers / formats are handled by Android's ImageDecoder but
        // not by BitmapFactory; use the same user-selected URI, without extra permissions.
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val source = ImageDecoder.createSource(contentResolver, uri)
                return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    var sample = 1
                    while (info.size.width / sample > 1600 || info.size.height / sample > 3000) sample *= 2
                    decoder.setTargetSampleSize(sample)
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
                throw IllegalStateException("BitmapFactory 与 ImageDecoder 均无法读取选中的图片", e)
            }
        }
        throw IllegalStateException("无法读取选中的图片", initialError)
    }

    private fun chatReadFailure(reason: Throwable): String {
        // Never include file paths, provider URIs or screenshot text in diagnostics.
        return when (reason) {
            is SecurityException -> "相册读取权限不足"
            is java.io.FileNotFoundException -> "图片不存在或未下载到本机"
            is OutOfMemoryError -> "图片尺寸过大，内存不足"
            else -> reason.javaClass.simpleName.ifBlank { "未知异常" }
        }
    }

    private fun processChatImageBatch(chatName: String, images: List<ChatImage>, top: Int, bottom: Int, strict: Boolean) {
        val pages = ArrayList<ChatScreenshotStitcher.Page>()
        val errors = mutableListOf<String>()
        var cancelled = false
        val dialog = AlertDialog.Builder(this).setTitle("正在离线识别聊天截图")
            .setMessage("准备处理 ${images.size} 张，请不要退出欧南…")
            .setNegativeButton("取消本次任务") { _, _ -> cancelled = true }.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        fun done() {
            recognizer.close()
            if (dialog.isShowing) dialog.dismiss()
            if (cancelled) { toast("已取消，未保存任何聊天内容"); return }
            val result = ChatScreenshotStitcher.merge(pages)
            if (result.messages == 0) {
                AlertDialog.Builder(this).setTitle("没有识别到聊天气泡")
                    .setMessage("本次总计识别 ${pages.sumOf { it.seenLines }} 行文字，成功读取 ${images.size - errors.count { it.contains("图片读取失败") }} / ${images.size} 张图片。\n${if (pages.sumOf { it.seenLines } == 0) "请先检查下面图片读取错误，而不是调整气泡比例。" else "若文字被过滤，可把顶部/底部比例调为 0 再试。"}\n${errors.joinToString("；")}")
                    .setPositiveButton("知道了", null).show()
                return
            }
            reviewChatBatch(chatName, images.size, result, errors)
        }
        fun process(index: Int) {
            if (cancelled || index >= images.size) { done(); return }
            dialog.setMessage("正在识别第 ${index + 1}/${images.size} 张…\n${images[index].name.take(42)}")
            Thread {
                val bitmap = runCatching { loadChatBitmap(images[index].uri) }
                mainHandler.post {
                    if (cancelled) { bitmap.getOrNull()?.recycle(); done(); return@post }
                    if (bitmap.isFailure) {
                        errors.add("第 ${index + 1} 张图片读取失败：${chatReadFailure(bitmap.exceptionOrNull() ?: IllegalStateException())}")
                        pages.add(ChatScreenshotStitcher.Page(emptyList()))
                        process(index + 1)
                        return@post
                    }
                    val bmp = bitmap.getOrThrow()
                    val input = InputImage.fromBitmap(bmp, 0)
                    recognizer.process(input)
                        .addOnSuccessListener { ocr ->
                            val page = ChatScreenshotProcessor.extract(bmp, ocr, top, bottom, strict)
                            pages.add(page)
                            if (page.seenLines == 0) errors.add("第 ${index + 1} 张：OCR 未读到文字")
                            if (page.usedFallback) errors.add("第 ${index + 1} 张：气泡颜色不确定，已生成待核对草稿")
                            else if (page.uncertainLines > 0) errors.add("第 ${index + 1} 张：含 ${page.uncertainLines} 行位置推测文字，请核对")
                        }
                        .addOnFailureListener {
                            errors.add("第 ${index + 1} 张 OCR 失败：${chatReadFailure(it)}")
                            pages.add(ChatScreenshotStitcher.Page(emptyList()))
                        }
                        .addOnCompleteListener {
                            bmp.recycle()
                            process(index + 1)
                        }
                }
            }.start()
        }
        process(0)
    }

    private fun reviewChatBatch(name: String, imageCount: Int, result: ChatScreenshotStitcher.Result, errors: List<String>) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        body.addView(text("${imageCount} 张截图 · 识别 ${result.messages} 个气泡 · 跨页重叠 ${result.overlaps} 个；待核对 ${result.gaps.size} 处。", 14f, blue, true))
        body.addView(text("聊天内容按截图位置推测。部分文字可能漏识别或误归属，尤其是颜色不确定的待核对草稿。请对照原图编辑后保存。", 12f, gray))
        if (errors.isNotEmpty()) body.addView(text(errors.joinToString("；"), 12f, Color.RED))
        val title = EditText(this).apply { setText(name); setSingleLine(true) }
        val content = EditText(this).apply { setText(result.text); textSize = 14f; minLines = 12; maxLines = 22; gravity = Gravity.TOP }
        body.addView(text("档案名称", 12f, gray)); body.addView(title)
        body.addView(text("整理稿 · 可编辑", 12f, gray)); body.addView(content)
        val scroller = ScrollView(this).apply { addView(body) }
        AlertDialog.Builder(this).setTitle("预览并修正后保存")
            .setView(scroller).setNegativeButton("放弃，不保存", null)
            .setPositiveButton("保存到聊天档案") { _, _ ->
                val heading = title.text.toString().trim()
                val value = content.text.toString().trim()
                if (heading.isBlank() || value.isBlank()) { toast("名称或正文为空，未保存"); return@setPositiveButton }
                val id = runCatching { chatBatchDb.save(heading, value, imageCount) }.getOrDefault(-1L)
                toast(if (id > 0) "已保存批量聊天整理稿（应用内明文）" else "保存失败或内容超长")
                if (id > 0) showPage("wechat")
            }.show()
    }

    private fun showPlainChatEntry(entry: ChatBatchArchive.Entry) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8)) }
        val name = EditText(this).apply { setText(entry.title); setSingleLine(true) }
        val body = EditText(this).apply { setText(entry.content); minLines = 10; maxLines = 22; gravity = Gravity.TOP; textSize = 14f }
        layout.addView(text("聊天名称", 12f, gray)); layout.addView(name)
        layout.addView(text("可修改文字（非加密档案）", 12f, gray)); layout.addView(body)
        addButton(layout, "复制全文") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("欧南聊天整理稿", body.text.toString()))
            toast("已复制，注意剪贴板可能被其他应用读取")
        }
        addButton(layout, "导出 TXT 文档") {
            pendingTxtDocument = body.text.toString()
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "text/plain"
                putExtra(Intent.EXTRA_TITLE, "欧南_聊天截图_${System.currentTimeMillis()}.txt")
            }, REQ_EXPORT_TXT)
        }
        val scroller = ScrollView(this).apply { addView(layout) }
        AlertDialog.Builder(this).setTitle("${entry.imageCount} 张截图 · 聊天整理档案")
            .setView(scroller).setNegativeButton("关闭", null)
            .setNeutralButton("删除") { _, _ ->
                AlertDialog.Builder(this).setTitle("确定删除这份明文档案？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ -> admin.delete(LocalKnowledgeAdmin.SCREENSHOTS, entry.id); showPage("wechat") }.show()
            }
            .setPositiveButton("保存修改") { _, _ ->
                val ok = admin.edit(LocalKnowledgeAdmin.SCREENSHOTS, entry.id, name.text.toString().trim(), body.text.toString())
                toast(if (ok) "已更新整理稿" else "更新失败，请核对内容")
                if (ok) showPage("wechat")
            }.show()
    }
    private fun askWeChatExport() {
        val pass = EditText(this).apply {
            hint = "至少12位的独立备份口令"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("创建加密备份")
            .setMessage("强烈建议备份口令不同于任何网站密码，保管在独立密码管理器中。丢失口令无法恢复，导出后请验证备份文件可读。")
            .setView(pass).setNegativeButton("取消", null)
            .setPositiveButton("选择保存位置") { _, _ ->
                val value = pass.text.toString()
                if (value.length < 12) { toast("备份口令至少 12 位"); return@setPositiveButton }
                privateExportPassword = value.toCharArray()
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE); type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE, "ounan-wechat-private.ounan")
                }, REQ_WECHAT_EXPORT)
            }.show()
    }
    private fun startWeChatImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_WECHAT_IMPORT)
    }
    private fun importWeChatBackup(uri: Uri) {
        val pass = EditText(this).apply { hint = "输入导出时的备份口令"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        AlertDialog.Builder(this).setTitle("恢复微信加密留档")
            .setView(pass).setNegativeButton("取消", null)
            .setPositiveButton("解密并恢复") { _, _ ->
                val chars = pass.text.toString().toCharArray()
                Thread {
                    val result = runCatching {
                        require(chars.size >= 12) { "口令不足12位" }
                        val bytes = contentResolver.openInputStream(uri)?.use { ins ->
                            val buffer = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            while (true) {
                                val n = ins.read(chunk)
                                if (n < 0) break
                                require(buffer.size() + n <= 20 * 1024 * 1024) { "备份超过20MB：请使用分卷备份或升级导入器" }
                                buffer.write(chunk, 0, n)
                            }
                            buffer.toString("UTF-8")
                        } ?: error("备份文件无法读取")
                        wechatDb.importEncrypted(bytes, chars)
                    }
                    chars.fill('\u0000')
                    mainHandler.post {
                        result.onSuccess { toast("恢复完成：新增 ${it.first} 条，共检查 ${it.second} 条") }
                            .onFailure { toast("恢复失败：${it.localizedMessage}") }
                        if (page == "wechat") showPage("wechat")
                    }
                }.start()
            }.show()
    }

    /** V0.5.0 local-only administrator: preview and reversible operations; no GPT network connection. */
    private fun showAdmin(container: LinearLayout) {
        addTitle(container, "本地知识管理员 · V0.5.0.1", "统一检索三套数据库；可修改和删除，操作有加密历史。当前尚未接入 GPT，不会上传数据。")
        val intro = card()
        intro.addView(text("已授权的未来 GPT 权限：全库读取、新增、编辑、删除；由欧南执行有限的本地命令。", 14f, ink))
        intro.addView(text("本版只允许你在手机上手动操作。显示结果每类最多 30 条，可搜索更多；不表示全库只有这些。", 12f, gray))
        addButton(intro, "🤖 GPT 智能知识管理员（需配置个人 HTTPS 接口）") { showPage("agent") }
        addButton(intro, "＋ 新建知识或聊天记录") { chooseAdminCreate() }
        addButton(intro, "↶ 查看修改历史 · 回收恢复") { showAdminHistory() }
        addButton(intro, "返回原有的知识关联页面") { showPage("ai") }
        container.addView(intro)

        val bar = card()
        val search = EditText(this).apply {
            hint = "搜索所有知识和聊天内容"
            setSingleLine(true)
            setText(adminSearchQuery)
        }
        bar.addView(search)
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("全部" to "all", "知识" to LocalKnowledgeAdmin.NOTES,
            "微信" to LocalKnowledgeAdmin.WECHAT, "截图" to LocalKnowledgeAdmin.SCREENSHOTS).forEach { (label,kind) ->
            val b = Button(this).apply {
                text = if (kind == adminSearchSource) "●$label" else label
                textSize = 12f
                isAllCaps = false
                setOnClickListener {
                    adminSearchSource = kind
                    showPage("admin")
                }
            }
            filters.addView(b, LinearLayout.LayoutParams(0, dp(45), 1f))
        }
        bar.addView(filters)
        container.addView(bar)
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(results)
        fun render() {
            results.removeAllViews()
            runCatching { admin.search(adminSearchQuery,adminSearchSource,30) }
                .onSuccess { found ->
                    results.addView(text("本次显示 ${found.size} 条匹配记录（每类最多 30 条）", 12f,gray))
                    if (found.isEmpty()) results.addView(text("还没有匹配的记录", 13f,gray))
                    found.forEach { item ->
                        val line = card()
                        line.addView(text(item.title, 16f, ink, true))
                        line.addView(text(item.body.replace("\n"," ").take(125), 13f,gray))
                        line.addView(text("${LocalKnowledgeAdmin.sourceLabel(item.source)} · ID ${item.id}", 11f, blue))
                        line.setOnClickListener { openAdminEditor(item.source,item.id) }
                        results.addView(line)
                    }
                }
                .onFailure { e -> results.addView(text("本地数据库读取失败：${e.javaClass.simpleName}",13f,gray)) }
        }
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        search.addTextChangedListener(object: android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adminSearchQuery = s?.toString().orEmpty()
                pending?.let { handler.removeCallbacks(it) }
                pending = Runnable { if (page == "admin") render() }
                handler.postDelayed(pending!!, 260L)
            }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        render()
    }

    private fun chooseAdminCreate() {
        val names = arrayOf("知识碎片", "微信留档（人工录入）", "截图聊天（人工录入）")
        val sources = arrayOf(LocalKnowledgeAdmin.NOTES,LocalKnowledgeAdmin.WECHAT,LocalKnowledgeAdmin.SCREENSHOTS)
        AlertDialog.Builder(this).setTitle("选择新记录来源")
            .setItems(names) { _, choice -> openAdminEditor(sources[choice],null) }
            .setNegativeButton("取消",null).show()
    }

    private fun openAdminEditor(source: String, id: Long?) {
        val current = id?.let { admin.get(source,it) }
        if (id != null && current == null) { toast("记录已不存在，请刷新"); showPage("admin"); return }
        val wrapper = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(16),dp(10),dp(16),dp(5))
        }
        val titleBox = EditText(this).apply { hint="标题 / 聊天名称"; setText(current?.title.orEmpty()); setSingleLine(true) }
        val tagsBox = EditText(this).apply {
            hint="标签（截图聊天暂无标签字段）"; setText(current?.tags.orEmpty()); setSingleLine(true)
        }
        val memoBox = EditText(this).apply { hint="人工备注"; setText(current?.note.orEmpty()); minLines=2 }
        val bodyBox = EditText(this).apply {
            hint="原始正文 / 聊天文本"; setText(current?.body.orEmpty())
            minLines=6; maxLines=14; gravity=Gravity.TOP
        }
        wrapper.addView(text("标题",12f,gray)); wrapper.addView(titleBox)
        if (source != LocalKnowledgeAdmin.SCREENSHOTS) {
            wrapper.addView(text("标签",12f,gray)); wrapper.addView(tagsBox)
        }
        wrapper.addView(text("正文 · 编辑前的版本会加密保存到历史",12f,gray))
        wrapper.addView(bodyBox)
        if (source == LocalKnowledgeAdmin.WECHAT) { wrapper.addView(text("备注",12f,gray)); wrapper.addView(memoBox) }
        if (current != null) wrapper.addView(text("来源：${LocalKnowledgeAdmin.sourceLabel(source)} · 原始 ID：${current.id}",12f,gray))
        val scroll = ScrollView(this).apply { addView(wrapper) }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (current == null) "新增本地记录" else "查看与编辑本地记录")
            .setView(scroll)
            .setNegativeButton("取消",null)
            .setPositiveButton("保存",null)
            .apply { if (current != null) setNeutralButton("移入回收历史",null) }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ok = runCatching {
                    if (current == null) admin.create(source,titleBox.text.toString(),bodyBox.text.toString(),tagsBox.text.toString())
                    else admin.edit(source,current.id,titleBox.text.toString(),bodyBox.text.toString(),tagsBox.text.toString(),
                        if (source == LocalKnowledgeAdmin.WECHAT) memoBox.text.toString() else null)
                }.getOrElse { toast("保存异常：${it.javaClass.simpleName}"); false }
                if (ok) { dialog.dismiss(); toast("已保存，修改可在历史中撤销"); showPage("admin") }
                else toast("保存失败，请检查正文、长度或重复记录")
            }
            if (current != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                AlertDialog.Builder(this).setTitle("将记录移入可恢复历史？")
                    .setMessage("会从当前知识库移走记录，原文保存在手机加密操作历史。可在管理页恢复。")
                    .setNegativeButton("取消",null)
                    .setPositiveButton("确认移走") { _,_ ->
                        val ok = runCatching { admin.delete(source,current.id) }.getOrDefault(false)
                        if (ok) { dialog.dismiss(); toast("已移入回收历史"); showPage("admin") }
                        else toast("操作失败，原记录未确认删除")
                    }.show()
            }
        }
        dialog.show()
    }

    private fun showAdminHistory() {
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(10),dp(14),dp(8)) }
        scroll.addView(body)
        val changes = runCatching { changeJournal.recent(40) }.getOrElse {
            toast("读取历史失败：${it.javaClass.simpleName}"); emptyList()
        }
        if (changes.isEmpty()) body.addView(text("暂无通过本地管理员执行的操作",13f,gray))
        else body.addView(text("按时间从新到旧排列；标题和正文预览来自本机已有的加密历史快照。",12f,gray))
        changes.forEach { h ->
            val box = card()
            val op = when(h.action) { "create" -> "新建"; "edit" -> "修改"; "delete" -> "删除"; else -> "操作" }
            val state = when(h.state) { "applied" -> "可尝试撤销"; "undone" -> "已撤销"; else -> "操作中断，需核对" }
            val recordTitle = AdminHistoryDisplay.title(h)
            box.addView(text(recordTitle,16f,ink,true))
            box.addView(text("$op · ${LocalKnowledgeAdmin.sourceLabel(h.source)} · ID ${h.recordId}",12f,blue))
            box.addView(text("$state · ${SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(Date(h.timestamp))}",12f,gray))
            box.addView(text(AdminHistoryDisplay.summary(h),13f,gray))
            addButton(box,"查看操作前后完整内容") {
                AlertDialog.Builder(this).setTitle("历史详情 · $op")
                    .setMessage(AdminHistoryDisplay.details(h))
                    .setPositiveButton("关闭",null).show()
            }
            if (h.state == "applied" || h.state == "pending") addButton(box,"↶ 撤销这次操作") {
                AlertDialog.Builder(this).setTitle("确认撤销「${recordTitle.take(35)}」？")
                    .setMessage("操作：$op · ${LocalKnowledgeAdmin.sourceLabel(h.source)} · ID ${h.recordId}\n\n只有记录仍符合当时的修改状态才会恢复，防止覆盖后续变更。")
                    .setNegativeButton("取消",null)
                    .setPositiveButton("执行恢复") { _,_ ->
                        val ok = runCatching { admin.undo(h.id) }.getOrElse { toast("恢复异常：${it.javaClass.simpleName}"); false }
                        toast(if (ok) "已恢复上一版本" else "无法安全恢复：记录可能已再次修改")
                        if (ok) { showPage("admin") }
                    }.show()
            }
            body.addView(box)
        }
        AlertDialog.Builder(this).setTitle("本地修改历史 / 回收恢复")
            .setView(scroll).setPositiveButton("关闭",null).show()
    }

    /** Explicit user-initiated GPT connection; the APK contains no OpenAI key.
     * No background uploads. Local CRUD tools require type checks and user write approval.
     */
    private fun showAgent(container: LinearLayout) {
        addTitle(container, "GPT · 第二大脑管理员 V0.5.2", "GPT 可以按需检索三套本地数据库，提出新增、修改、删除和恢复命令。网络需要你自己的 HTTPS 接口服务。")
        val setup = card()
        setup.addView(text("HTTPS 接口地址（你的个人服务端）",13f,ink,true))
        val url = EditText(this).apply {
            hint = "https://your-domain.example"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(agentEndpoint.ifBlank { getPreferences(MODE_PRIVATE).getString("agent_endpoint","") })
        }
        setup.addView(url)
        setup.addView(text("连接口令（Android Keystore 加密保存在本机，重启不必重填）",13f,ink,true))
        val secret = EditText(this).apply {
            hint = "个人服务端 OU NAN APP TOKEN"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(agentToken)
        }
        setup.addView(secret)
        addButton(setup, "保存本次连接设置") {
            val endpoint = url.text.toString().trim().trimEnd('/')
            if (!endpoint.startsWith("https://") || endpoint.contains("@") || endpoint.contains("?")) {
                toast("只允许 HTTPS 地址，不接受含账号或参数的 URL")
            } else if (secret.text.isBlank()) {
                toast("请填写服务端连接口令")
            } else {
                if (agentEndpoint != endpoint || agentToken != secret.text.toString()) agentSession = ""
                agentEndpoint = endpoint
                agentToken = secret.text.toString()
                runCatching { agentSecureConnection.save(agentEndpoint,agentToken) }
                    .onFailure { toast("加密保存失败，请检查设备密钥；口令仅在本次会话使用") }
                    .onSuccess { toast("网关地址与口令已加密保存（不包含 OpenAI API Key）") }
                getPreferences(MODE_PRIVATE).edit().remove("agent_endpoint").apply()
            }
        }
        addButton(setup, "断开 · 清除会话与本次口令") {
            agentSession = ""; agentToken = ""; agentNetworkConsented=false; secret.setText(""); agentSecureConnection.clear()
            agentStatus?.text = "本机口令和会话已清除；聊天日志仍在本地"
        }
        setup.addView(text("重要：首次使用请只拿虚构的测试记录演练。GPT 的工具调用会把检索结果发往你配置的服务及 OpenAI API；不会把数据库文件上传。",12f,gray))
        container.addView(setup)

        val chat = card()
        chat.addView(text("欧南 GPT · 对话与日志（仅本地加密存储）",17f,ink,true))
        val historyScroll = ScrollView(this).apply { setFillViewport(false) }
        val historyBody = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(5),dp(6),dp(5),dp(6)) }
        historyScroll.addView(historyBody)
        chat.addView(historyScroll,LinearLayout.LayoutParams(-1,dp(360)))
        agentMessagesPanel = historyBody; agentMessagesScroll = historyScroll
        agentChatDb.recent(100).forEach { m -> addAgentBubble(historyBody,m.role,m.text) }
        historyScroll.post { historyScroll.fullScroll(View.FOCUS_DOWN) }
        val prompt = EditText(this).apply {
            hint = "例如：搜索‘欧南数据库测试’，列出来源和原文依据"
            minLines=3; maxLines=6
            setText(pendingAgentDraft.orEmpty())
        }
        pendingAgentDraft = null
        chat.addView(prompt)
        agentStatus = text("未连接 · 未发送",12f,gray)
        chat.addView(agentStatus)
        agentAudit = text("本轮本地工具：尚未执行",12f,gray).apply { setTextIsSelectable(true) }
        chat.addView(agentAudit)
        agentAnswer = text("回答会显示在这里；数据库不会在你不操作时后台上传。",14f,ink).apply {
            setTextIsSelectable(true)
        }
        // This private TextView is the current response buffer. The scrollable chat
        // transcript shows the reply exactly once when the request completes.
        addButton(chat, "发送消息 · GPT 可按需调用本地数据库") {
            if (agentBusy) { toast("正在处理上一条请求"); return@addButton }
            if (agentEndpoint.isBlank() || agentToken.isBlank()) { toast("请先保存个人接口地址与口令"); return@addButton }
            val q = prompt.text.toString().trim()
            if (q.isBlank() || q.length > 2000) { toast("请输入 1～2000 字的问题"); return@addButton }
            val sendNow: () -> Unit = {
                val context = agentChatDb.recent(10).filter { it.role == "user" || it.role == "assistant" }
                    .takeLast(6).map { it.role to it.text.take(650) }
                agentBusy = true; agentSession = ""; agentAnswer?.text = ""
                appendAgentMessage("user",q)
                prompt.setText("")
                agentToolCount = 0; agentReadCount = 0; agentToolEvents.clear()
                agentDbRequested = Regex("搜索|检索|查询|查找|找到|找出|读取|数据库|知识库|聊天记录|档案").containsMatchIn(q) &&
                    !Regex("不需要读取|不要读取|无需读取|不要调用数据库").containsMatchIn(q)
                agentAudit?.text = "本轮本地工具：等待服务器指令……"
                agentRound(q,null,0,context)

            }
            if (!agentNetworkConsented) {
                AlertDialog.Builder(this).setTitle("首次连接授权")
                    .setMessage("本次 APP 运行期间允许欧南将你主动提问及相关本地检索结果发送到个人网关和 OpenAI。此后聊天不再每条重复弹窗；修改和删除数据库仍必须逐次确认。")
                    .setNegativeButton("取消",null)
                    .setPositiveButton("同意并发送") { _,_ -> agentNetworkConsented=true; sendNow() }
                    .show()
            } else sendNow()
        }
        addButton(chat, "🧠 对当前知识进行 GPT 初审（先检索、后建议，修改须批准）") {
            prompt.setText("请先搜索 notes 中近期采集的知识碎片，选取最多 3 条有价值的记录；逐条提出 1～3 个标签、核心观点摘要、是否存在明显杂乱内容。不要删除、不要修改原文，未查到就如实说明。")
            toast("已填入 GPT 初审指令，确认后再发送；此操作会产生 API 费用")
        }
        addButton(chat, "清空本机 GPT 聊天日志") {
            AlertDialog.Builder(this).setTitle("清空聊天记录？")
                .setMessage("将删除欧南手机上保存的 GPT 问答与工具日志，不会删除知识库原文，也不会清除服务端密钥。")
                .setNegativeButton("取消",null)
                .setPositiveButton("清空本机日志") { _,_ ->
                    agentChatDb.clear(); agentMessagesPanel?.removeAllViews(); agentSession="";
                    toast("本机 GPT 对话日志已清空")
                }.show()
        }
        addButton(chat, "查看本地操作历史与恢复") { showAdminHistory() }
        container.addView(chat)
    }

    private fun agentRound(question: String?, toolResults: JSONArray?, depth: Int,
                           context: List<Pair<String,String>> = emptyList()) {
        if (depth >= 8) { agentBusy=false; agentStatus?.text="达到单次工具调用上限（8轮），已停止"; return }
        agentStatus?.text = "GPT 正在处理第 ${depth+1} 轮请求……"
        val payload = JSONObject()
        if (question != null) {
            payload.put("question",question)
            payload.put("context",JSONArray().apply {
                context.forEach { (role,body) -> put(JSONObject().put("role",role).put("content",body)) }
            })
        }
        else { payload.put("session_id",agentSession); payload.put("tool_results",toolResults ?: JSONArray()) }
        val endpoint = agentEndpoint; val token = agentToken
        Thread {
            val result = runCatching { GptRelayClient.exchange(endpoint,token,payload) }
            runOnUiThread {
                result.onFailure { failure ->
                    agentBusy = false
                    val detail = failure.message?.take(450).orEmpty()
                    // IllegalStateException is also used for HTTP 401/502/503 in GptRelayClient.
                    // Show HTTP status plus server-safe error details instead of the class alone.
                    agentStatus?.text = if (detail.startsWith("服务器返回 HTTP ")) {
                        "请求失败：" + detail.substringBefore('：')
                    } else {
                        "连接失败：${failure.javaClass.simpleName}"
                    }
                    val shown = if (detail.isNotBlank()) "错误详情：$detail"
                        else "错误详情：${failure.javaClass.simpleName}（没有更多诊断信息）"
                    agentAnswer?.text = shown
                    appendAgentMessage("audit", shown)
                }
                result.onSuccess { data ->
                    agentSession = data.optString("session_id", agentSession)
                    val calls = data.optJSONArray("calls") ?: JSONArray()
                    if (calls.length() == 0) {
                        agentBusy = false
                        agentStatus?.text= if (agentToolCount == 0)
                            "已完成 · 本轮实际执行 0 次本地工具" else
                            "已完成 · 本轮实际执行 $agentToolCount 次本地工具（读取 $agentReadCount 次）"
                        val answerText = data.optString("answer","本轮没有生成文本")
                        agentAnswer?.text=if (agentToolCount == 0 && agentDbRequested)
                            "⚠ 本轮没有执行本地数据库工具。以下模型文字未经数据库检索核验：\n$answerText"
                            else answerText
                        appendAgentMessage("assistant",agentAnswer?.text?.toString().orEmpty())
                        if (agentToolCount == 0) agentAudit?.text = "本轮没有工具执行记录"

                    } else {
                        val writes = mutableListOf<String>()
                        for (i in 0 until calls.length()) {
                            val c = calls.optJSONObject(i) ?: continue
                            val name = c.optString("name")
                            val args = runCatching { JSONObject(c.optString("arguments","{}")) }.getOrDefault(JSONObject())
                            if (agentTools.isWrite(name)) writes.add(agentTools.describe(name,args))
                        }
                        val execute: (Boolean) -> Unit = { allow -> agentExecuteCalls(calls,allow,depth) }
                        if (writes.isEmpty()) execute(true)
                        else AlertDialog.Builder(this).setTitle("GPT 请求修改本地数据库")
                            .setMessage("这些操作将修改数据，并写入可恢复的操作历史：\n\n" + writes.joinToString("\n").take(1600))
                            .setNegativeButton("拒绝更改") { _,_ -> execute(false) }
                            .setPositiveButton("批准本轮操作") { _,_ -> execute(true) }
                            .setCancelable(false).show()
                    }
                }
            }
        }.start()
    }

    private fun agentExecuteCalls(calls: JSONArray, allowWrites: Boolean, depth: Int) {
        agentStatus?.text="正在本机执行受控工具（${calls.length()} 项）……"
        Thread {
            val results = JSONArray()
            val auditLines = mutableListOf<String>()
            val readCalls = mutableListOf<String>()
            for (i in 0 until calls.length().coerceAtMost(8)) {
                val c = calls.optJSONObject(i) ?: continue
                val name = c.optString("name")
                val args = runCatching { JSONObject(c.optString("arguments","{}")) }.getOrDefault(JSONObject())
                val output = if (agentTools.isWrite(name) && !allowWrites)
                    JSONObject().put("ok",false).put("error","用户拒绝本轮写操作")
                else agentTools.call(name,args)
                results.put(JSONObject().put("call_id",c.optString("call_id")).put("output",output))
                val ok = output.optBoolean("ok", false)
                val label = when (name) {
                    "search_records" -> "搜索(${args.optString("source", "all")}) → ${output.optJSONArray("results")?.length() ?: 0} 条${if (ok) "" else "（失败）"}"
                    "get_record" -> "读取(${args.optString("source")} #${args.optLong("id")}) → ${if (ok) "成功，${output.optInt("total_chars")} 字" else "失败"}"
                    "create_record", "edit_record", "delete_record", "undo_change" -> "$name → ${if (ok) "成功" else "未执行或失败"}"
                    else -> "$name → 未支持"
                }
                auditLines.add(label)
                if (name == "search_records" || name == "get_record") readCalls.add(name)
            }
            runOnUiThread {
                agentToolCount += auditLines.size
                agentReadCount += readCalls.size
                agentToolEvents.addAll(auditLines)
                agentAudit?.text = "本轮本机工具调用记录：\n" + agentToolEvents.joinToString("\n")
                if (auditLines.isNotEmpty()) appendAgentMessage("audit",auditLines.joinToString("\n"))
                agentRound(null,results,depth+1)
            }
        }.start()
    }

    private fun addAgentBubble(panel: LinearLayout, role: String, body: String) {
        val header = when(role) { "user" -> "我"; "assistant" -> "欧南 GPT"; else -> "本机工具 / 诊断" }
        val color = when(role) { "user" -> blue; "assistant" -> ink; else -> gray }
        val view = text("$header\n$body", if(role=="audit") 12f else 14f,color).apply {
            setTextIsSelectable(true)
            setPadding(dp(12),dp(9),dp(12),dp(9))
            setBackgroundColor(if(role=="user") Color.rgb(231,240,255) else if(role=="assistant") Color.WHITE else Color.rgb(239,241,245))
        }
        val lp = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }
        panel.addView(view,lp)
    }
    private fun appendAgentMessage(role:String, body:String) {
        if (body.isBlank()) return
        runCatching { agentChatDb.append(role,body) }
            .onFailure { toast("聊天日志本地保存失败：${it.javaClass.simpleName}") }
        agentMessagesPanel?.let { addAgentBubble(it,role,body) }
        agentMessagesScroll?.post { agentMessagesScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun showAi(container: LinearLayout) {
        addTitle(container, "跨档案本地关联", "同一个关键词查找知识碎片与微信留档；两类原文分别保存。")
        val info = card()
        info.addView(text("知识碎片 ${db.count()} 条 · 微信摘录 ${wechatDb.list(limit = 10000).size} 条", 18f, ink, true))
        info.addView(text("现在是本地关键词关联。未来会由你授权的智能体补全概念和证据链；本版不联网。", 13f, gray))
        container.addView(info)
        val input = EditText(this).apply {
            hint = "输入主题或联系人关键词：例如 AI、机器人、合作"
            setSingleLine(true)
        }
        container.addView(input)
        val out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        container.addView(out)
        addButton(container, "搜索两类私人记忆") {
            out.removeAllViews()
            val term = input.text.toString().trim()
            if (term.isBlank()) { toast("请先输入关键词"); return@addButton }
            val fragments = db.list(term, limit = 80)
            val chats = wechatDb.list(term, limit = 80)
            out.addView(text("知识碎片：${fragments.size} 条", 17f, ink, true))
            fragments.forEach { addNoteCard(out, it) }
            out.addView(text("微信摘录：${chats.size} 条", 17f, ink, true))
            chats.forEach { excerpt ->
                val box = card()
                box.addView(text(excerpt.conversation, 15f, ink, true))
                box.addView(text(KnowledgeRules.summary(excerpt.body), 13f, gray))
                box.addView(text("${excerpt.tags} · 微信档案", 11f, blue))
                box.setOnClickListener { showWeChatEntry(excerpt) }
                out.addView(box)
            }
            if (fragments.isEmpty() && chats.isEmpty()) out.addView(text("没有匹配的原始资料", 13f, gray))
        }
        val explain = card()
        explain.addView(text("第二大脑将来的工作", 17f, ink, true))
        explain.addView(text("保留原始证据 → 逐步补充知识点 → 关联不同来源 → 检测观点冲突 → 给出有出处的辅助决策。GPT 深度分析和知识图谱尚未实现。", 13f, gray))
        container.addView(explain)
    }

    private fun showResearch(container: LinearLayout) {
        addTitle(container, "知识研究室", "第一版提供可解释的本地关键词归类，不把规则匹配伪装成大模型分析。")
        val notes = db.list(limit = 1000)
        val stats = card()
        stats.addView(text("知识主题分布", 18f, ink, true))
        val counts = KnowledgeRules.categoryCounts(notes)
        if (counts.isEmpty()) stats.addView(text("有了收藏之后，这里会形成主题索引。", 13f, gray))
        counts.forEach { (name, count) -> stats.addView(text("$name   ·   $count 条", 15f, ink)) }
        container.addView(stats)

        val ideas = card()
        ideas.addView(text("待逐步实现的智能分析能力", 18f, ink, true))
        listOf("向量语义检索和相似度聚类", "自动核查事实与预测（包含外部事实源）", "知识冲突检测和事件时间线更新", "后台同步与跨设备的随时访问", "长期研究记忆的版本化管理").forEach {
            ideas.addView(text("• $it", 14f, gray))
        }
        container.addView(ideas)
    }

    private fun showSettings(container: LinearLayout) {
        addTitle(container, "隐私与加密备份", "资料仅保存在你的手机中；卸载或清除 APP 数据会删除设备密钥。")
        val screen = card()
        screen.addView(text("系统截图（测试开放）", 18f, ink, true))
        screen.addView(text("此版本不使用 FLAG_SECURE 阻止屏幕截图或录屏；可直接截图反馈界面问题。注意手机系统和其他 APP 可能记录屏幕上的私人信息，请勿展示密码、验证码或私人聊天。", 13f, gray))
        container.addView(screen)
        val floating = card()
        floating.addView(text("🕷 侧边迷你蜘蛛（可选）", 18f, ink, true))
        val shortcutPrefs = getSharedPreferences("spider_shortcut", MODE_PRIVATE)
        val shortcutEnabled = shortcutPrefs.getBoolean("enabled", false)
        floating.addView(text("开启后只出现悬浮小蜘蛛，不会扫描或保存当前页面。只有在知乎等阅读 APP 内亲自点蜘蛛「开始采集此 APP」，才持续读取到你点停止为止；结束后返回待机。", 13f, gray))
        addButton(floating, if (shortcutEnabled) "关闭侧边蜘蛛（停止常驻显示）" else "开启侧边蜘蛛（仅待机按钮）") {
            if (!isWeChatReaderEnabled()) { toast("请先开启欧南无障碍服务"); return@addButton }
            shortcutPrefs.edit().putBoolean("enabled", !shortcutEnabled).apply()
            toast("设置已更改，侧边按钮会在几秒内更新")
            showPage("settings")
        }
        container.addView(floating)
        val rules = card()
        rules.addView(text("本版隐私规则", 17f, ink, true))
        listOf("普通碎片与微信留档都在写入时 AES-GCM 加密；标题和正文不会明文存进 SQLite。",
               "微信聊天只按你主动开启的限时会话采集可见文字，不会爬取整个微信历史。",
               "普通蜘蛛必须先开启悬浮待机，再在阅读 APP 中手动点开始；结束前持续读取该 APP 可见文字。短词降权不能保证过滤全部私人信息。",
               "知识采集和本地标签默认不联网；你主动发送 GPT 提问时，检索结果可能发送到自己的网关及 OpenAI。",
               "加密导出是独立备份；密码忘记后无法恢复。每种档案分别导出一份。",
               "没有独立密码保险库，不要保存账号密码、验证码、私钥或助记词。",
               "删除微信原始聊天前，务必核对采集是否完整，并测试备份恢复；OCR 会漏字。")
            .forEach { rules.addView(text("• $it", 13f, gray)) }
        container.addView(rules)
        val data = card()
        data.addView(text("知识碎片 · 加密备份", 18f, ink, true))
        addButton(data, "导出加密知识库备份") { askFragmentExport() }
        addButton(data, "导入知识库加密备份") { startFragmentImport() }
        addButton(data, "清空全部知识碎片") {
            AlertDialog.Builder(this).setTitle("确定要删除所有知识碎片？")
                .setMessage("请先导出加密备份并验证恢复。微信档案不会受到影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("永久删除") { _, _ -> db.clearAll(); showPage("settings"); toast("已清空知识碎片") }.show()
        }
        container.addView(data)
        container.addView(text("版本 V0.5.3 · 持续蜘蛛采集 / 明确点按粘贴 / 本地自动标签。换机前请备份。", 12f, gray))
    }

    private fun askFragmentExport() {
        val pass = EditText(this).apply {
            hint = "设置独立备份密码（至少12位）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("导出加密知识碎片")
            .setMessage("文件可用来换机恢复。忘记口令无法解密，请在安全位置保存口令。")
            .setView(pass).setNegativeButton("取消", null)
            .setPositiveButton("选择保存位置") { _, _ ->
                val chars = pass.text.toString().toCharArray()
                if (chars.size < 12) { chars.fill('\u0000'); toast("密码至少12位"); return@setPositiveButton }
                fragmentExportPassword?.fill('\u0000')
                fragmentExportPassword = chars
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/octet-stream"
                    putExtra(Intent.EXTRA_TITLE,"ounan-knowledge-private.ounan")
                }, REQ_FRAGMENT_EXPORT)
            }.show()
    }
    private fun startFragmentImport() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQ_FRAGMENT_IMPORT)
    }
    private fun importFragmentBackup(uri: Uri) {
        val pass = EditText(this).apply {
            hint = "输入备份密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle("恢复知识库加密备份")
            .setView(pass).setNegativeButton("取消", null)
            .setPositiveButton("解密并导入") { _, _ ->
                val chars = pass.text.toString().toCharArray()
                Thread {
                    val result = runCatching {
                        require(chars.size >= 12) { "备份口令长度不足" }
                        val payload = contentResolver.openInputStream(uri)?.use { readLimitedText(it) }
                            ?: error("无法读取备份文件")
                        db.importEncrypted(payload, chars)
                    }
                    chars.fill('\u0000')
                    mainHandler.post { result.onSuccess { toast("恢复完成：新增 ${it.first} 条，检查 ${it.second} 条") }
                        .onFailure { toast("恢复失败：${it.localizedMessage}") }
                        if (page == "settings") showPage("settings") }
                }.start()
            }.show()
    }
    private fun readLimitedText(ins: java.io.InputStream): String {
        val output = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            require(output.size() + n <= 20 * 1024 * 1024) { "备份超过20MB，需要后续升级大文件导入器" }
            output.write(buf,0,n)
        }
        return output.toString("UTF-8")
    }

    private fun addTitle(parent: LinearLayout, title: String, subtitle: String) {
        parent.addView(text(title, 23f, ink, true))
        parent.addView(text(subtitle, 13f, gray).apply { setPadding(0, dp(6), 0, dp(14)) })
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        val bgShape = android.graphics.drawable.GradientDrawable().apply {
            setColor(white)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(1), Color.rgb(226, 232, 241))
        }
        background = bgShape
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(13) }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(3).toFloat(), 1f)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, 0, 0, dp(6))
    }

    private fun addButton(parent: LinearLayout, caption: String, click: () -> Unit) {
        val button = Button(this).apply {
            text = caption
            textSize = 14f
            isAllCaps = false
            setTextColor(blue)
            setOnClickListener { click() }
        }
        parent.addView(button, LinearLayout.LayoutParams(-1, dp(50)))
    }

    private fun addNoteCard(parent: LinearLayout, note: KnowledgeDb.Note) {
        val box = card()
        box.addView(text(note.title, 16f, ink, true))
        box.addView(text(KnowledgeRules.summary(note.body), 13f, gray))
        val date = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(note.createdAt))
        box.addView(text("${note.tags}  ·  ${note.origin}  ·  $date", 11f, blue))
        box.setOnClickListener { showDetail(note) }
        parent.addView(box)
    }

    private fun showDetail(note: KnowledgeDb.Note) {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val titleEdit = EditText(this).apply { setText(note.title); setSingleLine(true); hint = "标题" }
        val tagsEdit = EditText(this).apply { setText(note.tags); setSingleLine(true); hint = "标签" }
        val contentEdit = TextView(this).apply {
            text = note.body
            textSize = 14f
            setTextColor(ink)
            setPadding(dp(8),dp(12),dp(8),dp(12))
        }
        holder.addView(text("标题", 13f, gray)); holder.addView(titleEdit)
        holder.addView(text("标签", 13f, gray)); holder.addView(tagsEdit)
        holder.addView(text("原始正文（本页只读；管理页可编辑并保存历史）", 13f, gray)); holder.addView(contentEdit)
        addButton(holder, "📖 查看自动排版稿（不改原文）") {
            showFormattedDocument(DocumentFormatter.knowledge(note.body), "知识文档 · 自动排版")
        }
        addButton(holder, "↧ 导出排版文档 TXT（明文，需确认）") {
            confirmFormattedExport(DocumentFormatter.knowledge(note.body))
        }
        addButton(holder, "↧ 导出这条文字记录（明文 TXT，需主动确认）") {
            AlertDialog.Builder(this).setTitle("导出明文会失去加密保护")
                .setMessage("导出的 TXT 可以被其他应用读取。请不要将包含隐私、密码或他人私密消息的文档导出到不可信位置。确定继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("选择保存位置") { _, _ ->
                    pendingTxtDocument = note.body
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "欧南_文字留档_${System.currentTimeMillis()}.txt")
                    }
                    startActivityForResult(i, REQ_EXPORT_TXT)
                }.show()
        }
        if (note.sourceUrl.isNotBlank()) holder.addView(text("原始链接：${note.sourceUrl}", 12f, blue))
        val aiSummary = db.aiSummary(note.id)
        if (aiSummary.isNotBlank()) holder.addView(text("GPT 已确认摘要：$aiSummary", 13f, blue))
        val revisionCount = db.revisionCount(note.id)
        if (revisionCount > 0) holder.addView(text("已记录 $revisionCount 次 AI 辅助修改", 12f, gray))
        val related = KnowledgeRules.related(note, db.list(limit = 300))
        if (related.isNotEmpty()) {
            holder.addView(text("关联主题：" + related.joinToString("；") { it.title.take(15) }, 12f, gray))
        }
        val wrapper = ScrollView(this).apply { addView(holder) }
        AlertDialog.Builder(this)
            .setTitle("查看与编辑")
            .setView(wrapper)
            .setNegativeButton("关闭", null)
            .setNeutralButton("删除") { _, _ ->
                AlertDialog.Builder(this).setTitle("删除这条收藏？")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除") { _, _ -> admin.delete(LocalKnowledgeAdmin.NOTES, note.id); showPage(page); toast("已移入可恢复历史") }.show()
            }
            .setPositiveButton("保存") { _, _ ->
                admin.edit(LocalKnowledgeAdmin.NOTES, note.id, titleEdit.text.toString(), note.body, tagsEdit.text.toString())
                showPage(page)
                toast("已保存")
            }.show()
    }

    private fun showFormattedDocument(formatted: String, heading: String) {
        val content = TextView(this).apply {
            text = formatted
            textSize = 15f
            setTextColor(ink)
            setTextIsSelectable(true)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val scroller = ScrollView(this).apply { addView(content) }
        AlertDialog.Builder(this).setTitle(heading)
            .setView(scroller).setPositiveButton("关闭", null).show()
    }

    private fun confirmFormattedExport(formatted: String) {
        AlertDialog.Builder(this).setTitle("导出排版稿（明文）")
            .setMessage("仅导出当前显示的自动排版稿。TXT 文件没有加密保护，其他有权限的应用可能读取它。请勿存到不可信的位置。原始记录仍保存在加密库，不会被替换。")
            .setNegativeButton("取消", null)
            .setPositiveButton("选择保存位置") { _, _ ->
                pendingTxtDocument = formatted
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "欧南_排版知识文档_${System.currentTimeMillis()}.txt")
                }
                startActivityForResult(i, REQ_EXPORT_TXT)
            }.show()
    }

    /** Reads the clipboard ONLY in response to a foreground, explicit user tap. */
    private fun pasteCapture() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
        val description = clip?.description
        if (clip == null || clip.itemCount == 0 || (description?.hasMimeType("text/plain") != true &&
                description?.hasMimeType("text/html") != true)) {
            toast("剪贴板没有可粘贴的文字；请先在抖音 AI 解析页复制全文")
            return
        }
        val body = (clip.getItemAt(0).text?.toString()
            ?: clip.getItemAt(0).coerceToText(this)?.toString()).orEmpty()
            .replace("\u0000", "").trim()
        if (body.isBlank()) { toast("剪贴板文字为空，请重新复制"); return }
        if (body.length > 30000) {
            AlertDialog.Builder(this).setTitle("复制内容太长")
                .setMessage("本次复制 ${body.length} 字，当前单条知识最多保存 30000 字。请在原页面分两次复制，避免截断原文。")
                .setPositiveButton("知道了",null).show()
            return
        }
        val preview = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        preview.addView(text("在保存前核对原文；不自动上传 GPT。",13f,gray))
        val title = EditText(this).apply {
            setSingleLine(true); hint = "文章标题（可修改）"
            setText(body.lineSequence().map { it.trim() }.firstOrNull { it.length >= 4 }?.take(80) ?: "复制的知识片段")
        }
        val source = EditText(this).apply {
            setSingleLine(true); hint="资料来源"
            setText("抖音 AI 解析 · 手动粘贴")
        }
        val tags = EditText(this).apply {
            setSingleLine(true); hint="知识标签"
            setText(KnowledgeRules.tags(body))
        }
        val raw = EditText(this).apply {
            setText(body); gravity=Gravity.TOP
            minLines=8; maxLines=18
        }
        preview.addView(text("标题",12f,gray)); preview.addView(title)
        preview.addView(text("来源（非抖音内容可修改）",12f,gray)); preview.addView(source)
        preview.addView(text("本地建议标签（可修改）",12f,gray)); preview.addView(tags)
        preview.addView(text("原始文字 · ${body.length} 字（可校对）",12f,gray)); preview.addView(raw)
        val scroll = ScrollView(this).apply { addView(preview) }
        AlertDialog.Builder(this).setTitle("手动粘贴采集 · 确认保存")
            .setView(scroll)
            .setNegativeButton("取消",null)
            .setPositiveButton("保存到本地") { _,_ ->
                val content=raw.text.toString().trim()
                if (content.isBlank() || content.length>30000) {
                    toast("内容为空或超出 30000 字，请修改后重试"); return@setPositiveButton
                }
                val id = runCatching { db.createCaptured(title.text.toString(), content,
                    tags.text.toString(),source.text.toString()) }.getOrElse { -1L }
                if (id <= 0L) { toast("未保存：可能已经存在相同原文"); return@setPositiveButton }
                AlertDialog.Builder(this).setTitle("知识已保存 · #$id")
                    .setMessage("原文已保留在手机知识库；标签已保存。需要 GPT 初审时，将所选内容发送到你配置的云端 API 并产生费用。")
                    .setNegativeButton("继续采集") { _,_ -> showPage("home") }
                    .setPositiveButton("GPT 初审（先预填，不自动发送）") { _,_ ->
                        pendingAgentDraft = "请调用 get_record，source=notes，id=$id；只对这一条知识提出摘要、主题标签和可能需要核实的事实。保留原文，不要修改或删除数据库记录。"
                        showPage("agent")
                    }.show()
            }.show()
    }

    private fun manualEntry() {
        val input = EditText(this).apply {
            hint = "把文章、观点、链接或想法粘贴到这里。"
            minLines = 6
            gravity = Gravity.TOP
        }
        AlertDialog.Builder(this).setTitle("保存知识片段")
            .setView(input).setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val ok = db.insert(input.text.toString(), "手动录入")
                toast(if (ok) "已收入知识库" else "内容为空或已存在")
                showPage("home")
            }.show()
    }

    private fun importImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startActivityForResult(intent, REQ_IMPORT)
    }

    private fun consumeSharedIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
            val ok = db.insert(sharedText, "外部 APP 分享")
            toast(if (ok) "分享内容已保存" else "分享内容为空或已存在")
            showPage("home")
        } else if (intent.type?.startsWith("image/") == true) {
            val imageUri = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            if (imageUri != null) recognizeImage(imageUri, "外部 APP 图片分享")
        }
        // Prevent replay when returning to the activity after rotation.
        setIntent(Intent(this, MainActivity::class.java))
    }

    private fun recognizeImage(uri: Uri, origin: String) {
        toast("正在本地识别图片文字…")
        try {
            val input = InputImage.fromFilePath(this, uri)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer.process(input)
                .addOnSuccessListener { result ->
                    val ok = db.insert(result.text, origin, minChars = 4)
                    toast(if (ok) "识别完成，已保存 ${result.text.length} 字" else "没有识别出足够文字，或内容已存在")
                    showPage("home")
                }
                .addOnFailureListener { toast("文字识别失败：${it.localizedMessage}") }
                .addOnCompleteListener { recognizer.close() }
        } catch (e: Exception) {
            toast("图片读取失败：${e.localizedMessage}")
        }
    }

    private fun confirmCapture() {
        AlertDialog.Builder(this)
            .setTitle("启动 10 分钟智能采集？")
            .setMessage("将向系统申请共享整个屏幕，授权后离线读取屏幕图文。某些隐私信息也可能被识别，请在切换账号、银行、支付、私人聊天时立即停止。通知栏可随时结束。")
            .setNegativeButton("暂不开启", null)
            .setPositiveButton("继续授权") { _, _ -> requestProjection() }
            .show()
    }

    /**
     * Request user-approved full-display capture rather than Android 14+'s default
     * single-app sharing. The system still decides what can be shared and can
     * override this request; secure/redacted windows remain protected.
     */
    private fun fullDisplayConsentIntent(manager: MediaProjectionManager): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
    }

    private fun requestProjection() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 12)
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(fullDisplayConsentIntent(manager), REQ_PROJECTION)
    }

    private fun stopCapture() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
        toast("已请求停止采集")
        frame.postDelayed({ if (page == "home") showPage("home") }, 300)
    }

    @Deprecated("Android Activity result API; kept to avoid external UI dependencies in this MVP")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) {
            if (requestCode == REQ_WECHAT_OCR) pendingWechatOcrChat = ""
            if (requestCode == REQ_EXPORT_TXT) pendingTxtDocument = null
            if (requestCode == REQ_FRAGMENT_EXPORT) {
                fragmentExportPassword?.fill('\u0000')
                fragmentExportPassword = null
            }
            if (requestCode == REQ_WECHAT_EXPORT) {
                privateExportPassword?.fill('\u0000')
                privateExportPassword = null
            }
            return
        }
        when (requestCode) {
            REQ_IMPORT -> data.data?.let { recognizeImage(it, "截图导入 OCR") }
            REQ_EXPORT_TXT -> {
                val text = pendingTxtDocument
                pendingTxtDocument = null
                val uri = data.data
                if (text != null && uri != null) runCatching {
                    contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ?: error("无法创建 TXT")
                }.onSuccess { toast("已保存 TXT 文档（明文）") }
                 .onFailure { toast("导出失败：${it.localizedMessage}") }
            }
            REQ_PROJECTION -> {
                val intent = Intent(this, CaptureService::class.java).apply {
                    action = CaptureService.ACTION_START
                    putExtra(CaptureService.EXTRA_CODE, resultCode)
                    putExtra(CaptureService.EXTRA_DATA, data)
                }
                try {
                    startForegroundService(intent)
                    toast("采集已申请启动，切换到需要阅读的 APP")
                    frame.postDelayed({ if (page == "home") showPage("home") }, 450)
                } catch (e: Exception) { toast("无法开启：${e.localizedMessage}") }
            }
            REQ_WECHAT_OCR -> {
                val name = pendingWechatOcrChat
                pendingWechatOcrChat = ""
                if (name.isNotBlank()) {
                    val session = getSharedPreferences("spider_session", MODE_PRIVATE)
                    session.edit().putLong("id", System.currentTimeMillis())
                        .putString("mode", "wechat_ocr").putString("chat", name)
                        .putLong("expiry", System.currentTimeMillis() + 5 * 60_000L)
                        .putBoolean("paused", false).putInt("lines", 0)
                        .putString("status", "已授权录屏，等待微信聊天画面").apply()
                    val start = Intent(this, WeChatOcrService::class.java).apply {
                        action = WeChatOcrService.ACTION_START
                        putExtra(WeChatOcrService.EXTRA_CODE, resultCode)
                        putExtra(WeChatOcrService.EXTRA_DATA, data)
                        putExtra(WeChatOcrService.EXTRA_CHAT, name)
                    }
                    OcrLifecycleLog.reset(this)
                    OcrLifecycleLog.note(this, "录屏授权成功；启动微信 OCR 前台服务")
                    val d = getSharedPreferences("ocr_startup_diagnostics", MODE_PRIVATE)
                    d.edit().putString("phase", "系统已授权，正在交给 OCR 服务")
                        .putString("reason", "").putLong("updated_at", System.currentTimeMillis()).apply()
                    runCatching { startForegroundService(start) }
                        .onSuccess { toast("OCR 蜘蛛已申请启动：请回到目标微信聊天并观察悬浮窗") }
                        .onFailure {
                            d.edit().putString("phase", "前台服务启动调用失败")
                                .putString("reason", it.javaClass.simpleName)
                                .putLong("updated_at", System.currentTimeMillis()).apply()
                            session.edit().putLong("id", 0L).apply()
                            toast("启动失败：${it.javaClass.simpleName}")
                        }
                    frame.postDelayed({ if (page == "wechat") showPage(page) }, 500L)
                }
            }
            REQ_WECHAT_IMAGE -> receiveChatImages(data)
            REQ_WECHAT_IMPORT -> data.data?.let { importWeChatBackup(it) }
            REQ_FRAGMENT_IMPORT -> data.data?.let { importFragmentBackup(it) }
            REQ_FRAGMENT_EXPORT -> {
                val chars = fragmentExportPassword
                fragmentExportPassword = null
                if (chars != null) {
                    val uri = data.data
                    Thread {
                        val result = runCatching {
                            require(uri != null) { "没有选择文件" }
                            val payload = db.exportEncrypted(chars)
                            contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                                ?: error("无法写入文件")
                        }
                        chars.fill('\u0000')
                        mainHandler.post { result.onSuccess { toast("加密知识库备份已写入，请验证能恢复") }
                            .onFailure { toast("导出失败：${it.localizedMessage}") } }
                    }.start()
                }
            }
            REQ_WECHAT_EXPORT -> {
                val chars = privateExportPassword
                privateExportPassword = null
                if (chars != null) {
                    val uri = data.data
                    Thread {
                        val result = runCatching {
                            require(uri != null) { "未选择文件" }
                            val payload = wechatDb.exportEncrypted(chars)
                            contentResolver.openOutputStream(uri)?.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                                ?: error("无法写入备份文件")
                        }
                        chars.fill('\u0000')
                        mainHandler.post { result.onSuccess { toast("已生成加密备份，请检查文件并验证恢复") }
                            .onFailure { toast("备份失败：${it.localizedMessage}") } }
                    }.start()
                }
            }

        }
    }

    override fun onDestroy() {
        privateExportPassword?.fill('\u0000')
        fragmentExportPassword?.fill('\u0000')
        if (::wechatDb.isInitialized) wechatDb.close()
        if (::chatBatchDb.isInitialized) chatBatchDb.close()
        if (::changeJournal.isInitialized) changeJournal.close()
        if (::db.isInitialized) db.close()
        if (::agentChatDb.isInitialized) agentChatDb.close()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val EXTRA_SPIDER_SHORTCUT = "ounan_spider_shortcut"
        const val REQ_EXPORT_TXT = 29
        const val REQ_IMPORT = 21
        const val REQ_PROJECTION = 22
        const val REQ_FRAGMENT_EXPORT = 23
        const val REQ_FRAGMENT_IMPORT = 27
        const val REQ_WECHAT_IMAGE = 24
        const val REQ_WECHAT_EXPORT = 25
        const val REQ_WECHAT_IMPORT = 26
        const val REQ_WECHAT_OCR = 31
        @Volatile var visible = false
    }
}
