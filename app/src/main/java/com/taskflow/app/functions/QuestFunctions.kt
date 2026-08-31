package com.taskflow.app.functions

import android.util.Log
import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.service.AppFunction
import com.taskflow.app.data.Quest
import com.taskflow.app.data.QuestBoardSummary
import com.taskflow.app.data.QuestRepository

@AppFunctionSerializable
data class QuestRollRequest(
    /** 想要的氛围，例如 chill、social、chaos。 */
    val vibe: String? = null,
    /** 想要的强度，例如 chill、spicy、chaotic。 */
    val intensity: String? = null,
    /** 指定任务主题，例如 story、exercise、nature。 */
    val theme: String? = null,
    /** 期望时长，单位分钟。 */
    val durationMinutes: Int? = null,
)

@AppFunctionSerializable
data class CompleteQuestRequest(
    /** 要完成的任务 ID。 */
    val questId: String,
    /** 完成后的简短感言。 */
    val reaction: String? = null,
)

@AppFunctionSerializable
data class LocalModelStatusResponse(
    /** 模型当前状态。 */
    val status: String,
    /** 说明信息。 */
    val message: String,
    /** 模型路径。 */
    val modelPath: String? = null,
)

@AppFunctionSerializable
data class LocalModelPathRequest(
    /** 本地模型绝对路径。 */
    val path: String? = null,
)

class QuestFunctions(
    private val repository: QuestRepository,
) {

    /**
     * 生成一个新的趣味支线任务。
     *
     * @param request 任务偏好，包含氛围、强度和期望时长。
     * @return 新创建的任务对象。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun rollQuest(
        context: AppFunctionContext,
        request: QuestRollRequest,
    ): Quest {
        Log.i(TAG, "rollQuest called: vibe=${request.vibe}, intensity=${request.intensity}, duration=${request.durationMinutes}")
        return repository.rollQuest(
            vibe = request.vibe ?: "random",
            intensity = request.intensity ?: "spicy",
            theme = request.theme,
            durationMinutes = request.durationMinutes,
        )
    }

    /**
     * 生成一个 Boss 级任务，适合用户想狠狠干掉拖延事项时使用。
     *
     * @param theme Boss 任务主题标签。
     * @return 新创建的 Boss 任务。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun summonBossQuest(
        context: AppFunctionContext,
        theme: String? = null,
    ): Quest {
        Log.i(TAG, "summonBossQuest called: theme=$theme")
        return repository.rollQuest(
            vibe = theme ?: "resolve",
            intensity = "boss",
            theme = null,
            durationMinutes = 25,
            forceBoss = true,
        )
    }

    /**
     * 标记某个任务已完成。
     *
     * @param request 完成任务所需参数。
     * @return 更新后的任务；如果未找到则返回 null。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun completeQuest(
        context: AppFunctionContext,
        request: CompleteQuestRequest,
    ): Quest? {
        Log.i(TAG, "completeQuest called: id=${request.questId}")
        return repository.completeQuest(
            questId = request.questId,
            reaction = request.reaction,
        )
    }

    /**
     * 放弃当前任务并立刻换一张新的。
     *
     * @param questId 要重掷的任务 ID。
     * @return 新任务；如果原任务不存在则返回 null。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun rerollQuest(
        context: AppFunctionContext,
        questId: String,
    ): Quest? {
        Log.i(TAG, "rerollQuest called: id=$questId")
        return repository.rerollQuest(questId)
    }

    /**
     * 获取当前仍在进行中的任务列表。
     *
     * @return 活跃任务列表。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun listActiveQuests(
        context: AppFunctionContext,
    ): List<Quest> = repository.listActiveQuests()

    /**
     * 获取任务板的总览数据。
     *
     * @return 当前任务板统计信息。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getQuestBoardSummary(
        context: AppFunctionContext,
    ): QuestBoardSummary = repository.boardSummary()

    /**
     * 使用 app 内本地模型生成一个任务。
     *
     * @param request 任务偏好，包含氛围、强度和期望时长。
     * @return 新任务；当本地模型不可用时会自动回退到模板任务。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun rollQuestWithLocalModel(
        context: AppFunctionContext,
        request: QuestRollRequest,
    ): Quest {
        Log.i(TAG, "rollQuestWithLocalModel called: vibe=${request.vibe}, intensity=${request.intensity}")
        return repository.rollQuestWithLocalModel(
            vibe = request.vibe ?: "random",
            intensity = request.intensity ?: "spicy",
            theme = request.theme,
            durationMinutes = request.durationMinutes,
        ).quest
    }

    /**
     * 使用 OpenAI 兼容 API 生成一个任务；接口不可用时自动回退到模板任务。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun rollQuestWithApi(
        context: AppFunctionContext,
        request: QuestRollRequest,
    ): Quest = repository.rollQuestWithApi(
        vibe = request.vibe ?: "random",
        intensity = request.intensity ?: "spicy",
        theme = request.theme,
        durationMinutes = request.durationMinutes,
    ).quest

    /**
     * 查询 app 内本地模型当前状态。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getLocalModelStatus(
        context: AppFunctionContext,
    ): LocalModelStatusResponse {
        val status = repository.getLocalModelStatus()
        return LocalModelStatusResponse(
            status = status.availability.name,
            message = status.message,
            modelPath = status.modelPath,
        )
    }

    /**
     * 设置本地模型绝对路径。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun setLocalModelPath(
        context: AppFunctionContext,
        request: LocalModelPathRequest,
    ): LocalModelStatusResponse {
        val status = repository.setLocalModelPath(request.path)
        return LocalModelStatusResponse(
            status = status.availability.name,
            message = status.message,
            modelPath = status.modelPath,
        )
    }

    /**
     * 兼容旧调用名，内部改走 app 内本地模型。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun rollQuestWithNano(
        context: AppFunctionContext,
        request: QuestRollRequest,
    ): Quest = rollQuestWithLocalModel(context, request)

    /**
     * 兼容旧调用名，内部改走 app 内本地模型状态。
     */
    @AppFunction(isDescribedByKDoc = true)
    suspend fun getNanoAvailability(
        context: AppFunctionContext,
    ): LocalModelStatusResponse = getLocalModelStatus(context)

    companion object {
        private const val TAG = "QuestFunctions"
    }
}
