package com.taskflow.app.ai

import android.content.Context
import com.taskflow.app.data.QuestBlueprint
import com.taskflow.app.logging.AppLog

enum class LocalModelAvailability {
    READY,
    MODEL_MISSING,
    ERROR,
}

data class LocalModelStatus(
    val availability: LocalModelAvailability,
    val message: String,
    val modelPath: String? = null,
)

data class LocalQuestDraft(
    val title: String,
    val description: String,
    val reward: String,
    val difficulty: String,
    val vibe: String,
)

sealed interface LocalGenerationResult {
    data class Success(
        val draft: LocalQuestDraft,
        val modelPath: String,
    ) : LocalGenerationResult

    data class Fallback(
        val status: LocalModelStatus,
    ) : LocalGenerationResult
}

/** 保留既有调用接口；端侧推理依赖已移除，所有请求回退至结构化任务模板。 */
class LocalModelTaskGenerator(
    @Suppress("UNUSED_PARAMETER") context: Context,
) {

    suspend fun setPreferredModelPath(path: String?): LocalModelStatus {
        AppLog.i("LocalModel", "ignored local model path after runtime removal: $path")
        return unavailableStatus()
    }

    suspend fun checkAvailability(): LocalModelStatus = unavailableStatus()

    suspend fun generateQuest(
        vibe: String,
        intensity: String,
        durationMinutes: Int,
        forceBoss: Boolean,
        avoidTitles: List<String> = emptyList(),
    ): LocalGenerationResult {
        AppLog.i(
            "LocalModel",
            "generation skipped after runtime removal: vibe=$vibe, intensity=$intensity, duration=$durationMinutes, boss=$forceBoss, avoid=${avoidTitles.size}",
        )
        return LocalGenerationResult.Fallback(unavailableStatus())
    }

    suspend fun polishBlueprint(
        blueprint: QuestBlueprint,
        avoidTitles: List<String> = emptyList(),
    ): LocalGenerationResult {
        AppLog.i("LocalModel", "blueprint polish skipped after runtime removal: title=${blueprint.title}, avoid=${avoidTitles.size}")
        return LocalGenerationResult.Fallback(unavailableStatus())
    }

    private fun unavailableStatus() = LocalModelStatus(
        availability = LocalModelAvailability.MODEL_MISSING,
        message = "端侧模型运行时已移除，已使用结构化任务模板",
    )
}
