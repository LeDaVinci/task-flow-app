package com.taskflow.app.data

import android.util.Log
import com.taskflow.app.ai.LocalGenerationResult
import com.taskflow.app.ai.LocalModelStatus
import com.taskflow.app.ai.LocalModelTaskGenerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

data class QuestCreationResult(
    val quest: Quest,
    val source: QuestSource,
    val note: String? = null,
)

enum class QuestSource {
    TEMPLATE,
    LOCAL_MODEL,
}

class QuestRepository(
    private val localModelTaskGenerator: LocalModelTaskGenerator,
    private val planner: QuestBlueprintPlanner = QuestBlueprintPlanner(),
) {

    private val random = Random(System.currentTimeMillis())

    private val _quests = MutableStateFlow(seedQuests())
    val quests: StateFlow<List<Quest>> = _quests.asStateFlow()

    fun rollQuest(
        vibe: String = "random",
        intensity: String = "spicy",
        durationMinutes: Int? = null,
        forceBoss: Boolean = false,
        theme: String? = null,
    ): Quest {
        val normalizedVibe = vibe.ifBlank { "random" }.lowercase()
        val normalizedIntensity = intensity.ifBlank { "spicy" }.lowercase()
        val actualDuration = durationMinutes ?: suggestedDuration(normalizedIntensity, forceBoss)
        val blueprint = planner.plan(
            vibe = normalizedVibe,
            intensity = normalizedIntensity,
            durationMinutes = actualDuration,
            forceBoss = forceBoss,
            requestedTheme = theme,
            avoidTitles = _quests.value.take(8).map { it.title },
        )
        return createQuest(blueprint, QuestSource.TEMPLATE)
    }

    suspend fun rollQuestWithLocalModel(
        vibe: String = "random",
        intensity: String = "spicy",
        durationMinutes: Int? = null,
        forceBoss: Boolean = false,
        theme: String? = null,
    ): QuestCreationResult {
        val normalizedVibe = vibe.ifBlank { "random" }.lowercase()
        val normalizedIntensity = intensity.ifBlank { "spicy" }.lowercase()
        val actualDuration = durationMinutes ?: suggestedDuration(normalizedIntensity, forceBoss)
        val avoidTitles = _quests.value.take(8).map { it.title }
        val blueprint = planner.plan(
            vibe = normalizedVibe,
            intensity = normalizedIntensity,
            durationMinutes = actualDuration,
            forceBoss = forceBoss,
            requestedTheme = theme,
            avoidTitles = avoidTitles,
        )
        return when (val result = localModelTaskGenerator.polishBlueprint(
            blueprint = blueprint,
            avoidTitles = avoidTitles,
        )) {
            is LocalGenerationResult.Success -> {
                val quest = createQuest(
                    title = normalizeLocalTitle(result.draft.title),
                    description = result.draft.description,
                    reward = result.draft.reward,
                    difficulty = blueprint.difficulty,
                    vibe = blueprint.vibe,
                    theme = blueprint.theme.label,
                    mode = blueprint.mode.label,
                    durationMinutes = actualDuration,
                    source = QuestSource.LOCAL_MODEL,
                )
                Log.i(TAG, "Local model generated quest successfully: modelPath=${result.modelPath}, title=${result.draft.title}")
                QuestCreationResult(quest, QuestSource.LOCAL_MODEL, "本地模型: ${result.modelPath}")
            }
            is LocalGenerationResult.Fallback -> {
                Log.i(TAG, "Local model fallback: ${result.status.message}")
                val quest = createQuest(blueprint, QuestSource.TEMPLATE)
                QuestCreationResult(quest, QuestSource.TEMPLATE, result.status.message)
            }
        }
    }

    fun rerollQuest(questId: String): Quest? {
        val original = findQuest(questId) ?: return null
        archiveQuest(questId)
        return rollQuest(
            vibe = original.vibe,
            intensity = original.difficulty,
            durationMinutes = original.durationMinutes,
            forceBoss = original.difficulty == "boss",
            theme = original.theme,
        )
    }

    fun completeQuest(questId: String, reaction: String? = null): Quest? {
        var completed: Quest? = null
        _quests.update { quests ->
            quests.map { quest ->
                if (quest.id == questId && quest.status == "active") {
                    quest.copy(
                        status = "completed",
                        completedAt = System.currentTimeMillis(),
                        reaction = reaction?.trim().takeUnless { it.isNullOrBlank() },
                    ).also { completed = it }
                } else {
                    quest
                }
            }
        }
        if (completed != null) {
            Log.i(TAG, "Quest completed: id=$questId")
        }
        return completed
    }

    fun archiveQuest(questId: String): Quest? {
        var archived: Quest? = null
        _quests.update { quests ->
            quests.map { quest ->
                if (quest.id == questId && quest.status == "active") {
                    quest.copy(status = "archived").also { archived = it }
                } else {
                    quest
                }
            }
        }
        if (archived != null) {
            Log.i(TAG, "Quest archived: id=$questId")
        }
        return archived
    }

    fun findQuest(questId: String): Quest? = _quests.value.firstOrNull { it.id == questId }

    fun listActiveQuests(): List<Quest> = _quests.value.filter { it.status == "active" }

    fun boardSummary(): QuestBoardSummary {
        val all = _quests.value
        val active = all.filter { it.status == "active" }
        val completed = all.filter { it.status == "completed" }
        return QuestBoardSummary(
            activeCount = active.size,
            completedCount = completed.size,
            bossCount = all.count { it.difficulty == "boss" && it.status == "active" },
            totalXp = completed.sumOf { it.xp },
            highlightedTitles = active.take(3).map { it.title },
        )
    }

    private fun seedQuests(): List<Quest> = listOf(
        seedQuest(
            title = "今日预告片 · 给今天起片名",
            description = "给今天起一个片名，再写三句预告词，把这 8 分钟当成一段短片并存进备忘录。",
            reward = "创意值 +18 XP",
            difficulty = "spicy",
            vibe = "chaos",
            theme = QuestTheme.STORY.label,
            mode = QuestMode.DIRECTOR.label,
            durationMinutes = 8,
            xp = 26,
        ),
        seedQuest(
            title = "线索追踪 · 天空取样",
            description = "看窗外 2 分钟，记下颜色和形状变化，最后在备忘录写两个观察词。",
            reward = "天气值 +16 XP",
            difficulty = "chill",
            vibe = "chill",
            theme = QuestTheme.NATURE.label,
            mode = QuestMode.DETECTIVE.label,
            durationMinutes = 6,
            xp = 18,
        ),
        seedQuest(
            title = "Boss 最终回合 · 角落排雷",
            description = "选桌面一个最乱的小角，清掉 5 个无效物品，中途不允许改目标。",
            reward = "Boss 宝箱: 秩序值 +62 XP",
            difficulty = "boss",
            vibe = "resolve",
            theme = QuestTheme.TIDY.label,
            mode = QuestMode.BOSS.label,
            durationMinutes = 12,
            xp = 40,
        ),
    )

    private fun seedQuest(
        title: String,
        description: String,
        difficulty: String,
        vibe: String,
        theme: String,
        mode: String,
        durationMinutes: Int,
        xp: Int,
        reward: String,
    ): Quest = Quest(
        id = buildId(),
        title = title,
        description = description,
        difficulty = difficulty,
        reward = reward,
        status = "active",
        durationMinutes = durationMinutes,
        vibe = vibe,
        theme = theme,
        mode = mode,
        xp = xp,
        createdAt = System.currentTimeMillis(),
    )

    private fun normalizeDifficulty(intensity: String): String = when (intensity) {
        "chill" -> "chill"
        "chaotic" -> "chaotic"
        else -> "spicy"
    }

    suspend fun getLocalModelStatus(): LocalModelStatus = localModelTaskGenerator.checkAvailability()

    suspend fun setLocalModelPath(path: String?): LocalModelStatus = localModelTaskGenerator.setPreferredModelPath(path)

    private fun suggestedDuration(intensity: String, forceBoss: Boolean): Int = when {
        forceBoss -> 25
        intensity == "chill" -> 8
        intensity == "chaotic" -> 15
        else -> 12
    }

    private fun rewardXp(difficulty: String, durationMinutes: Int): Int = when (difficulty) {
        "boss" -> 60 + durationMinutes
        "chaotic" -> 28 + durationMinutes
        "chill" -> 10 + durationMinutes
        else -> 18 + durationMinutes
    }

    private fun buildId(): String = "quest-${System.currentTimeMillis()}-${random.nextInt(1000, 9999)}"

    private fun createQuest(
        blueprint: QuestBlueprint,
        source: QuestSource,
    ): Quest = createQuest(
        title = blueprint.title,
        description = blueprint.description,
        reward = blueprint.reward,
        difficulty = blueprint.difficulty,
        vibe = blueprint.vibe,
        theme = blueprint.theme.label,
        mode = blueprint.mode.label,
        durationMinutes = blueprint.durationMinutes,
        source = source,
    )

    private fun createQuest(
        title: String,
        description: String,
        reward: String,
        difficulty: String,
        vibe: String,
        theme: String,
        mode: String,
        durationMinutes: Int,
        source: QuestSource,
    ): Quest {
        val quest = Quest(
            id = buildId(),
            title = title,
            description = description,
            difficulty = difficulty,
            reward = reward,
            status = "active",
            durationMinutes = durationMinutes,
            vibe = vibe,
            theme = theme,
            mode = mode,
            xp = rewardXp(difficulty, durationMinutes),
            createdAt = System.currentTimeMillis(),
        )
        _quests.update { listOf(quest) + it }
        Log.i(TAG, "Quest rolled: id=${quest.id}, difficulty=${quest.difficulty}, vibe=${quest.vibe}, source=$source")
        return quest
    }

    private fun normalizeLocalTitle(
        title: String,
    ): String {
        return title.trim().removePrefix("\"").removeSuffix("\"")
    }

    companion object {
        private const val TAG = "QuestRepository"
    }
}
