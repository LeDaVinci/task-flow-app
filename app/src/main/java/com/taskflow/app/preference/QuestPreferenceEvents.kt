package com.taskflow.app.preference

import com.taskflow.app.data.Quest
import com.taskflow.app.data.QuestTheme
import com.taskflow.app.logging.AppLog
import kotlinx.coroutines.CancellationException
import java.util.Calendar

fun Quest.interaction(kind: InteractionKind): QuestInteraction = QuestInteraction(
    id = "$id:${kind.name}", questId = id, kind = kind, occurredAt = System.currentTimeMillis(),
    title = title.take(100), topic = PreferenceTopic.parse(preferenceTopic), durationMinutes = durationMinutes,
    intensity = difficulty, source = source, entry = generationEntry ?: source,
    random = requestedTheme.isNullOrBlank(), reaction = reaction?.take(300),
    localHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
)

suspend fun PreferenceRepository.recordSafely(event: QuestInteraction) {
    try { record(event) } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { AppLog.w("Preference", "could not save interaction: kind=${event.kind}") }
}

fun QuestTheme.preferenceTopic(): PreferenceTopic = when (this) {
    QuestTheme.TIDY -> PreferenceTopic.TIDY
    QuestTheme.EXERCISE, QuestTheme.CARE -> PreferenceTopic.CARE
    QuestTheme.PLANNING -> PreferenceTopic.PLANNING
    QuestTheme.MEDITATION, QuestTheme.NATURE -> PreferenceTopic.RECOVERY
    QuestTheme.SOCIAL -> PreferenceTopic.SOCIAL
    else -> PreferenceTopic.EXPLORE
}
