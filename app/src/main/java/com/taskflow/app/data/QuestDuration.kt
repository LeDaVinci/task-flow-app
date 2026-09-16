package com.taskflow.app.data

/** Estimated action time, not a minimum time the user must spend. */
object QuestDuration {
    val supported = 5..30

    fun normalize(minutes: Int): Int = minutes.coerceIn(supported)

    fun parse(value: Any?): Int? {
        val number = value as? Number ?: return null
        val minutes = number.toInt()
        return minutes.takeIf { it in supported && number.toDouble() == it.toDouble() }
    }

    fun rewardText(reward: String, minutes: Int): String =
        reward.replace(Regex("[+＋-]?\\s*\\d+(?:\\.\\d+)?\\s*XP", RegexOption.IGNORE_CASE), "+$minutes XP")
}
