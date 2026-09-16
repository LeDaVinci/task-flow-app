package com.taskflow.app.data

import kotlin.random.Random

enum class QuestTheme(
    val wireName: String,
    val label: String,
) {
    STORY("story", "讲故事"),
    EXERCISE("exercise", "做运动"),
    MEDITATION("meditation", "冥想"),
    OUTDOOR("outdoor", "去户外"),
    WRITING("writing", "写作"),
    DRAWING("drawing", "画画"),
    SOCIAL("social", "和人交流"),
    NATURE("nature", "感受自然"),
    TIDY("tidy", "整理环境"),
    CARE("care", "个人照料"),
    PLANNING("planning", "轻量规划"),
    OBSERVE("observe", "观察记录"),
    ADVENTURE("adventure", "微型冒险"),
    EXPRESSION("expression", "自我表达");

    companion object {
        fun from(raw: String?): QuestTheme? {
            val value = raw?.trim().orEmpty().lowercase()
            return entries.firstOrNull { it.wireName == value } ?: when {
                "故事" in value || "story" in value -> STORY
                "运动" in value || "exercise" in value -> EXERCISE
                "冥想" in value || "meditation" in value -> MEDITATION
                "户外" in value || "outdoor" in value -> OUTDOOR
                "写作" in value || "writing" in value -> WRITING
                "画" in value || "drawing" in value -> DRAWING
                "交流" in value || "社交" in value || "social" in value -> SOCIAL
                "自然" in value || "nature" in value -> NATURE
                "整理" in value || "tidy" in value -> TIDY
                "观察" in value || "observe" in value -> OBSERVE
                "冒险" in value || "adventure" in value -> ADVENTURE
                "表达" in value || "expression" in value -> EXPRESSION
                else -> null
            }
        }
    }
}

enum class QuestMode(
    val wireName: String,
    val label: String,
) {
    DIRECTOR("director", "导演模式"),
    DETECTIVE("detective", "侦探模式"),
    RITUAL("ritual", "仪式模式"),
    SPRINT("sprint", "限时冲刺"),
    TREASURE("treasure", "寻宝模式"),
    SILENT("silent", "静音模式"),
    SIGNAL("signal", "信号模式"),
    BOSS("boss", "Boss 模式"),
}

data class QuestBlueprint(
    val theme: QuestTheme,
    val mode: QuestMode,
    val title: String,
    val description: String,
    val reward: String,
    val difficulty: String,
    val vibe: String,
    val durationMinutes: Int,
    val outputHint: String,
    val successSignal: String,
)

private data class QuestPrototype(
    val theme: QuestTheme,
    val titleCore: String,
    val action: String,
    val outputHint: String,
    val successSignal: String,
    val rewardSeed: String,
    val durationMinutes: Int = 10,
)

class QuestBlueprintPlanner(
    private val random: Random = Random(System.currentTimeMillis()),
) {

    fun plan(
        vibe: String,
        intensity: String,
        durationMinutes: Int?,
        forceBoss: Boolean,
        requestedTheme: String? = null,
        avoidTitles: List<String> = emptyList(),
        preferredDurationMinutes: Int? = null,
    ): QuestBlueprint {
        val normalizedVibe = vibe.ifBlank { "random" }.lowercase()
        val difficulty = if (forceBoss) "boss" else normalizeDifficulty(intensity)
        val theme = QuestTheme.from(requestedTheme) ?: chooseTheme(normalizedVibe, difficulty)
        val mode = chooseMode(theme, difficulty)
        val candidates = prototypes.filter { it.theme == theme }
        // Prefer an appropriately sized action, never pad a short action to match a preference.
        val preferred = preferredDurationMinutes?.let { minutes ->
            val distance = candidates.minOf { kotlin.math.abs(it.durationMinutes - minutes) }
            candidates.filter { kotlin.math.abs(it.durationMinutes - minutes) == distance }
        } ?: candidates
        val prototype = preferred.random(random)
        val minutes = durationMinutes?.let(QuestDuration::normalize) ?: prototype.durationMinutes
        val title = uniqueTitle(composeTitle(prototype, mode, difficulty), avoidTitles)
        return QuestBlueprint(
            theme = theme,
            mode = mode,
            title = title,
            description = composeDescription(prototype, mode, minutes),
            reward = composeReward(prototype, difficulty, minutes),
            difficulty = difficulty,
            vibe = normalizedVibe,
            durationMinutes = minutes,
            outputHint = prototype.outputHint,
            successSignal = prototype.successSignal,
        )
    }

    private fun chooseTheme(
        vibe: String,
        difficulty: String,
    ): QuestTheme {
        val pool = when {
            difficulty == "boss" -> listOf(QuestTheme.TIDY, QuestTheme.STORY, QuestTheme.EXERCISE, QuestTheme.SOCIAL)
            "chill" in vibe -> listOf(QuestTheme.MEDITATION, QuestTheme.NATURE, QuestTheme.OBSERVE, QuestTheme.TIDY)
            "chaos" in vibe || "wild" in vibe -> listOf(QuestTheme.ADVENTURE, QuestTheme.STORY, QuestTheme.OUTDOOR, QuestTheme.EXPRESSION)
            "social" in vibe -> listOf(QuestTheme.SOCIAL, QuestTheme.STORY, QuestTheme.EXPRESSION)
            else -> QuestTheme.entries
        }
        return pool.random(random)
    }

    private fun chooseMode(
        theme: QuestTheme,
        difficulty: String,
    ): QuestMode {
        if (difficulty == "boss") return QuestMode.BOSS
        val pool = when (theme) {
            QuestTheme.STORY -> listOf(QuestMode.DIRECTOR, QuestMode.SPRINT, QuestMode.RITUAL)
            QuestTheme.EXERCISE -> listOf(QuestMode.SPRINT, QuestMode.BOSS, QuestMode.SILENT)
            QuestTheme.MEDITATION -> listOf(QuestMode.RITUAL, QuestMode.SILENT, QuestMode.DETECTIVE)
            QuestTheme.OUTDOOR -> listOf(QuestMode.DETECTIVE, QuestMode.TREASURE, QuestMode.SPRINT)
            QuestTheme.WRITING -> listOf(QuestMode.DIRECTOR, QuestMode.RITUAL, QuestMode.SPRINT)
            QuestTheme.DRAWING -> listOf(QuestMode.DIRECTOR, QuestMode.SILENT, QuestMode.TREASURE)
            QuestTheme.SOCIAL -> listOf(QuestMode.SIGNAL, QuestMode.DIRECTOR, QuestMode.SPRINT)
            QuestTheme.NATURE -> listOf(QuestMode.DETECTIVE, QuestMode.RITUAL, QuestMode.TREASURE)
            QuestTheme.TIDY -> listOf(QuestMode.SPRINT, QuestMode.TREASURE, QuestMode.RITUAL)
            QuestTheme.CARE -> listOf(QuestMode.RITUAL, QuestMode.SILENT)
            QuestTheme.PLANNING -> listOf(QuestMode.DIRECTOR, QuestMode.SILENT)
            QuestTheme.OBSERVE -> listOf(QuestMode.DETECTIVE, QuestMode.SILENT, QuestMode.DIRECTOR)
            QuestTheme.ADVENTURE -> listOf(QuestMode.TREASURE, QuestMode.DETECTIVE, QuestMode.SPRINT)
            QuestTheme.EXPRESSION -> listOf(QuestMode.DIRECTOR, QuestMode.SIGNAL, QuestMode.RITUAL)
        }
        return pool.random(random)
    }

    private fun composeTitle(
        prototype: QuestPrototype,
        mode: QuestMode,
        difficulty: String,
    ): String {
        val modePrefix = when (mode) {
            QuestMode.DIRECTOR -> "今日预告片"
            QuestMode.DETECTIVE -> "线索追踪"
            QuestMode.RITUAL -> "小型仪式"
            QuestMode.SPRINT -> "限时回合"
            QuestMode.TREASURE -> "隐藏战利品"
            QuestMode.SILENT -> "静默任务"
            QuestMode.SIGNAL -> "单句投递"
            QuestMode.BOSS -> "最终回合"
        }
        val bossPrefix = if (difficulty == "boss") "Boss " else ""
        return "$bossPrefix$modePrefix · ${prototype.titleCore}"
    }

    private fun composeDescription(
        prototype: QuestPrototype,
        mode: QuestMode,
        durationMinutes: Int,
    ): String {
        val tail = when (mode) {
            QuestMode.DIRECTOR -> "把这 $durationMinutes 分钟当成一段短片，最后在${prototype.outputHint}留下结果。"
            QuestMode.DETECTIVE -> "只盯一个线索推进，最后在${prototype.outputHint}记下发现。"
            QuestMode.RITUAL -> "开始前先深呼吸一次，结束后在${prototype.outputHint}留一句确认。"
            QuestMode.SPRINT -> "全程只给自己 $durationMinutes 分钟，时间到立刻收手。"
            QuestMode.TREASURE -> "把找到的东西当成战利品，最后在${prototype.outputHint}留下证据。"
            QuestMode.SILENT -> "全程不切屏不说话，结束后在${prototype.outputHint}留下结果。"
            QuestMode.SIGNAL -> "动作完成后，向一个人发出一个具体信号并保留痕迹。"
            QuestMode.BOSS -> "中途不允许改目标，做完后在${prototype.outputHint}写下这轮结果。"
        }
        return "${prototype.action}。$tail"
    }

    private fun composeReward(
        prototype: QuestPrototype,
        difficulty: String,
        durationMinutes: Int,
    ): String = (if (difficulty == "boss") "Boss 宝箱: " else "") + "${prototype.rewardSeed} +$durationMinutes XP"

    private fun uniqueTitle(
        raw: String,
        avoidTitles: List<String>,
    ): String {
        val clean = raw.trim()
        if (avoidTitles.none { it == clean }) return clean
        val suffixes = listOf("夜版", "侧录", "回放", "续章", "二周目")
        return suffixes
            .map { "$clean $it" }
            .firstOrNull { candidate -> avoidTitles.none { it == candidate } }
            ?: "$clean ${random.nextInt(2, 10)}"
    }

    private fun normalizeDifficulty(intensity: String): String = when (intensity.lowercase()) {
        "chill" -> "chill"
        "chaotic" -> "chaotic"
        else -> "spicy"
    }

    private val prototypes = listOf(
        QuestPrototype(QuestTheme.CARE, "舒适补给", "给自己准备一杯水，洗脸并舒展肩颈，留几分钟安静坐着", "当下感受", "身体感觉更舒适", "舒适值"),
        QuestPrototype(QuestTheme.CARE, "明早照料", "准备明天要穿的衣服和随身用品，给早晨少留一点忙乱", "门口或床边", "明早用品已备好", "安心值"),
        QuestPrototype(QuestTheme.PLANNING, "生活减负", "列下最近挂心的生活小事，只选出一件明天可以推进的事", "纸或备忘录", "明确一件下一步行动", "清晰度"),
        QuestPrototype(QuestTheme.PLANNING, "晚间小安排", "梳理明天的吃饭、出行与休息安排，给自己留一段空闲", "纸或备忘录", "一份简短的生活安排", "从容值", 15),
        QuestPrototype(QuestTheme.STORY, "给今天起片名", "给今天起一个片名，再写三句预告词", "备忘录", "片名加三句预告词", "创意值"),
        QuestPrototype(QuestTheme.STORY, "倒叙回放", "先写今天最晚发生的一件事，再倒着补两句前情", "备忘录", "三句倒叙文本", "叙事值"),
        QuestPrototype(QuestTheme.STORY, "旁白试音", "录一段 30 秒旁白，把今天讲成一个刚开场的故事", "语音备忘录", "一段 30 秒录音", "存在感", 5),
        QuestPrototype(QuestTheme.EXERCISE, "楼道快走", "离开座位快走一段路，再做一轮简单拉伸", "身体感受记录", "一条完成记录", "体能槽"),
        QuestPrototype(QuestTheme.EXERCISE, "桌边激活", "做 20 次开合跳或深蹲，然后喝一口水", "备忘录", "一次动作完成记录", "热启动值", 5),
        QuestPrototype(QuestTheme.EXERCISE, "楼梯回合", "上下楼梯或原地踏步一轮，直到呼吸明显变快", "备忘录", "一条体感记录", "行动值"),
        QuestPrototype(QuestTheme.MEDITATION, "呼吸计时", "闭眼专注呼吸，默数 20 次吸气和呼气", "备忘录", "一句当前状态", "平静值", 10),
        QuestPrototype(QuestTheme.MEDITATION, "身体扫描", "从肩膀到脚趾缓慢扫描一遍身体紧张点", "备忘录", "一个最紧的部位", "稳定值"),
        QuestPrototype(QuestTheme.MEDITATION, "环境静听", "什么都不做，只听周围的 3 种声音", "备忘录", "三种声音名字", "降噪值", 5),
        QuestPrototype(QuestTheme.OUTDOOR, "楼下巡航", "下楼走一圈，找一个今天以前没认真看过的角落", "照片或备忘录", "一张照片或一句记录", "探索值", 15),
        QuestPrototype(QuestTheme.OUTDOOR, "短途脱离", "离开当前房间或楼层，给自己一个真正的外部视角", "备忘录", "一句外部观察", "脱线值"),
        QuestPrototype(QuestTheme.OUTDOOR, "新角度取景", "出门后找一个平时不会停下来的位置站 1 分钟", "照片", "一张新角度照片", "镜头值"),
        QuestPrototype(QuestTheme.WRITING, "三句复盘", "写三句关于今天的句子，分别是事实、情绪、下一步", "备忘录", "三句文本", "清晰度"),
        QuestPrototype(QuestTheme.WRITING, "一句日记", "只写一句今天最值得留下来的句子", "备忘录", "一句日记", "记录值", 5),
        QuestPrototype(QuestTheme.WRITING, "清单写作", "围绕一个困扰点写一个 5 项小清单", "备忘录", "五项清单", "秩序值"),
        QuestPrototype(QuestTheme.DRAWING, "桌面速写", "随手画眼前一个物体的轮廓，不许擦改", "纸或备忘录", "一张速写", "手感值"),
        QuestPrototype(QuestTheme.DRAWING, "天气线条", "用几条线画出你感觉到的今天气氛", "纸或备忘录", "一张线条图", "情绪墨水"),
        QuestPrototype(QuestTheme.DRAWING, "盲画挑战", "盯着一个物体看，尽量不低头画它的轮廓", "纸", "一张盲画", "观察值"),
        QuestPrototype(QuestTheme.SOCIAL, "具体问候", "给一个人发一句问候，只能提一个具体细节", "聊天窗口", "一条已发送消息", "连接值", 5),
        QuestPrototype(QuestTheme.SOCIAL, "单句夸奖", "给一个人发一句不求回复的夸奖", "聊天窗口", "一条已发送消息", "勇气值", 5),
        QuestPrototype(QuestTheme.SOCIAL, "重新开线", "给一个很久没联系的人发一句轻量近况", "聊天窗口", "一条已发送消息", "社交回血"),
        QuestPrototype(QuestTheme.NATURE, "天空取样", "看天空或窗外 2 分钟，记下颜色和形状变化", "备忘录", "两个观察词", "天气值", 5),
        QuestPrototype(QuestTheme.NATURE, "风的证词", "找一个有风或光线变化的位置站一会儿", "备忘录", "一句自然感受", "感官值", 5),
        QuestPrototype(QuestTheme.NATURE, "自然采样", "收集今天听到的 3 种自然声或环境声", "备忘录", "三种声音名字", "在场感"),
        QuestPrototype(QuestTheme.TIDY, "角落排雷", "选桌面或房间一个最乱的小角，清掉 5 个无效物品", "现场照片或备忘录", "一个清理结果", "秩序值"),
        QuestPrototype(QuestTheme.TIDY, "抽屉突击", "随机打开一个抽屉，只处理最上层那一层", "现场照片或备忘录", "一个处理前后差异", "空间值", 15),
        QuestPrototype(QuestTheme.TIDY, "旧物判定", "找出 3 个已经不再服务今天的东西", "备忘录", "三件物品名字", "背包空间"),
        QuestPrototype(QuestTheme.OBSERVE, "异常清单", "观察身边 3 个平时会忽略的细节", "备忘录", "三个细节", "洞察值"),
        QuestPrototype(QuestTheme.OBSERVE, "颜色采样", "选一个颜色，找出周围 3 个同色物体", "备忘录或照片", "三个同色目标", "取景值"),
        QuestPrototype(QuestTheme.OBSERVE, "动线记录", "观察自己从起身到坐下会经过哪些固定动作", "备忘录", "三步动线记录", "自察值"),
        QuestPrototype(QuestTheme.ADVENTURE, "陌生绕路", "给自己一次 10 分钟的小绕路，去一个平时不会停下来的地方", "照片或备忘录", "一张证据或一句记录", "冒险值"),
        QuestPrototype(QuestTheme.ADVENTURE, "随机角落突击", "在房间里随机选一个角落，只在那里做一件推进动作", "备忘录", "一个推进结果", "混沌值"),
        QuestPrototype(QuestTheme.ADVENTURE, "低成本寻宝", "找一个你三个月没碰过但一直没扔的东西", "照片或备忘录", "一件旧物和一句原因", "记忆碎片"),
        QuestPrototype(QuestTheme.EXPRESSION, "一句宣言", "写一句今天最真实的态度，不修饰不解释", "备忘录", "一句宣言", "表达值", 5),
        QuestPrototype(QuestTheme.EXPRESSION, "30 秒录音", "录 30 秒语音，说出你现在最想推进的事", "语音备忘录", "一段录音", "声音值", 5),
        QuestPrototype(QuestTheme.EXPRESSION, "情绪标记", "给现在的情绪起一个名字，再补一句原因", "备忘录", "一个情绪名和一句原因", "命名值", 5),
    )
}
