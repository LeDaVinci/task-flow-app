package com.taskflow.app.data

import androidx.appfunctions.AppFunctionSerializable

@AppFunctionSerializable
data class Quest(
    /** 全局唯一 ID。 */
    val id: String,
    /** 展示给用户和智能体的任务标题。 */
    val title: String,
    /** 任务正文，强调要做的动作。 */
    val description: String,
    /** 任务难度: chill / spicy / chaotic / boss。 */
    val difficulty: String,
    /** 奖励描述。 */
    val reward: String,
    /** 任务状态: active / completed / archived。 */
    val status: String,
    /** 推荐完成时长，单位分钟。 */
    val durationMinutes: Int,
    /** 任务氛围标签。 */
    val vibe: String,
    /** 任务主题。 */
    val theme: String,
    /** 任务玩法。 */
    val mode: String,
    /** 经验值奖励。 */
    val xp: Int,
    /** 任务生成来源。 */
    val source: String,
    /** 创建时间戳。 */
    val createdAt: Long,
    /** 完成时间戳。 */
    val completedAt: Long? = null,
    /** 完成感言。 */
    val reaction: String? = null,
)

@AppFunctionSerializable
data class QuestBoardSummary(
    /** 当前活跃任务数。 */
    val activeCount: Int,
    /** 已完成任务数。 */
    val completedCount: Int,
    /** Boss 任务数。 */
    val bossCount: Int,
    /** 当前总经验值。 */
    val totalXp: Int,
    /** 当前活跃任务的标题摘要。 */
    val highlightedTitles: List<String>,
)
