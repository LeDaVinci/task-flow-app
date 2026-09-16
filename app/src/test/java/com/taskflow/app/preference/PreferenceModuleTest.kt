package com.taskflow.app.preference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PreferenceModuleTest {
    private val now = 100 * PreferenceRuleEngine.DAY
    private fun event(id: String, kind: InteractionKind, topic: PreferenceTopic = PreferenceTopic.TIDY, at: Long = now) =
        QuestInteraction("$id:${kind.name}", id, kind, at, "整理桌面", topic, 15, "chill", "TEMPLATE", "CHILL", true)
    private fun suggestion() = PreferenceSuggestion("suggestion", "近期适合生活整理", mapOf(PreferenceTopic.TIDY to 1),
        15, "chill", listOf("0"), now, now + 30 * PreferenceRuleEngine.DAY)
    private class MemoryStore(var saved: PreferenceState = PreferenceState()) : PreferenceStore {
        override fun load() = saved
        override suspend fun save(state: PreferenceState) { saved = state }
    }
    private val inference = object : PreferenceInferenceEngine {
        override suspend fun infer(events: List<QuestInteraction>, includeReactions: Boolean) = suggestion()
    }
    private fun repository(store: MemoryStore = MemoryStore()) = LocalPreferenceRepository(store, inference) { now }

    @Test fun disabledLearningDoesNotRecordAndDuplicateCompletionCountsOnce() = runBlocking {
        val repo = repository()
        repo.record(event("one", InteractionKind.GENERATED))
        assertTrue(repo.state.value.events.isEmpty())
        repo.setEnabled(true)
        repo.record(event("one", InteractionKind.COMPLETED))
        repo.record(event("one", InteractionKind.COMPLETED).copy(id = "different-delivery"))
        assertEquals(1, repo.state.value.events.size)
        assertEquals(1, repo.state.value.effectiveTaskCount)
    }

    @Test fun eightTasksAreRequiredNotEightActions() = runBlocking {
        val repo = repository()
        repo.setEnabled(true)
        InteractionKind.entries.forEach { repo.record(event("one", it)) }
        assertFalse(repo.state.value.canSummarize)
        repeat(7) { repo.record(event("task$it", InteractionKind.COMPLETED)) }
        assertTrue(repo.state.value.canSummarize)
        repo.generateSuggestion(false)
        assertNull(repo.state.value.accepted)
        assertNotNull(repo.state.value.pending)
        repo.adopt("wrong-id")
        assertNull(repo.state.value.accepted)
        repo.adopt("suggestion")
        assertEquals("suggestion", repo.state.value.accepted?.id)
    }

    @Test fun completionReplacesStartScoreAndExpiryIsNeutral() {
        val rules = PreferenceRuleEngine()
        val done = event("one", InteractionKind.COMPLETED)
        assertEquals(rules.calculate(listOf(done), now), rules.calculate(listOf(event("one", InteractionKind.STARTED), done), now))
        val expired = rules.calculate(listOf(event("one", InteractionKind.STARTED), event("one", InteractionKind.EXPIRED)), now)
        assertEquals(0.0, expired.topics[PreferenceTopic.TIDY]!!, 0.001)
    }

    @Test fun oldBehaviorHasLessInfluenceAndExposureIsNormalized() {
        val rules = PreferenceRuleEngine()
        val positive = rules.calculate(listOf(event("one", InteractionKind.COMPLETED)), now)
        val old = rules.calculate(listOf(event("one", InteractionKind.COMPLETED, at = now - 14 * PreferenceRuleEngine.DAY)), now)
        assertEquals(positive.topics[PreferenceTopic.TIDY]!! / 2, old.topics[PreferenceTopic.TIDY]!!, 0.001)
        val exposed = rules.calculate(listOf(event("one", InteractionKind.COMPLETED)) + (1..10).map { event("shown$it", InteractionKind.GENERATED) }, now)
        assertTrue(exposed.topics[PreferenceTopic.TIDY]!! < positive.topics[PreferenceTopic.TIDY]!!)
    }

    @Test fun clearDuringInferenceCannotRestoreOldData() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val deferredEngine = object : PreferenceInferenceEngine {
            override suspend fun infer(events: List<QuestInteraction>, includeReactions: Boolean): PreferenceSuggestion {
                started.complete(Unit)
                finish.await()
                return suggestion()
            }
        }
        val repo = LocalPreferenceRepository(MemoryStore(), deferredEngine) { now }
        repo.setEnabled(true)
        repeat(8) { repo.record(event("$it", InteractionKind.COMPLETED)) }
        val job = launch { repo.generateSuggestion(false) }
        started.await()
        repo.clear()
        finish.complete(Unit)
        job.join()
        assertTrue(repo.state.value.events.isEmpty())
        assertNull(repo.state.value.pending)
        assertNull(repo.state.value.accepted)
    }

    @Test fun explicitThemeAndDurationArePreservedAndBossIsNeverInferred() = runBlocking {
        val repo = repository()
        repo.setEnabled(true)
        repeat(8) { repo.record(event("$it", InteractionKind.COMPLETED).copy(intensity = "boss")) }
        val policy = LocalQuestRecommendationPolicy(repo, random = Random(1), now = { now })
        val result = policy.recommend(RecommendationRequest("AI", "收拾衣柜", null, 30, 15))
        assertNull(result.topic)
        assertEquals(30, result.durationMinutes)
        assertTrue(result.intensity in listOf("chill", "spicy"))
    }

    @Test fun recentRandomThemeCapAppliesButExplicitChoicesAreExempt() = runBlocking {
        val repo = repository()
        repo.setEnabled(true)
        repeat(5) { repo.record(event("$it", InteractionKind.GENERATED)) }
        repo.adjust(PreferenceTopic.TIDY, 1)
        val policy = LocalQuestRecommendationPolicy(repo, random = Random(0), now = { now })
        repeat(50) {
            assertNotEquals(PreferenceTopic.TIDY, policy.recommend(RecommendationRequest("AI", null, null, null, 15)).topic)
        }
    }

    @Test fun expiredRecordsArePrunedAndOffUsesDefaultRecommendation() = runBlocking {
        val store = MemoryStore(PreferenceState(enabled = true, events = listOf(event("old", InteractionKind.COMPLETED, at = now - 31 * PreferenceRuleEngine.DAY))))
        val repo = repository(store)
        assertTrue(repo.snapshot().events.isEmpty())
        repo.adjust(PreferenceTopic.TIDY, 1)
        repo.setEnabled(false)
        val result = LocalQuestRecommendationPolicy(repo).recommend(RecommendationRequest("CHAOS", null, "chaotic", null, 30))
        assertNull(result.topic)
        assertEquals(30, result.durationMinutes)
        assertEquals("chaotic", result.intensity)
    }

    @Test fun reactionsAreRemovedBeforeInferenceUnlessSelected() = runBlocking {
        var receivedReaction: String? = "uninitialized"
        val engine = object : PreferenceInferenceEngine {
            override suspend fun infer(events: List<QuestInteraction>, includeReactions: Boolean): PreferenceSuggestion {
                receivedReaction = events.first().reaction
                return suggestion()
            }
        }
        val repo = LocalPreferenceRepository(MemoryStore(), engine) { now }
        repo.setEnabled(true)
        repeat(8) { repo.record(event("$it", InteractionKind.COMPLETED).copy(reaction = "今天很累")) }
        repo.generateSuggestion(false)
        assertNull(receivedReaction)
        repo.generateSuggestion(true)
        assertEquals("今天很累", receivedReaction)
    }

    @Test fun preferencesRestoreAndHistoryStaysBoundedWithoutSuppressingFutureInvitations() = runBlocking {
        val store = MemoryStore()
        val repo = repository(store)
        repo.setEnabled(true)
        repeat(200) { repo.record(event("$it", InteractionKind.COMPLETED, at = now - 1000 + it)) }
        repo.generateSuggestion(false)
        repo.adopt("suggestion")
        repo.adjust(PreferenceTopic.CARE, 1)
        val restored = repository(store)
        assertEquals("suggestion", restored.state.value.accepted?.id)
        assertEquals(1, restored.state.value.manualTopics[PreferenceTopic.CARE])
        repeat(8) { restored.record(event("new$it", InteractionKind.COMPLETED)) }
        assertEquals(200, restored.state.value.effectiveTaskCount)
        assertTrue(restored.state.value.shouldInvite)
    }

    @Test fun disablingDuringInferenceDiscardsSuggestionAndStopsCollection() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val engine = object : PreferenceInferenceEngine {
            override suspend fun infer(events: List<QuestInteraction>, includeReactions: Boolean): PreferenceSuggestion {
                started.complete(Unit)
                finish.await()
                return suggestion()
            }
        }
        val repo = LocalPreferenceRepository(MemoryStore(), engine) { now }
        repo.setEnabled(true)
        repeat(8) { repo.record(event("$it", InteractionKind.COMPLETED)) }
        val job = launch { repo.generateSuggestion(false) }
        started.await()
        repo.setEnabled(false)
        repo.record(event("ignored", InteractionKind.COMPLETED))
        finish.complete(Unit)
        job.join()
        assertNull(repo.state.value.pending)
        assertEquals(8, repo.state.value.effectiveTaskCount)
    }
}
