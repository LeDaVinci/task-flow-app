package com.taskflow.app.preference

import kotlinx.coroutines.flow.StateFlow

enum class PreferenceTopic(val label: String) {
    TIDY("生活整理"), CARE("个人照料"), PLANNING("轻量规划"),
    SOCIAL("轻社交"), RECOVERY("恢复行动"), EXPLORE("微型探索"), UNKNOWN("未分类");

    companion object {
        fun parse(value: String?): PreferenceTopic = entries.firstOrNull { it.name == value } ?: UNKNOWN
    }
}

enum class InteractionKind { GENERATED, STARTED, COMPLETED, REROLLED, ARCHIVED, TIMER_FINISHED, EXPIRED }

data class QuestInteraction(
    val id: String,
    val questId: String,
    val kind: InteractionKind,
    val occurredAt: Long,
    val title: String,
    val topic: PreferenceTopic,
    val durationMinutes: Int,
    val intensity: String,
    val source: String,
    val entry: String,
    val random: Boolean,
    val reaction: String? = null,
    val localHour: Int? = null,
)

data class PreferenceSuggestion(
    val id: String,
    val summary: String,
    val topics: Map<PreferenceTopic, Int>,
    val durationMinutes: Int?,
    val intensity: String?,
    val evidenceQuestIds: List<String>,
    val createdAt: Long,
    val expiresAt: Long,
    val recentHint: String? = null,
    val hintExpiresAt: Long? = null,
)

data class PreferenceState(
    val enabled: Boolean = false,
    val revision: Long = 0,
    val events: List<QuestInteraction> = emptyList(),
    val manualTopics: Map<PreferenceTopic, Int> = emptyMap(),
    val accepted: PreferenceSuggestion? = null,
    val pending: PreferenceSuggestion? = null,
    val reviewedQuestIds: Set<String> = emptySet(),
    val message: String? = null,
) {
    val effectiveQuestIds: Set<String> get() = events.filter { it.kind in TERMINAL_KINDS }.map { it.questId }.toSet()
    val effectiveTaskCount: Int get() = effectiveQuestIds.size
    val canSummarize: Boolean get() = enabled && effectiveTaskCount >= 8
    val shouldInvite: Boolean get() = canSummarize && pending == null && (effectiveQuestIds - reviewedQuestIds).size >= 8

    companion object {
        val TERMINAL_KINDS = setOf(InteractionKind.COMPLETED, InteractionKind.REROLLED, InteractionKind.ARCHIVED)
    }
}

data class PreferenceWeights(
    val topics: Map<PreferenceTopic, Double> = emptyMap(),
    val durations: Map<Int, Double> = emptyMap(),
    val intensities: Map<String, Double> = emptyMap(),
)

data class RecommendationRequest(
    val entry: String,
    val userTheme: String?,
    val intensity: String?,
    val durationMinutes: Int?,
    val fallbackDuration: Int,
)

data class QuestRecommendation(val topic: PreferenceTopic?, val durationMinutes: Int, val intensity: String?, val hint: String?)

interface PreferenceStore {
    fun load(): PreferenceState
    suspend fun save(state: PreferenceState)
}

interface PreferenceInferenceEngine {
    suspend fun infer(events: List<QuestInteraction>, includeReactions: Boolean): PreferenceSuggestion
}

interface PreferenceRepository {
    val state: StateFlow<PreferenceState>
    suspend fun snapshot(): PreferenceState
    suspend fun record(event: QuestInteraction)
    suspend fun setEnabled(enabled: Boolean)
    suspend fun adjust(topic: PreferenceTopic, delta: Int)
    suspend fun generateSuggestion(includeReactions: Boolean)
    suspend fun adopt(id: String)
    suspend fun dismissSuggestion()
    suspend fun clear()
}

interface QuestRecommendationPolicy {
    suspend fun recommend(request: RecommendationRequest): QuestRecommendation
}
