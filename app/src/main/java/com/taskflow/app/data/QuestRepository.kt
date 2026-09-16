package com.taskflow.app.data

import com.taskflow.app.ai.ApiGenerationResult
import com.taskflow.app.ai.LocalGenerationResult
import com.taskflow.app.ai.LocalModelStatus
import com.taskflow.app.ai.LocalModelTaskGenerator
import com.taskflow.app.ai.OpenAiApiTaskGenerator
import com.taskflow.app.logging.AppLog
import com.taskflow.app.preference.*
import com.taskflow.app.timer.QuestTimerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.UUID

data class QuestCreationResult(val quest: Quest, val source: QuestSource, val note: String? = null)
enum class QuestSource { TEMPLATE, LOCAL_MODEL, API }

class QuestRepository(
    private val localModelTaskGenerator: LocalModelTaskGenerator,
    private val apiTaskGenerator: OpenAiApiTaskGenerator,
    private val stateStore: QuestStateStore,
    private val preferences: PreferenceRepository,
    private val recommendations: QuestRecommendationPolicy,
    private val timer: QuestTimerController,
    private val planner: QuestBlueprintPlanner = QuestBlueprintPlanner(),
) {
    private val lock = Mutex()
    private var totalXp = stateStore.loadTotalXp()
    private val _quests = MutableStateFlow(listOfNotNull(stateStore.loadActiveQuest()))
    val quests: StateFlow<List<Quest>> = _quests.asStateFlow()

    suspend fun rollQuest(
        vibe: String = "random", intensity: String = "spicy", durationMinutes: Int? = null,
        forceBoss: Boolean = false, theme: String? = null,
    ): Quest = lock.withLock {
        expireLocked()
        activeQuest()?.let { return@withLock it }
        create(templatePlan(vibe, intensity, durationMinutes, forceBoss, theme, entryFor(intensity, forceBoss)), QuestSource.TEMPLATE)
    }

    suspend fun rollQuestWithLocalModel(
        vibe: String = "random", intensity: String = "spicy", durationMinutes: Int? = null,
        forceBoss: Boolean = false, theme: String? = null,
    ): QuestCreationResult = lock.withLock {
        expireLocked()
        activeQuest()?.let { return@withLock QuestCreationResult(it, QuestSource.valueOf(it.source), "已有进行中的支线") }
        val plan = templatePlan(vibe, intensity, durationMinutes, forceBoss, theme, "TEMPLATE")
        when (val result = localModelTaskGenerator.polishBlueprint(plan.blueprint, recentTitles())) {
            is LocalGenerationResult.Success -> {
                val quest = create(plan, QuestSource.LOCAL_MODEL, result.draft.title, result.draft.description, result.draft.reward)
                QuestCreationResult(quest, QuestSource.LOCAL_MODEL)
            }
            is LocalGenerationResult.Fallback -> QuestCreationResult(create(plan, QuestSource.TEMPLATE), QuestSource.TEMPLATE, result.status.message)
        }
    }

    suspend fun rollQuestWithApi(
        vibe: String = "random", intensity: String = "spicy", durationMinutes: Int? = null,
        forceBoss: Boolean = false, theme: String? = null,
    ): QuestCreationResult = lock.withLock {
        expireLocked()
        activeQuest()?.let { return@withLock QuestCreationResult(it, QuestSource.valueOf(it.source), "已有进行中的支线") }
        generateApi(templatePlan(vibe, intensity, durationMinutes, forceBoss, theme, if (forceBoss) "BOSS" else "AI"))
    }

    suspend fun rerollQuest(questId: String): Quest? = lock.withLock {
        expireLocked()
        val original = activeQuest()?.takeIf { it.id == questId } ?: return@withLock null
        val entry = original.generationEntry ?: if (original.source == "API") "AI" else entryFor(original.difficulty, original.difficulty == "boss")
        val plan = templatePlan(
            original.vibe, original.difficulty, null, entry == "BOSS",
            original.requestedTheme, entry,
        )
        if (entry == "AI" || original.source == "API") generateApi(plan, original).quest
        else create(plan, QuestSource.TEMPLATE, replacing = original)
    }

    suspend fun completeQuest(questId: String, reaction: String? = null): Quest? = lock.withLock {
        expireLocked()
        val active = activeQuest()?.takeIf { it.id == questId } ?: return@withLock null
        timer.timerState.value?.takeIf { it.questId == questId && it.endAt <= System.currentTimeMillis() }?.let {
            timer.markFinished(questId)
        }
        val completed = active.copy(status = "completed", completedAt = System.currentTimeMillis(),
            reaction = reaction?.trim()?.takeIf { it.isNotBlank() })
        totalXp += active.xp
        stateStore.saveTotalXp(totalXp)
        stateStore.clearActiveQuest()
        _quests.value = listOf(completed) + _quests.value.filterNot { it.status == "active" }
        timer.clear(questId)
        preferences.recordSafely(completed.interaction(InteractionKind.COMPLETED))
        AppLog.i("QuestRepository", "completed: id=$questId, totalXp=$totalXp")
        completed
    }

    suspend fun archiveQuest(questId: String): Quest? = lock.withLock {
        expireLocked()
        val active = activeQuest()?.takeIf { it.id == questId } ?: return@withLock null
        stateStore.clearActiveQuest()
        _quests.value = _quests.value.filterNot { it.status == "active" }
        timer.clear(questId)
        preferences.recordSafely(active.interaction(InteractionKind.ARCHIVED))
        active.copy(status = "archived")
    }

    suspend fun expireActiveQuestIfNeeded(): Boolean = lock.withLock { expireLocked() }

    suspend fun startQuest(questId: String): Quest? = lock.withLock {
        expireLocked()
        val active = activeQuest()?.takeIf { it.id == questId } ?: return@withLock null
        if (timer.start(active)) active else null
    }

    private suspend fun expireLocked(): Boolean {
        val active = activeQuest() ?: return false
        if (isToday(active.createdAt)) return false
        stateStore.clearActiveQuest()
        _quests.value = _quests.value.filterNot { it.status == "active" }
        timer.clear(active.id)
        preferences.recordSafely(active.interaction(InteractionKind.EXPIRED))
        AppLog.i("QuestRepository", "expired: id=${active.id}")
        return true
    }

    fun findQuest(questId: String): Quest? = _quests.value.firstOrNull { it.id == questId }
    fun listActiveQuests(): List<Quest> = listOfNotNull(activeQuest()?.takeIf { isToday(it.createdAt) })
    fun boardSummary(): QuestBoardSummary = QuestBoardSummary(
        activeCount = listActiveQuests().size,
        completedCount = _quests.value.count { it.status == "completed" },
        bossCount = listActiveQuests().count { it.difficulty == "boss" },
        totalXp = totalXp, highlightedTitles = listActiveQuests().map { it.title },
    )

    suspend fun getLocalModelStatus(): LocalModelStatus = localModelTaskGenerator.checkAvailability()
    suspend fun setLocalModelPath(path: String?): LocalModelStatus = localModelTaskGenerator.setPreferredModelPath(path)

    private data class GenerationPlan(
        val blueprint: QuestBlueprint, val input: String?, val entry: String, val hint: String?,
    )

    private suspend fun templatePlan(
        vibe: String, intensity: String, duration: Int?, boss: Boolean, theme: String?, entry: String,
    ): GenerationPlan {
        val input = theme?.trim()?.takeIf { it.isNotBlank() }
        val fallback = if (boss || intensity == "chaotic") 30 else 15
        val request = RecommendationRequest(entry, input, if (entry == "AI" && intensity == "spicy") null else intensity,
            duration?.let { if (it <= 15) 15 else 30 }, fallback)
        val recommendation = try { recommendations.recommend(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { QuestRecommendation(null, request.durationMinutes ?: fallback, intensity, null) }
        val mappedTheme = when (recommendation.topic) {
            PreferenceTopic.TIDY -> "tidy"
            PreferenceTopic.CARE -> "care"
            PreferenceTopic.PLANNING -> "planning"
            PreferenceTopic.SOCIAL -> "social"
            PreferenceTopic.RECOVERY -> "meditation"
            PreferenceTopic.EXPLORE -> "adventure"
            else -> null
        }
        val blueprint = planner.plan(vibe, recommendation.intensity ?: intensity, recommendation.durationMinutes, boss,
            input ?: mappedTheme, recentTitles())
        return GenerationPlan(blueprint, input, entry, recommendation.hint)
    }

    private suspend fun generateApi(plan: GenerationPlan, replacing: Quest? = null): QuestCreationResult {
        return when (val result = apiTaskGenerator.polishBlueprint(plan.blueprint, recentTitles(), plan.input, plan.hint)) {
            is ApiGenerationResult.Success -> QuestCreationResult(
                create(plan, QuestSource.API, result.draft.title, result.draft.description, result.draft.reward,
                    result.draft.topic, replacing), QuestSource.API,
            )
            is ApiGenerationResult.Fallback -> QuestCreationResult(create(plan, QuestSource.TEMPLATE, replacing = replacing),
                QuestSource.TEMPLATE, result.message)
        }
    }

    private suspend fun create(
        plan: GenerationPlan, source: QuestSource, title: String = plan.blueprint.title,
        description: String = plan.blueprint.description, reward: String = plan.blueprint.reward,
        topic: PreferenceTopic = plan.blueprint.theme.preferenceTopic(), replacing: Quest? = null,
    ): Quest {
        val b = plan.blueprint
        val quest = Quest(id = UUID.randomUUID().toString(), title = title, description = description, difficulty = b.difficulty,
            reward = reward, status = "active", durationMinutes = b.durationMinutes, vibe = b.vibe,
            theme = if (source == QuestSource.API && topic != PreferenceTopic.UNKNOWN) topic.label else b.theme.label,
            mode = b.mode.label, xp = b.durationMinutes, source = source.name, requestedTheme = plan.input,
            generationEntry = plan.entry, preferenceTopic = topic.name, createdAt = System.currentTimeMillis())
        stateStore.saveActiveQuest(quest)
        _quests.value = listOf(quest) + _quests.value.filterNot { it.status == "active" }
        if (replacing != null) {
            timer.clear(replacing.id)
            preferences.recordSafely(replacing.interaction(InteractionKind.REROLLED))
        }
        preferences.recordSafely(quest.interaction(InteractionKind.GENERATED))
        AppLog.i("QuestRepository", "created: id=${quest.id}, source=$source, replacing=${replacing != null}")
        return quest
    }

    private fun entryFor(intensity: String, boss: Boolean) = when {
        boss -> "BOSS"
        intensity == "chill" -> "CHILL"
        intensity == "chaotic" -> "CHAOS"
        else -> "TEMPLATE"
    }
    private fun activeQuest(): Quest? = _quests.value.firstOrNull { it.status == "active" }
    private fun recentTitles(): List<String> = _quests.value.take(8).map { it.title }
    private fun isToday(timestamp: Long): Boolean {
        val today = Calendar.getInstance()
        val createdAt = Calendar.getInstance().apply { timeInMillis = timestamp }
        return today.get(Calendar.ERA) == createdAt.get(Calendar.ERA) &&
            today.get(Calendar.YEAR) == createdAt.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == createdAt.get(Calendar.DAY_OF_YEAR)
    }
}
