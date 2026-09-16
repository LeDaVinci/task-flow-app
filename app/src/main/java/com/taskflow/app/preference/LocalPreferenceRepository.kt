package com.taskflow.app.preference

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class LocalPreferenceRepository(
    private val store: PreferenceStore,
    private val inference: PreferenceInferenceEngine,
    private val now: () -> Long = System::currentTimeMillis,
) : PreferenceRepository {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(trim(store.load()))
    override val state = mutableState.asStateFlow()
    private var activeRequest: String? = null
    private var needsPruningSave = true

    override suspend fun snapshot(): PreferenceState = lock.withLock {
        val fresh = trim(state.value)
        if (fresh != state.value || needsPruningSave) persist(fresh)
        fresh
    }

    override suspend fun record(event: QuestInteraction) = lock.withLock {
        val current = state.value
        if (!current.enabled || current.events.any { it.id == event.id || (it.questId == event.questId && it.kind == event.kind) }) return@withLock
        persist(trim(current.copy(events = current.events + event)))
    }

    override suspend fun setEnabled(enabled: Boolean) = lock.withLock {
        activeRequest = null
        persist(state.value.copy(enabled = enabled, revision = state.value.revision + 1, pending = null, message = null))
    }

    override suspend fun adjust(topic: PreferenceTopic, delta: Int) = lock.withLock {
        if (!state.value.enabled || topic == PreferenceTopic.UNKNOWN) return@withLock
        activeRequest = null
        val amount = ((state.value.manualTopics[topic] ?: 0) + delta.coerceIn(-1, 1)).coerceIn(-2, 2)
        persist(state.value.copy(manualTopics = state.value.manualTopics + (topic to amount), revision = state.value.revision + 1, pending = null, message = null))
    }

    override suspend fun generateSuggestion(includeReactions: Boolean) {
        val request = UUID.randomUUID().toString()
        val input = lock.withLock {
            val current = trim(state.value)
            if (!current.canSummarize) return
            activeRequest = request
            persist(current.copy(pending = null, message = null))
            current
        }
        try {
            val result = inference.infer(input.events.map { if (includeReactions) it else it.copy(reaction = null) }, includeReactions)
            lock.withLock {
                if (activeRequest != request || !state.value.enabled || input.revision != state.value.revision) return@withLock
                persist(state.value.copy(pending = result, reviewedQuestIds = input.effectiveQuestIds, message = null))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            lock.withLock {
                if (activeRequest == request && input.revision == state.value.revision) {
                    persist(state.value.copy(message = "暂时没整理好，可以稍后再试。已有偏好仍然有效。"))
                }
            }
        } finally {
            lock.withLock { if (activeRequest == request) activeRequest = null }
        }
    }

    override suspend fun adopt(id: String) = lock.withLock {
        val suggestion = state.value.pending?.takeIf { it.id == id && it.expiresAt > now() } ?: return@withLock
        if (!state.value.enabled) return@withLock
        activeRequest = null
        persist(state.value.copy(accepted = suggestion, pending = null, revision = state.value.revision + 1, message = "已采用，将用于下次推荐。"))
    }

    override suspend fun dismissSuggestion() = lock.withLock {
        activeRequest = null
        persist(state.value.copy(pending = null, reviewedQuestIds = state.value.effectiveQuestIds, revision = state.value.revision + 1, message = null))
    }

    override suspend fun clear() = lock.withLock {
        activeRequest = null
        persist(PreferenceState(enabled = state.value.enabled, revision = state.value.revision + 1, message = "记录与偏好已清空，重新均衡推荐。"))
    }

    private suspend fun persist(next: PreferenceState) {
        withContext(NonCancellable) {
            store.save(next)
            mutableState.value = next
            needsPruningSave = false
        }
    }

    private fun trim(value: PreferenceState): PreferenceState {
        val cutoff = now() - 30 * PreferenceRuleEngine.DAY
        val recent = value.events.filter { it.occurredAt >= cutoff }
        val ids = recent.sortedByDescending { it.occurredAt }.map { it.questId }.distinct().take(200).toSet()
        return value.copy(
            events = recent.filter { it.questId in ids },
            accepted = value.accepted?.takeIf { it.expiresAt > now() },
            pending = value.pending?.takeIf { it.expiresAt > now() },
            reviewedQuestIds = value.reviewedQuestIds.intersect(ids),
        )
    }
}
