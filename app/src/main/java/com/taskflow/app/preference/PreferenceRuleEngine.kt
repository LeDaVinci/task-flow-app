package com.taskflow.app.preference

import kotlin.math.pow

class PreferenceRuleEngine {
    fun calculate(events: List<QuestInteraction>, now: Long): PreferenceWeights {
        val topics = mutableMapOf<PreferenceTopic, MutableList<Double>>()
        val durations = mutableMapOf<Int, MutableList<Double>>()
        val intensities = mutableMapOf<String, MutableList<Double>>()
        events.groupBy { it.questId }.values.forEach { history ->
            val last = history.maxBy { it.occurredAt }
            val kinds = history.map { it.kind }.toSet()
            val score = when {
                InteractionKind.COMPLETED in kinds -> 3.0
                InteractionKind.ARCHIVED in kinds -> -0.75
                InteractionKind.REROLLED in kinds -> -0.5
                InteractionKind.EXPIRED in kinds -> 0.0
                InteractionKind.STARTED in kinds -> 0.5
                else -> 0.0
            }
            val decay = 0.5.pow((now - last.occurredAt).coerceAtLeast(0) / (14.0 * DAY))
            if (last.topic != PreferenceTopic.UNKNOWN) topics.getOrPut(last.topic) { mutableListOf() }.add(score * decay)
            val completionAt = history.firstOrNull { it.kind == InteractionKind.COMPLETED }?.occurredAt
            val timedCompletion = completionAt != null && history.any {
                it.kind == InteractionKind.TIMER_FINISHED && it.occurredAt <= completionAt
            }
            durations.getOrPut(last.durationMinutes) { mutableListOf() }.add((score + if (timedCompletion) 0.25 else 0.0) * decay)
            intensities.getOrPut(last.intensity) { mutableListOf() }.add(score * decay)
        }
        // Include exposures in the denominator and smooth sparse observations.
        fun <T> average(map: Map<T, List<Double>>) = map.mapValues { (_, values) -> values.sum() / (values.size + 3) }
        return PreferenceWeights(average(topics), average(durations), average(intensities))
    }

    companion object { const val DAY = 86_400_000L }
}
