package com.taskflow.app.preference

import com.taskflow.app.data.QuestDuration

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class LocalPreferenceStore(context: Context) : PreferenceStore {
    private val prefs = context.getSharedPreferences("task_preferences", Context.MODE_PRIVATE)

    override fun load(): PreferenceState = runCatching {
        val root = JSONObject(prefs.getString("state", "{}") ?: "{}")
        PreferenceState(
            enabled = root.optBoolean("enabled"), revision = root.optLong("revision"),
            events = root.optJSONArray("events").objects().mapNotNull { json -> runCatching {
                QuestInteraction(
                    json.getString("id"), json.getString("questId"), InteractionKind.valueOf(json.getString("kind")),
                    json.getLong("at"), json.getString("title"), PreferenceTopic.parse(json.optString("topic")),
                    json.getInt("duration"), json.getString("intensity"), json.getString("source"),
                    json.getString("entry"), json.getBoolean("random"), json.nullableString("reaction"),
                    json.optInt("hour", -1).takeIf { it in 0..23 },
                )
            }.getOrNull() },
            manualTopics = root.optJSONObject("manual").topicScores(),
            accepted = root.optJSONObject("accepted")?.suggestion(),
            pending = root.optJSONObject("pending")?.suggestion(),
            reviewedQuestIds = root.optJSONArray("reviewed").let { array -> (0 until (array?.length() ?: 0)).map { array!!.getString(it) }.toSet() },
        )
    }.getOrElse { PreferenceState() }

    override suspend fun save(state: PreferenceState) = withContext(Dispatchers.IO) {
        val root = JSONObject().put("schema", 1).put("enabled", state.enabled).put("revision", state.revision)
            .put("manual", state.manualTopics.toJson()).put("reviewed", JSONArray(state.reviewedQuestIds.toList()))
            .put("accepted", state.accepted?.toJson()).put("pending", state.pending?.toJson())
            .put("events", JSONArray().apply {
                state.events.forEach { e -> put(JSONObject().put("id", e.id).put("questId", e.questId)
                    .put("kind", e.kind.name).put("at", e.occurredAt).put("title", e.title)
                    .put("topic", e.topic.name).put("duration", e.durationMinutes).put("intensity", e.intensity)
                    .put("source", e.source).put("entry", e.entry).put("random", e.random).put("reaction", e.reaction).put("hour", e.localHour)) }
            })
        check(prefs.edit().putString("state", root.toString()).commit()) { "无法保存任务偏好" }
    }

    private fun PreferenceSuggestion.toJson() = JSONObject().put("id", id).put("summary", summary)
        .put("topics", topics.toJson()).put("duration", durationMinutes).put("intensity", intensity)
        .put("evidence", JSONArray(evidenceQuestIds)).put("created", createdAt).put("expires", expiresAt)
        .put("hint", recentHint).put("hintExpires", hintExpiresAt)

    private fun JSONObject.suggestion(): PreferenceSuggestion? = runCatching {
        PreferenceSuggestion(getString("id"), getString("summary"), optJSONObject("topics").topicScores(),
            QuestDuration.parse(opt("duration")), nullableString("intensity"),
            optJSONArray("evidence").let { array -> (0 until (array?.length() ?: 0)).map { array!!.getString(it) } },
            getLong("created"), getLong("expires"), nullableString("hint"), optLong("hintExpires").takeIf { it > 0 })
    }.getOrNull()

    private fun Map<PreferenceTopic, Int>.toJson() = JSONObject().also { json -> forEach { (key, value) -> json.put(key.name, value) } }
    private fun JSONObject?.topicScores(): Map<PreferenceTopic, Int> = PreferenceTopic.entries
        .filter { it != PreferenceTopic.UNKNOWN && this?.has(it.name) == true }
        .associateWith { this!!.optInt(it.name).coerceIn(-2, 2) }
    private fun JSONObject.nullableString(key: String) = if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
    private fun JSONArray?.objects(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
}
