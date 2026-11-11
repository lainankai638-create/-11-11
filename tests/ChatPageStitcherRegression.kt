package com.ounan.knowledge

fun main() {
    fun same(expected: Any, actual: Any, hint:String) { check(expected == actual) { "$hint expected=$expected actual=$actual" } }
    val s=ChatPageStitcher()
    same(listOf("[左侧] 你好","[右侧] 好的","[左侧] 你在哪","[右侧] 好的"),s.accept(listOf("[左侧] 你好","[右侧] 好的","[左侧] 你在哪","[右侧] 好的")).append,"repeat messages first page")
    same(emptyList<String>(),s.accept(listOf("[左侧] 你好","[右侧] 好的","[左侧] 你在哪","[右侧] 好的")).append,"same page no duplicates")
    val d=s.accept(listOf("[左侧] 你在哪","[右侧] 好的","[左侧] 是的","[右侧] 好的"))
    same(2,d.overlap,"two-line overlap")
    same(listOf("[左侧] 是的","[右侧] 好的"),d.append,"preserve later repeated identical message")
    check(!d.gap)
    same(6,s.totalLines,"accumulated line count")
    val d2=s.accept(listOf("[左侧] 又一个话题","[右侧] 这里是新的一屏"))
    check(d2.gap && s.possibleGaps==1) { "missing overlap must be marked" }
    check(!ChatPageStitcher().accept(emptyList()).gap)
    val k=ChatPageStitcher()
    k.accept(listOf("A","B","C","D","E","F","G","H","I","J"))
    // Overlap of eight lines must not be incorrectly suppressed as 'stationary'.
    val delta=k.accept(listOf("C","D","E","F","G","H","I","J","K","L"))
    same(listOf("K","L"),delta.append,"scroll with 80 percent common")
    val text="采集于：2026年10月29日\n\n人工智能的发展\n正在改变知识获取方式。\n点赞\n欢迎继续阅读。"
    val formatted=DocumentFormatter.knowledge(text)
    check(formatted.contains("【正文】") && formatted.contains("【可能的界面元素") && formatted.contains("采集信息"))
    check(formatted.contains("人工智能的发展正在改变知识获取方式"))
    val repeatedChat=DocumentFormatter.weChat("[左侧·待核对] 好的\n[左侧·待核对] 好的")
    check(repeatedChat.split("好的").size == 3) { "same consecutive messages must be kept in formatted chat" }
    check(OcrTextRules.titleMatches("小迷糊","微信 小迷糊 聊天"))
    println("PASS: overlap, identical messages, stationary, gaps, 80% common, formatter, chat title")
}
