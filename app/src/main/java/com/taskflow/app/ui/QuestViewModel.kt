package com.taskflow.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskflow.app.ChaosQuestApp
import com.taskflow.app.ai.LocalModelAvailability
import com.taskflow.app.data.Quest
import com.taskflow.app.data.QuestBoardSummary
import com.taskflow.app.data.QuestRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

class QuestViewModel : ViewModel() {

    private val repository: QuestRepository = ChaosQuestApp.instance.questRepository

    val quests: StateFlow<List<Quest>> = repository.quests

    private val _headline = MutableStateFlow("今天的命运板还没翻车。")
    val headline: StateFlow<String> = _headline.asStateFlow()

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
        refreshLocalModelStatus()
    }

    fun rollQuest(vibe: String = "random", intensity: String = "spicy", theme: String? = null) {
        val quest = repository.rollQuest(vibe = vibe, intensity = intensity, theme = theme)
        _headline.value = "新支线已刷新: ${quest.title}"
    }

    fun summonBossQuest() {
        val quest = repository.rollQuest(vibe = "resolve", intensity = "boss", durationMinutes = 25, forceBoss = true)
        _headline.value = "Boss 降临: ${quest.title}"
    }

    fun rollQuestWithLocalModel(vibe: String = "random", intensity: String = "spicy", theme: String? = null) {
        viewModelScope.launch {
            val result = repository.rollQuestWithLocalModel(vibe = vibe, intensity = intensity, theme = theme)
            _headline.value = when (result.source) {
                com.taskflow.app.data.QuestSource.LOCAL_MODEL -> "本地模型已生成: ${result.quest.title}"
                com.taskflow.app.data.QuestSource.TEMPLATE -> result.note ?: "本地模型不可用，已回退模板任务"
            }
            refreshLocalModelStatus()
        }
    }

    fun completeQuest(questId: String) {
        val quest = repository.completeQuest(questId, "已收工") ?: return
        _headline.value = "通关成功: ${quest.title}"
    }

    fun rerollQuest(questId: String) {
        val quest = repository.rerollQuest(questId) ?: return
        _headline.value = "命运重掷: ${quest.title}"
    }

    fun archiveQuest(questId: String) {
        val quest = repository.archiveQuest(questId) ?: return
        _headline.value = "已把 ${quest.title} 扔回酒馆公告栏。"
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
}
