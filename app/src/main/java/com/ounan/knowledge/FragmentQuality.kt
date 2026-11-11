package com.ounan.knowledge

/** Local, deterministic screening. No private text is uploaded to score it. */
internal object FragmentQuality {
    private val chrome = setOf("首页", "返回", "搜索", "我", "我的", "关注", "推荐", "评论", "分享", "转发", "点赞", "更多", "下一页", "上一页", "确定", "取消", "立即下载", "打开", "设置", "登录", "注册", "编辑", "复制", "收藏", "关注作者")
    private val sentence = Regex("[。！？!?；;：:]|[，,].{4,}")
    data class Ranked(val text:String, val score:Int)
    fun score(value:String): Int {
        val t=value.trim().replace(Regex("\\s+")," ")
        if (t.isBlank() || t.length>1600 || t in chrome) return 0
        if (t.matches(Regex("[0-9+.,: /\\-]+"))) return 0
        var result = when {
            t.length<=3 -> 0
            t.length<=8 -> 1
            t.length<=16 -> 2
            t.length<=35 -> 4
            else -> 6
        }
        if (sentence.containsMatchIn(t)) result+=3
        if (t.count { it=='。' || it=='！' || it=='？' || it=='；' } >= 2) result+=2
        if (t.contains("http") || t.contains("关注我") || t.contains("点击查看")) result-=3
        return result.coerceAtLeast(0)
    }
    fun select(items:List<String>):List<Ranked> = items.map { Ranked(it.trim(),score(it)) }
        .filter { it.score>=5 }
        .distinctBy { it.text }
        .take(80)
}
