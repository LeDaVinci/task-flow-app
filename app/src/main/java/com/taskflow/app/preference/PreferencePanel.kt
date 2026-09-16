package com.taskflow.app.preference

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PreferenceEntry(viewModel: PreferenceViewModel) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(onClick = { open = true }, shape = MaterialTheme.shapes.large, color = Color(0xFF18223D)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("任务偏好", color = Color.White, fontWeight = FontWeight.Bold)
            Text(
                when {
                    !state.enabled -> "让支线更懂你 · 点此了解并开启"
                    state.pending != null -> "新的偏好建议已准备好，看看是否合适"
                    state.shouldInvite -> "已积累 ${state.effectiveTaskCount} 次任务选择，生成一份偏好看看？"
                    state.accepted != null -> "${state.accepted!!.summary} · 查看与调整"
                    state.canSummarize -> "已积累 ${state.effectiveTaskCount} 个有效任务 · 查看与调整"
                    else -> "正在了解你的选择 · ${state.effectiveTaskCount}/8 个有效任务"
                }, color = Color(0xFFB9C5E4), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (open) PreferenceSheet(viewModel, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferenceSheet(viewModel: PreferenceViewModel, onDismiss: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val generating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val error by viewModel.errorMessage.collectAsStateWithLifecycle()
    var includeReactions by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.refresh() }
    val weights = remember(state.events) { PreferenceRuleEngine().calculate(state.events, System.currentTimeMillis()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("你的任务偏好", style = MaterialTheme.typography.titleLarge)
            Text("开启后，在本机记住任务选择、完成情况与可选感言，逐步调整随机推荐。明确输入的主题始终优先。记录保留最近 30 天，最多 200 个任务。")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("学习我的任务偏好", Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = { viewModel.setEnabled(it) })
            }
            if (!state.enabled) Text("当前使用默认随机推荐。关闭期间不记录新行为；再次开启后可继续使用尚未过期的偏好。")
            if (state.enabled) {
                Text("推荐方向", style = MaterialTheme.typography.titleMedium)
                PreferenceTopic.entries.filter { it != PreferenceTopic.UNKNOWN }.forEach { topic ->
                    val manual = state.manualTopics[topic] ?: 0
                    val tasks = state.events.filter { it.topic == topic }
                    val completed = tasks.count { it.kind == InteractionKind.COMPLETED }
                    val skipped = tasks.count { it.kind == InteractionKind.REROLLED || it.kind == InteractionKind.ARCHIVED }
                    Column {
                        Text(topic.label, fontWeight = FontWeight.Bold)
                        Text("完成 $completed 次 · 换掉或放下 $skipped 次", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val label = when {
                                manual > 0 -> "你希望多一点"
                                manual < 0 -> "你希望少一点"
                                (weights.topics[topic] ?: 0.0) > 0.2 -> "近期较适合"
                                (weights.topics[topic] ?: 0.0) < -0.1 -> "近期少推荐"
                                else -> "均衡探索"
                            }
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { viewModel.adjust(topic, -1) }, enabled = manual > -2 && !generating) { Text("少一点") }
                            TextButton(onClick = { viewModel.adjust(topic, 1) }, enabled = manual < 2 && !generating) { Text("多一点") }
                        }
                    }
                }
                val preferredDuration = weights.durations.maxByOrNull { it.value }?.takeIf { it.value > 0.1 }?.key
                Text(preferredDuration?.let { "近期更容易完成 $it 分钟的任务。" } ?: "时长偏好还在积累，暂时保持原来的任务时长。")
                val preferredIntensity = weights.intensities.filterKeys { it == "chill" || it == "spicy" }
                    .maxByOrNull { it.value }?.takeIf { it.value > 0.1 }?.key
                preferredIntensity?.let { Text(if (it == "chill") "近期更适合低压力行动。" else "近期适合适度投入的行动。") }
                val frequentPeriod = state.events.filter { it.kind == InteractionKind.COMPLETED }.mapNotNull { it.localHour }
                    .groupingBy { hour -> when (hour) { in 6..11 -> "上午"; in 12..17 -> "下午"; in 18..23 -> "晚上"; else -> "深夜" } }
                    .eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 3 }
                frequentPeriod?.let { Text("你最近常在${it.key}完成任务。") }
                state.accepted?.let {
                    Text("已采用的 AI 偏好", style = MaterialTheme.typography.titleMedium)
                    Text(it.summary)
                }
                Text("让 AI 整理偏好", style = MaterialTheme.typography.titleMedium)
                Text("完成、换掉或归档 8 个不同任务后可生成。点击生成会将近期任务记录发送给当前 AI 服务；只有采用后，建议才影响推荐。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = includeReactions, onCheckedChange = { includeReactions = it }, enabled = !generating)
                    Text("包含通关感言（可选）")
                }
                if (generating) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("正在翻阅你的支线足迹…")
                }
                Button(onClick = { viewModel.generate(includeReactions) }, enabled = state.canSummarize && !generating) {
                    Text(if (state.pending != null) "重新生成" else "生成偏好摘要（${state.effectiveTaskCount}/8）")
                }
                state.pending?.let { suggestion ->
                    Text("待确认建议", fontWeight = FontWeight.Bold)
                    Text(suggestion.summary)
                    val evidenceTitles = state.events.filter { it.questId in suggestion.evidenceQuestIds }.distinctBy { it.questId }.take(3).joinToString("、") { it.title }
                    Text("参考任务：$evidenceTitles", style = MaterialTheme.typography.bodySmall)
                    Row {
                        Button(onClick = { viewModel.adopt(suggestion.id) }, enabled = !generating) { Text("采用") }
                        TextButton(onClick = { viewModel.dismiss() }) { Text("暂不采用") }
                    }
                }
            }
            (error ?: state.message)?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = { confirmClear = true }) { Text("清空记录与偏好") }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("清空任务偏好？") },
        text = { Text("删除学习记录、AI 建议和手动调整。当前任务与 XP 保留；清空后重新均衡推荐。") },
        confirmButton = { TextButton(onClick = { viewModel.clear(); confirmClear = false }) { Text("清空") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
    )
}
