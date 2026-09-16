package com.taskflow.app

import android.app.Application
import androidx.appfunctions.service.AppFunctionConfiguration
import com.taskflow.app.ai.LocalModelTaskGenerator
import com.taskflow.app.ai.OpenAiApiTaskGenerator
import com.taskflow.app.data.QuestRepository
import com.taskflow.app.data.QuestStateStore
import com.taskflow.app.functions.QuestFunctions
import com.taskflow.app.logging.AppLog
import com.taskflow.app.timer.QuestTimerController
import com.taskflow.app.ai.TaskAiClient
import com.taskflow.app.preference.*

class ChaosQuestApp : Application(), AppFunctionConfiguration.Provider {

    val localModelTaskGenerator: LocalModelTaskGenerator by lazy { LocalModelTaskGenerator(this) }
    private val taskAiClient by lazy { TaskAiClient() }
    val apiTaskGenerator: OpenAiApiTaskGenerator by lazy { OpenAiApiTaskGenerator(taskAiClient) }
    val preferenceRepository: PreferenceRepository by lazy {
        LocalPreferenceRepository(LocalPreferenceStore(this), AiPreferenceInferenceEngine(taskAiClient))
    }
    private val recommendationPolicy: QuestRecommendationPolicy by lazy { LocalQuestRecommendationPolicy(preferenceRepository) }
    val questStateStore: QuestStateStore by lazy { QuestStateStore(this) }
    val questTimerController: QuestTimerController by lazy { QuestTimerController(this, preferenceRepository) }
    val questRepository: QuestRepository by lazy {
        QuestRepository(localModelTaskGenerator, apiTaskGenerator, questStateStore, preferenceRepository, recommendationPolicy, questTimerController)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.i("App", "initialized")
    }

    override val appFunctionConfiguration: AppFunctionConfiguration by lazy {
        AppFunctionConfiguration.Builder()
            .addEnclosingClassFactory(QuestFunctions::class.java) {
                QuestFunctions(questRepository)
            }
            .build()
    }

    companion object {
        lateinit var instance: ChaosQuestApp
            private set
    }
}
