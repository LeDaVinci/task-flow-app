package com.taskflow.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.ChaosQuestApp
import com.taskflow.app.ai.LocalModelAvailability
import com.taskflow.app.data.Quest
import com.taskflow.app.data.QuestBoardSummary
import com.taskflow.app.data.QuestRepository
import com.taskflow.app.data.QuestSource
import com.taskflow.app.timer.QuestTimerState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class QuestViewModel : ViewModel() {

    private val repository: QuestRepository = ChaosQuestApp.instance.questRepository
    private val timerController = ChaosQuestApp.instance.questTimerController

    val quests: StateFlow<List<Quest>> = repository.quests
    val timerState: StateFlow<QuestTimerState?> = timerController.timerState

    private val _headline = MutableStateFlow("今晚想给生活加点什么？")
    val headline: StateFlow<String> = _headline.asStateFlow()

    private val _isAiGenerating = MutableStateFlow(false)
    val isAiGenerating: StateFlow<Boolean> = _isAiGenerating.asStateFlow()

    private val _aiLoadingMessage = MutableStateFlow(AI_LOADING_MESSAGES.first())
    val aiLoadingMessage: StateFlow<String> = _aiLoadingMessage.asStateFlow()

    private val _localModelStatus = MutableStateFlow("本地模型状态检测中")
    val localModelStatus: StateFlow<String> = _localModelStatus.asStateFlow()

    val summary: StateFlow<QuestBoardSummary> = quests
        .map { repository.boardSummary() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.boardSummary(),
        )

    init {
        refreshDailyState()
    }

    fun refreshDailyState() = viewModelScope.launch {
        if (repository.expireActiveQuestIfNeeded()) {
            _headline.value = "昨晚的支线已自动注销，今晚重新开始。"
        }
    }

    fun rollQuest(vibe: String = "random", intensity: String = "spicy", theme: String? = null) = viewModelScope.launch {
        val quest = repository.rollQuest(vibe = vibe, intensity = intensity, theme = theme)
        _headline.value = "新支线已刷新: ${quest.title}"
    }

    fun summonBossQuest() = viewModelScope.launch {
        val quest = repository.rollQuest(
            vibe = "resolve",
            intensity = "boss",
            forceBoss = true,
        )
        _headline.value = "Boss 降临: ${quest.title}"
    }

    fun rollQuestWithLocalModel(vibe: String = "random", intensity: String = "spicy", theme: String? = null) {
        viewModelScope.launch {
            val result = repository.rollQuestWithLocalModel(vibe = vibe, intensity = intensity, theme = theme)
            _headline.value = when (result.source) {
                QuestSource.LOCAL_MODEL -> "本地模型已生成: ${result.quest.title}"
                QuestSource.TEMPLATE -> result.note ?: "本地模型不可用，已回退模板任务"
                QuestSource.API -> ""
            }
            refreshLocalModelStatus()
        }
    }

    fun rollQuestWithApi(theme: String? = null) {
        if (_isAiGenerating.value) return
        _isAiGenerating.value = true
        _aiLoadingMessage.value = AI_LOADING_MESSAGES.first()
        val rotationJob = rotateAiLoadingMessages()
        viewModelScope.launch {
            try {
                val result = repository.rollQuestWithApi(
                    vibe = "random",
                    intensity = "spicy",
                    theme = theme?.trim().takeUnless { it.isNullOrBlank() },
                )
                _headline.value = when (result.source) {
                    QuestSource.API -> "AI 已生成新支线: ${result.quest.title}"
                    QuestSource.TEMPLATE -> "AI 暂时不可用，已换成随机支线"
                    QuestSource.LOCAL_MODEL -> ""
                }
            } finally {
                rotationJob.cancel()
                _isAiGenerating.value = false
            }
        }
    }

    fun completeQuest(questId: String, reaction: String? = null) = viewModelScope.launch {
        val quest = repository.completeQuest(questId, reaction) ?: return@launch
        _headline.value = "通关成功 +${quest.xp} XP"
    }

    fun rerollQuest(quest: Quest) {
        if (quest.source == QuestSource.API.name || quest.generationEntry == "AI") {
            if (_isAiGenerating.value) return
            _isAiGenerating.value = true
            _aiLoadingMessage.value = AI_LOADING_MESSAGES.first()
            val rotationJob = rotateAiLoadingMessages()
            viewModelScope.launch {
                try {
                    val refreshed = repository.rerollQuest(quest.id) ?: return@launch
                    _headline.value = "换了一张: ${refreshed.title}"
                } finally {
                    rotationJob.cancel()
                    _isAiGenerating.value = false
                }
            }
            return
        }
        viewModelScope.launch {
            val refreshed = repository.rerollQuest(quest.id) ?: return@launch
            _headline.value = "换了一张: ${refreshed.title}"
        }
    }

    fun archiveQuest(questId: String) = viewModelScope.launch {
        val quest = repository.archiveQuest(questId) ?: return@launch
        _headline.value = "已放下 ${quest.title}。"
    }

    fun startQuestTimer(quest: Quest) = viewModelScope.launch {
        if (_isAiGenerating.value) return@launch
        if (repository.startQuest(quest.id) != null) {
            _headline.value = "开始执行：${quest.title}"
        }
    }

    fun showNotificationPermissionNeeded() {
        _headline.value = "需要通知权限，才能在系统中持续显示倒计时。"
    }

    fun refreshLocalModelStatus() {
        viewModelScope.launch {
            val status = repository.getLocalModelStatus()
            _localModelStatus.value = when (status.availability) {
                LocalModelAvailability.READY -> "本地模型可用"
                LocalModelAvailability.MODEL_MISSING -> "本地模型缺失"
                LocalModelAvailability.ERROR -> "本地模型异常"
            }
        }
    }

    private fun rotateAiLoadingMessages(): Job = viewModelScope.launch {
        var index = 0
        while (isActive) {
            _aiLoadingMessage.value = AI_LOADING_MESSAGES[index]
            delay(1_500)
            index = (index + 1) % AI_LOADING_MESSAGES.size
        }
    }

    private companion object {
        val AI_LOADING_MESSAGES = listOf(
            "正在翻找今晚的隐藏支线…",
            "正在给生活事务套上任务皮肤…",
            "正在避开无聊选项…",
            "正在挑一件你现在就能做的事…",
        )
    }
}
