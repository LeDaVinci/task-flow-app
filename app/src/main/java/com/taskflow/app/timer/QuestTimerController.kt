package com.taskflow.app.timer

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.taskflow.app.data.Quest
import com.taskflow.app.logging.AppLog
import com.taskflow.app.preference.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.Calendar

data class QuestTimerState(
    val questId: String,
    val questTitle: String,
    val endAt: Long,
    val isFinished: Boolean,
)

class QuestTimerController(private val context: Context, private val preferences: PreferenceRepository) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _timerState = MutableStateFlow(loadState())
    val timerState: StateFlow<QuestTimerState?> = _timerState.asStateFlow()

    suspend fun start(quest: Quest): Boolean {
        val current = _timerState.value
        if (current?.questId == quest.id && !current.isFinished) return false

        val state = QuestTimerState(
            questId = quest.id,
            questTitle = quest.title,
            endAt = System.currentTimeMillis() + quest.durationMinutes * 60_000L,
            isFinished = false,
        )
        persist(state)
        _timerState.value = state
        ContextCompat.startForegroundService(
            context,
            Intent(context, QuestTimerService::class.java).setAction(QuestTimerService.ACTION_START),
        )
        preferences.recordSafely(quest.interaction(InteractionKind.STARTED))
        AppLog.i("QuestTimer", "started: questId=${quest.id}, endAt=${state.endAt}")
        return true
    }

    suspend fun markFinished(questId: String) {
        val state = _timerState.value?.takeIf { it.questId == questId } ?: return
        val finished = state.copy(isFinished = true)
        persist(finished)
        _timerState.value = finished
        preferences.state.value.events.firstOrNull { it.questId == questId }?.let { event ->
            preferences.recordSafely(event.copy(id = "$questId:TIMER_FINISHED", kind = InteractionKind.TIMER_FINISHED,
                occurredAt = state.endAt, reaction = null,
                localHour = Calendar.getInstance().apply { timeInMillis = state.endAt }.get(Calendar.HOUR_OF_DAY)))
        }
        AppLog.i("QuestTimer", "finished: questId=$questId")
    }

    fun clear(questId: String? = null) {
        val state = _timerState.value
        if (questId != null && state?.questId != questId) return
        prefs.edit().remove(KEY_TIMER_STATE).apply()
        _timerState.value = null
        context.stopService(Intent(context, QuestTimerService::class.java))
        AppLog.i("QuestTimer", "cleared: questId=${state?.questId}")
    }

    private fun loadState(): QuestTimerState? {
        val raw = prefs.getString(KEY_TIMER_STATE, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            QuestTimerState(
                questId = json.getString("questId"),
                questTitle = json.getString("questTitle"),
                endAt = json.getLong("endAt"),
                isFinished = json.getBoolean("isFinished"),
            )
        }.getOrNull()?.let { state ->
            if (!state.isFinished && state.endAt <= System.currentTimeMillis()) state.copy(isFinished = true) else state
        }
    }

    private fun persist(state: QuestTimerState) {
        prefs.edit().putString(
            KEY_TIMER_STATE,
            JSONObject()
                .put("questId", state.questId)
                .put("questTitle", state.questTitle)
                .put("endAt", state.endAt)
                .put("isFinished", state.isFinished)
                .toString(),
        ).apply()
    }

    private companion object {
        const val PREFS_NAME = "quest_timer"
        const val KEY_TIMER_STATE = "timer_state"
    }
}
