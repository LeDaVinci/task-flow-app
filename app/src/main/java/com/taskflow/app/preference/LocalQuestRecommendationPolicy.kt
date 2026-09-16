package com.taskflow.app.preference

import com.taskflow.app.data.QuestDuration
import kotlin.random.Random

class LocalQuestRecommendationPolicy(
    private val repository: PreferenceRepository,
    private val rules: PreferenceRuleEngine = PreferenceRuleEngine(),
    private val random: Random = Random.Default,
    private val now: () -> Long = System::currentTimeMillis,
) : QuestRecommendationPolicy {
    override suspend fun recommend(request: RecommendationRequest): QuestRecommendation {
        val state = repository.snapshot()
        val fallback = QuestRecommendation(null, request.durationMinutes?.let(QuestDuration::normalize), request.intensity, null)
        if (!state.enabled) return fallback
        val weights = rules.calculate(state.events, now())
        val accepted = state.accepted?.takeIf { it.expiresAt > now() }
        val candidates = when (request.entry) {
            "CHILL" -> listOf(PreferenceTopic.RECOVERY, PreferenceTopic.CARE, PreferenceTopic.TIDY)
            "BOSS" -> listOf(PreferenceTopic.TIDY, PreferenceTopic.PLANNING, PreferenceTopic.SOCIAL)
            else -> PreferenceTopic.entries.filter { it != PreferenceTopic.UNKNOWN }
        }
        val explicit = !request.userTheme.isNullOrBlank()
        val recentTopics = state.events.filter { it.kind == InteractionKind.GENERATED && it.random }.takeLast(9).map { it.topic }
        var eligible = candidates.filter { topic -> recentTopics.count { it == topic } < 5 }
        val recentCompletions = state.events.filter { it.kind == InteractionKind.COMPLETED && it.random }.takeLast(2)
        if (recentCompletions.size == 2 && recentCompletions.map { it.topic }.distinct().size == 1) {
            eligible = eligible.filter { it != recentCompletions.last().topic }
        }
        if (eligible.isEmpty()) eligible = candidates
        val scores = eligible.associateWith { topic ->
            state.manualTopics[topic]?.takeIf { it != 0 }?.let { if (it > 0) 6.0 else 0.15 }
                ?: (1.0 + (weights.topics[topic] ?: 0.0) + (accepted?.topics?.get(topic) ?: 0) * 0.4).coerceIn(0.15, 4.0)
        }
        val topic = if (explicit) null else if (random.nextDouble() < 0.3) eligible.random(random) else choose(scores)
        val durationScores = QuestDuration.supported.associateWith { minutes ->
            (weights.durations[minutes] ?: 0.0) + if (accepted?.durationMinutes == minutes) 0.4 else 0.0
        }
        val duration = fallback.durationMinutes ?: durationScores.maxBy { it.value }.takeIf { it.value > 0.0 }?.key
        // Boss/chaotic are selected by the user, never promoted by ordinary completion.
        val intensity = request.intensity ?: listOf("chill", "spicy").maxBy {
            (weights.intensities[it] ?: 0.0) + (if (accepted?.intensity == it) 0.4 else 0.0) + if (it == "spicy") 0.01 else 0.0
        }
        val hint = buildList {
            if (topic != null) add("本次选择方向：${topic.label}")
            if (duration != null) add("偏好时长约 $duration 分钟，仅供选任务参考，不要拉长简单任务")
            add("建议强度：$intensity")
            if (accepted?.hintExpiresAt?.let { it > now() } == true && accepted.recentHint != null) add("近期任务线索：${accepted.recentHint}")
        }.joinToString("；")
        return QuestRecommendation(topic, duration, intensity, hint)
    }

    private fun <T> choose(weights: Map<T, Double>): T {
        var cursor = random.nextDouble() * weights.values.sum()
        weights.forEach { (key, weight) -> cursor -= weight; if (cursor <= 0) return key }
        return weights.keys.last()
    }
}
