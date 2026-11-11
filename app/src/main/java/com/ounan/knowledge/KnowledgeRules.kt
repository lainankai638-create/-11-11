package com.ounan.knowledge

/** Transparent local keyword rules. No large-language-model inference is claimed in v0.1. */
object KnowledgeRules {
    private val categories = linkedMapOf(
        "历史与传统文化" to listOf("周易", "易经", "阴阳", "八卦", "六爻", "伏羲", "文王", "天干", "地支", "甲骨", "史记", "历史", "儒家", "道家"),
        "哲学与思想" to listOf("哲学", "思想", "宇宙观", "方法论", "认知", "辩证", "逻辑", "伦理", "存在主义"),
        "人工智能" to listOf("AI", "人工智能", "大模型", "LLM", "机器人", "智能体", "AGI", "算力", "模型"),
        "科技与软件" to listOf("Android", "安卓", "操作系统", "开源", "Github", "芯片", "软件", "程序", "代码"),
        "国际局势" to listOf("俄罗斯", "乌克兰", "北约", "美国", "欧盟", "台海", "战争", "军事", "国际"),
        "财经商业" to listOf("经济", "产业", "商业", "投资", "利润", "收入", "市场", "财经", "成本"),
        "社会与生活" to listOf("生活", "社会", "人口", "医疗", "养老", "就业", "教育"),
        "个人研究" to listOf("研究", "假设", "证据", "验证", "笔记", "思考", "观点")
    )
    /** Local auto-labels, ordered by weighted evidence; no network or GPT needed. */
    fun tags(text: String): String {
        // "AI解析" is a platform UI heading, not reliable evidence that the article is about AI.
        val source = text.take(12000).replace(Regex("(?i)AI\\s*解析"), "")
        val weighted = categories.map { (category, keys) ->
            val matches = keys.count { key ->
                if (key.all { it.code < 128 } && key.length <= 3) {
                    Regex("(?i)(?<![a-z0-9])" + Regex.escape(key) + "(?![a-z0-9])").containsMatchIn(source)
                } else source.contains(key, ignoreCase = true)
            }
            category to matches
        }.filter { it.second > 0 }.sortedByDescending { it.second }
        return if (weighted.isEmpty()) "待分类" else weighted.take(3).joinToString("、") { it.first }
    }
    fun summary(text: String): String = text.replace(Regex("\\s+"), " ").take(160)
    fun categoryCounts(notes: List<KnowledgeDb.Note>): List<Pair<String, Int>> = categories.keys.map { key ->
        key to notes.count { it.tags.contains(key) }
    }.filter { it.second > 0 }.sortedByDescending { it.second }

    fun related(target: KnowledgeDb.Note, others: List<KnowledgeDb.Note>): List<KnowledgeDb.Note> {
        val keys = target.tags.split("、").filter { it != "待分类" && it.isNotBlank() }
        return others.filter { it.id != target.id && keys.any { k -> it.tags.contains(k) } }.take(5)
    }
}
