package com.taskflow.app

import android.app.Application
import android.util.Log
import androidx.appfunctions.service.AppFunctionConfiguration
import com.taskflow.app.ai.LocalModelTaskGenerator
import com.taskflow.app.ai.OpenAiApiTaskGenerator
import com.taskflow.app.data.QuestRepository
import com.taskflow.app.functions.QuestFunctions

class ChaosQuestApp : Application(), AppFunctionConfiguration.Provider {

    val localModelTaskGenerator: LocalModelTaskGenerator by lazy { LocalModelTaskGenerator(this) }
    val apiTaskGenerator: OpenAiApiTaskGenerator by lazy { OpenAiApiTaskGenerator() }
    val questRepository: QuestRepository by lazy { QuestRepository(localModelTaskGenerator, apiTaskGenerator) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "ChaosQuestApp initialized")
    }

    override val appFunctionConfiguration: AppFunctionConfiguration by lazy {
        AppFunctionConfiguration.Builder()
            .addEnclosingClassFactory(QuestFunctions::class.java) {
                QuestFunctions(questRepository)
            }
            .build()
    }

    companion object {
        private const val TAG = "ChaosQuestApp"

        lateinit var instance: ChaosQuestApp
            private set
    }
}
