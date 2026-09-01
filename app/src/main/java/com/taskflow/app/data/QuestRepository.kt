package com.taskflow.app.data

import com.taskflow.app.ai.ApiGenerationResult
import com.taskflow.app.ai.LocalGenerationResult
import com.taskflow.app.ai.LocalModelStatus
import com.taskflow.app.ai.LocalModelTaskGenerator
import com.taskflow.app.ai.OpenAiApiTaskGenerator
import com.taskflow.app.logging.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.Calendar
import kotlin.random.Random

data class QuestCreationResult(
    val quest: Quest,
    val source: QuestSource,
    val note: String? = null,
)

enum class QuestSource {
    TEMPLATE,
    LOCAL_MODEL,
    API,
}

class QuestRepository(
    private val localModelTaskGenerator: LocalModelTaskGenerator,
    private val apiTaskGenerator: OpenAiApiTaskGenerator,
    private val stateStore: QuestStateStore,
    private val planner: QuestBlueprintPlanner = QuestBlueprintPlanner(),
) {

    private val random = Random(System.currentTimeMillis())
    private var totalXp = stateStore.loadTotalXp()
    private val _quests = MutableStateFlow(loadInitialQuests())
    val quests: StateFlow<List<Quest>> = _quests.asStateFlow()

    fun rollQuest(
        vibe: String = "random",
        intensity: String = "spicy",
        durationMinutes: Int? = null,
        forceBoss: Boolean = false,
        theme: String? = null,
    ): Quest {
        activeQuest()?.let { return it }
        return createTemplateQuest(vibe, intensity, durationMinutes, forceBoss, theme)
    }

    suspend fun rollQuestWithLocalModel(
        vibe: String = "random",
        intensity: String = "spicy",
        durationMinutes: Int? = null,
        forceBoss: Boolean = false,
        theme: String? = null,
    ): QuestCreationResult {
        activeQuest()?.let { return QuestCreationResult(it, QuestSource.TEMPLATE, "已有进行中的支线") }
        val blueprint = planBlueprint(vibe, intensity, durationMinutes, forceBoss, theme)
        return when (val result = localModelTaskGenerator.polishBlueprint(blueprint, recentTitles())) {
            is LocalGenerationResult.Success -> {
                val quest = createQuest(
                    title = normalizeLocalTitle(result.draft.title),
                    description = result.draft.description,
                    reward = result.draft.reward,
                    blueprint = blueprint,
                    source = QuestSource.LOCAL_MODEL,
                    replacing = false,
                )
                AppLog.i("QuestRepository", "local model generated: title=${quest.title}")
                QuestCreationResult(quest, QuestSource.LOCAL_MODEL)
            }
            is LocalGenerationResult.Fallback -> fallbackToTemplate(blueprint, result.status.message, replacing = false)
        }
    }

    suspend fun rollQuestWithApi(
        vibe: String = "random",
        intensity: String = "spicy",
        durationMinutes: Int? = null,
        forceBoss: Boolean = false,
        theme: String? = null,
    ): QuestCreationResult {
        activeQuest()?.let { return QuestCreationResult(it, QuestSource.TEMPLATE, "已有进行中的支线") }
        return generateApiQuest(vibe, intensity, durationMinutes, forceBoss, theme, replacing = false)
    }

    suspend fun rerollQuest(questId: String): Quest? {
        val original = activeQuest()?.takeIf { it.id == questId } ?: return null
        return if (original.source == QuestSource.API.name) {
            generateApiQuest(
                vibe = original.vibe,
                intensity = original.difficulty,
                durationMinutes = original.durationMinutes,
                forceBoss = original.difficulty == "boss",
                theme = original.theme,
                replacing = true,
            ).quest
        } else {
            createTemplateQuest(
                vibe = original.vibe,
                intensity = original.difficulty,
                durationMinutes = original.durationMinutes,
                forceBoss = original.difficulty == "boss",
                theme = original.theme,
                replacing = true,
            )
        }
    }

    fun completeQuest(questId: String, reaction: String? = null): Quest? {
        val active = activeQuest()?.takeIf { it.id == questId } ?: return null
        val completed = active.copy(
            status = "completed",
            completedAt = System.currentTimeMillis(),
            reaction = reaction?.trim().takeUnless { it.isNullOrBlank() },
        )
        totalXp += active.xp
        stateStore.saveTotalXp(totalXp)
        stateStore.clearActiveQuest()
        _quests.update { quests -> listOf(completed) + quests.filterNot { it.status == "active" } }
        AppLog.i("QuestRepository", "quest completed: id=$questId, totalXp=$totalXp")
        return completed
    }

    fun archiveQuest(questId: String): Quest? {
        val active = activeQuest()?.takeIf { it.id == questId } ?: return null
        stateStore.clearActiveQuest()
        _quests.update { quests -> quests.filterNot { it.status == "active" } }
        AppLog.i("QuestRepository", "quest archived: id=$questId")
        return active.copy(status = "archived")
    }

    fun expireActiveQuestIfNeeded(): Boolean {
        val active = activeQuest() ?: return false
        if (isToday(active.createdAt)) return false
        stateStore.clearActiveQuest()
        _quests.update { quests -> quests.filterNot { it.status == "active" } }
        AppLog.i("QuestRepository", "expired previous-day quest: id=${active.id}")
        return true
    }

    fun findQuest(questId: String): Quest? = _quests.value.firstOrNull { it.id == questId }

    fun listActiveQuests(): List<Quest> = listOfNotNull(activeQuest())

    fun boardSummary(): QuestBoardSummary {
        val all = _quests.value
        val active = all.filter { it.status == "active" }
        return QuestBoardSummary(
            activeCount = active.size,
            completedCount = all.count { it.status == "completed" },
            bossCount = active.count { it.difficulty == "boss" },
            totalXp = totalXp,
            highlightedTitles = active.map { it.title },
        )
    }

    suspend fun getLocalModelStatus(): LocalModelStatus = localModelTaskGenerator.checkAvailability()

    suspend fun setLocalModelPath(path: String?): LocalModelStatus = localModelTaskGenerator.setPreferredModelPath(path)

    private suspend fun generateApiQuest(
        vibe: String,
        intensity: String,
        durationMinutes: Int?,
        forceBoss: Boolean,
        theme: String?,
        replacing: Boolean,
    ): QuestCreationResult {
        val blueprint = planBlueprint(vibe, intensity, durationMinutes, forceBoss, theme)
        return when (val result = apiTaskGenerator.polishBlueprint(blueprint, recentTitles(), theme)) {
            is ApiGenerationResult.Success -> {
                val quest = createQuest(
                    title = result.draft.title,
                    description = result.draft.description,
                    reward = result.draft.reward,
                    blueprint = blueprint,
                    source = QuestSource.API,
                    replacing = replacing,
                )
                AppLog.i("QuestRepository", "API quest ${if (replacing) "replaced" else "created"}: id=${quest.id}")
                QuestCreationResult(quest, QuestSource.API)
            }
            is ApiGenerationResult.Fallback -> fallbackToTemplate(blueprint, result.message, replacing)
        }
    }

    private fun createTemplateQuest(
        vibe: String,
        intensity: String,
        durationMinutes: Int?,
        forceBoss: Boolean,
        theme: String?,
        replacing: Boolean = false,
    ): Quest {
        val blueprint = planBlueprint(vibe, intensity, durationMinutes, forceBoss, theme)
        return createQuest(
            title = blueprint.title,
            description = blueprint.description,
            reward = blueprint.reward,
            blueprint = blueprint,
            source = QuestSource.TEMPLATE,
            replacing = replacing,
        )
    }

    private fun fallbackToTemplate(
        blueprint: QuestBlueprint,
        message: String,
        replacing: Boolean,
    ): QuestCreationResult {
        AppLog.w("QuestRepository", "API quest fallback: $message")
        val quest = createQuest(
            title = blueprint.title,
            description = blueprint.description,
            reward = blueprint.reward,
            blueprint = blueprint,
            source = QuestSource.TEMPLATE,
            replacing = replacing,
        )
        return QuestCreationResult(quest, QuestSource.TEMPLATE, message)
    }

    private fun planBlueprint(
        vibe: String,
        intensity: String,
        durationMinutes: Int?,
        forceBoss: Boolean,
        theme: String?,
    ): QuestBlueprint = planner.plan(
        vibe = vibe.ifBlank { "random" }.lowercase(),
        intensity = intensity.ifBlank { "spicy" }.lowercase(),
        durationMinutes = normalizeDuration(durationMinutes ?: suggestedDuration(intensity, forceBoss)),
        forceBoss = forceBoss,
        requestedTheme = theme,
        avoidTitles = recentTitles(),
    )

    private fun createQuest(
        title: String,
        description: String,
        reward: String,
        blueprint: QuestBlueprint,
        source: QuestSource,
        replacing: Boolean,
    ): Quest {
        val existing = activeQuest()
        if (existing != null && !replacing) return existing
        val quest = Quest(
            id = buildId(),
            title = title,
            description = description,
            difficulty = blueprint.difficulty,
            reward = reward,
            status = "active",
            durationMinutes = blueprint.durationMinutes,
            vibe = blueprint.vibe,
            theme = blueprint.theme.label,
            mode = blueprint.mode.label,
            xp = xpForDuration(blueprint.durationMinutes),
            source = source.name,
            createdAt = System.currentTimeMillis(),
        )
        stateStore.saveActiveQuest(quest)
        _quests.update { quests -> listOf(quest) + quests.filterNot { it.status == "active" } }
        AppLog.i("QuestRepository", "quest ${if (replacing) "replaced" else "created"}: id=${quest.id}, source=$source")
        return quest
    }

    private fun loadInitialQuests(): List<Quest> {
        val active = stateStore.loadActiveQuest() ?: return emptyList()
        return if (isToday(active.createdAt)) {
            listOf(active)
        } else {
            stateStore.clearActiveQuest()
            AppLog.i("QuestRepository", "discarded previous-day quest at startup")
            emptyList()
        }
    }

    private fun activeQuest(): Quest? = _quests.value.firstOrNull { it.status == "active" }

    private fun recentTitles(): List<String> = _quests.value.take(8).map { it.title }

    private fun suggestedDuration(intensity: String, forceBoss: Boolean): Int = when {
        forceBoss -> 30
        intensity.lowercase() == "chaotic" -> 30
        else -> 15
    }

    private fun normalizeDuration(durationMinutes: Int): Int = if (durationMinutes <= 15) 15 else 30

    private fun xpForDuration(durationMinutes: Int): Int = normalizeDuration(durationMinutes)

    private fun isToday(timestamp: Long): Boolean {
        val today = Calendar.getInstance()
        val createdAt = Calendar.getInstance().apply { timeInMillis = timestamp }
        return today.get(Calendar.ERA) == createdAt.get(Calendar.ERA) &&
            today.get(Calendar.YEAR) == createdAt.get(Calendar.YEAR) &&
            today.get(Calendar.DAY_OF_YEAR) == createdAt.get(Calendar.DAY_OF_YEAR)
    }

    private fun buildId(): String = "quest-${System.currentTimeMillis()}-${random.nextInt(1000, 9999)}"

    private fun normalizeLocalTitle(title: String): String = title.trim().removePrefix("\"").removeSuffix("\"")
}
