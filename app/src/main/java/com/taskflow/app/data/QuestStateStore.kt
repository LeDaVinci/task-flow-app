package com.taskflow.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject

class QuestStateStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadActiveQuest(): Quest? {
        val raw = prefs.getString(KEY_ACTIVE_QUEST, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            Quest(
                id = json.getString("id"),
                title = json.getString("title"),
                description = json.getString("description"),
                difficulty = json.getString("difficulty"),
                reward = json.getString("reward"),
                status = "active",
                durationMinutes = json.getInt("durationMinutes"),
                vibe = json.getString("vibe"),
                theme = json.getString("theme"),
                mode = json.getString("mode"),
                xp = json.getInt("xp"),
                source = json.optString("source", QuestSource.TEMPLATE.name),
                createdAt = json.getLong("createdAt"),
            )
        }.getOrNull()
    }

    fun saveActiveQuest(quest: Quest) {
        val json = JSONObject()
            .put("id", quest.id)
            .put("title", quest.title)
            .put("description", quest.description)
            .put("difficulty", quest.difficulty)
            .put("reward", quest.reward)
            .put("durationMinutes", quest.durationMinutes)
            .put("vibe", quest.vibe)
            .put("theme", quest.theme)
            .put("mode", quest.mode)
            .put("xp", quest.xp)
            .put("source", quest.source)
            .put("createdAt", quest.createdAt)
        prefs.edit { putString(KEY_ACTIVE_QUEST, json.toString()) }
    }

    fun clearActiveQuest() {
        prefs.edit { remove(KEY_ACTIVE_QUEST) }
    }

    fun loadTotalXp(): Int = prefs.getInt(KEY_TOTAL_XP, 0)

    fun saveTotalXp(totalXp: Int) {
        prefs.edit { putInt(KEY_TOTAL_XP, totalXp) }
    }

    companion object {
        private const val PREFS_NAME = "quest_state"
        private const val KEY_ACTIVE_QUEST = "active_quest"
        private const val KEY_TOTAL_XP = "total_xp"
    }
}
