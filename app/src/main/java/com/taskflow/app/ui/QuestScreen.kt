package com.taskflow.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.MilitaryTech
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.taskflow.app.data.Quest
import com.taskflow.app.timer.QuestTimerState
import kotlinx.coroutines.delay
import com.taskflow.app.preference.PreferenceEntry
import com.taskflow.app.preference.PreferenceViewModel

@Composable
fun QuestScreen(viewModel: QuestViewModel) {
    val preferenceViewModel: PreferenceViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val quests by viewModel.quests.collectAsStateWithLifecycle()
    val headline by viewModel.headline.collectAsStateWithLifecycle()
    val isAiGenerating by viewModel.isAiGenerating.collectAsStateWithLifecycle()
    val aiLoadingMessage by viewModel.aiLoadingMessage.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val timerState by viewModel.timerState.collectAsStateWithLifecycle()
    val activeQuests = quests.filter { it.status == "active" }
    val completedQuests = quests.filter { it.status == "completed" }
    val context = LocalContext.current
    var showAiThemeDialog by rememberSaveable { mutableStateOf(false) }
    var aiTheme by rememberSaveable { mutableStateOf("") }
    var completionQuestId by rememberSaveable { mutableStateOf<String?>(null) }
    var completionReaction by rememberSaveable { mutableStateOf("") }
    var pendingTimerQuestId by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingTimerQuestId?.let { questId ->
                activeQuests.firstOrNull { it.id == questId }?.let(viewModel::startQuestTimer)
            }
        } else {
            viewModel.showNotificationPermissionNeeded()
        }
        pendingTimerQuestId = null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0B1020),
                        Color(0xFF151B31),
                        Color(0xFF21153A),
                    )
                )
            )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                HeroPanel(
                    headline = headline,
                    hasActiveQuest = activeQuests.isNotEmpty(),
                    isAiGenerating = isAiGenerating,
                    aiLoadingMessage = aiLoadingMessage,
                    onRollChill = { viewModel.rollQuest(vibe = "chill", intensity = "chill") },
                    onRollChaos = { viewModel.rollQuest(vibe = "chaos", intensity = "chaotic") },
                    onRollApi = { showAiThemeDialog = true },
                    onSummonBoss = { viewModel.summonBossQuest() },
                )
            }

            item {
                StatsPanel(
                    activeCount = summary.activeCount,
                    completedCount = summary.completedCount,
                    bossCount = summary.bossCount,
                    totalXp = summary.totalXp,
                )
            }

            item { PreferenceEntry(preferenceViewModel) }

            item { SectionTitle("进行中的支线", "${activeQuests.size} 个活跃任务") }

            if (activeQuests.isEmpty()) {
                item {
                    EmptyBoard()
                }
            } else {
                items(activeQuests, key = { it.id }) { quest ->
                    QuestCard(
                        quest = quest,
                        actionsEnabled = !isAiGenerating,
                        onComplete = {
                            completionQuestId = quest.id
                            completionReaction = ""
                        },
                        onReroll = { viewModel.rerollQuest(quest) },
                        onArchive = { viewModel.archiveQuest(quest.id) },
                        timerState = timerState?.takeIf { it.questId == quest.id },
                        onStart = {
                            val notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) == PackageManager.PERMISSION_GRANTED
                            if (notificationsAllowed) {
                                viewModel.startQuestTimer(quest)
                            } else {
                                pendingTimerQuestId = quest.id
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        },
                    )
                }
            }

            if (completedQuests.isNotEmpty()) {
                item {
                    SectionTitle("已通关", "最近完成的战绩")
                }
                items(completedQuests.take(6), key = { it.id }) { quest ->
                    CompletedQuestCard(quest)
                }
            }
        }

        if (showAiThemeDialog) {
            AiThemeDialog(
                theme = aiTheme,
                onThemeChange = { aiTheme = it },
                onDismiss = { showAiThemeDialog = false },
                onRandom = {
                    showAiThemeDialog = false
                    aiTheme = ""
                    viewModel.rollQuestWithApi()
                },
                onGenerate = {
                    showAiThemeDialog = false
                    viewModel.rollQuestWithApi(aiTheme)
                    aiTheme = ""
                },
            )
        }

        completionQuestId?.let { questId ->
            CompletionReactionDialog(
                reaction = completionReaction,
                onReactionChange = { completionReaction = it },
                onDismiss = { completionQuestId = null },
                onSkip = {
                    viewModel.completeQuest(questId)
                    completionQuestId = null
                },
                onComplete = {
                    viewModel.completeQuest(questId, completionReaction)
                    completionQuestId = null
                },
            )
        }
    }
}

@Composable
private fun HeroPanel(
    headline: String,
    hasActiveQuest: Boolean,
    isAiGenerating: Boolean,
    aiLoadingMessage: String,
    onRollChill: () -> Unit,
    onRollChaos: () -> Unit,
    onRollApi: () -> Unit,
    onSummonBoss: () -> Unit,
) {
    val canGenerate = !hasActiveQuest && !isAiGenerating
    ElevatedCard(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = Color(0xCC182746)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(Color(0xFF294D87), Color(0xFF8F2EFF), Color(0xFFFF5FA2))
                    )
                )
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Chaos Quest",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Serif,
            )
            Text(
                text = "把拖延、无聊和混乱包装成一张张可执行支线任务。",
                color = Color(0xFFF4EFFF),
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.14f),
            ) {
                Text(
                    text = headline,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Color.White,
                    fontSize = 14.sp,
                )
            }
            if (isAiGenerating) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF08162D).copy(alpha = 0.36f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFFBDF9EA),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(aiLoadingMessage, color = Color(0xFFBDF9EA), fontSize = 13.sp)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActionButton(
                        text = "抽轻支线",
                        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                        onClick = onRollChill,
                        enabled = canGenerate,
                    )
                    ActionButton(
                        text = "抽混沌支线",
                        icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        onClick = onRollChaos,
                        enabled = canGenerate,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActionButton(
                        text = if (isAiGenerating) "AI 生成中" else "AI生成",
                        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                        onClick = onRollApi,
                        enabled = canGenerate,
                    )
                }
                Button(
                    onClick = onSummonBoss,
                    enabled = canGenerate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 56.dp),
                ) {
                    Icon(Icons.Outlined.MilitaryTech, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("召唤 Boss")
                }
            }
        }
    }
}

@Composable
private fun RowScope.ActionButton(
    text: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .defaultMinSize(minHeight = 76.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
        ) {
            val compact = maxWidth < 150.dp
            if (compact) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    icon()
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp,
                        maxLines = 2,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    icon()
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = text,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiThemeDialog(
    theme: String,
    onThemeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onRandom: () -> Unit,
    onGenerate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("AI 生成支线") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("想围绕什么做点事？留空也可以。")
                OutlinedTextField(
                    value = theme,
                    onValueChange = onThemeChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("任务主题（可选）") },
                    placeholder = { Text("例如：收拾衣柜、让我缓一缓") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(onClick = onGenerate) {
                Text("生成任务")
            }
        },
        dismissButton = {
            TextButton(onClick = onRandom) {
                Text("随机来一个")
            }
        },
    )
}

@Composable
private fun CompletionReactionDialog(
    reaction: String,
    onReactionChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSkip: () -> Unit,
    onComplete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("通关感言") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("这件事做完后，感觉怎么样？")
                OutlinedTextField(
                    value = reaction,
                    onValueChange = onReactionChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("写一句也行，留空也没关系") },
                    minLines = 3,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            Button(onClick = onComplete) {
                Text("通关 + XP")
            }
        },
        dismissButton = {
            TextButton(onClick = onSkip) {
                Text("跳过感言")
            }
        },
    )
}

@Composable
private fun StatsPanel(
    activeCount: Int,
    completedCount: Int,
    bossCount: Int,
    totalXp: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatTile("活跃", activeCount.toString(), Color(0xFF5FF2C6), Modifier.weight(1f))
        StatTile("通关", completedCount.toString(), Color(0xFFFFC857), Modifier.weight(1f))
        StatTile("Boss", bossCount.toString(), Color(0xFFFF6B6B), Modifier.weight(1f))
        StatTile("XP", totalXp.toString(), Color(0xFF8B7CFF), Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF151F37)),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(value, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column {
        Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        Text(subtitle, color = Color(0xFFB3BDD4), fontSize = 13.sp)
    }
}

@Composable
private fun QuestCard(
    quest: Quest,
    actionsEnabled: Boolean,
    onComplete: () -> Unit,
    onReroll: () -> Unit,
    onArchive: () -> Unit,
    timerState: QuestTimerState?,
    onStart: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10182B)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = quest.title,
                        color = Color.White,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = quest.description,
                        color = Color(0xFFDCE3F5),
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                    )
                }
                BossOrb(quest.difficulty)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(text = quest.difficulty.uppercase(), color = difficultyColor(quest.difficulty))
                    Badge(text = "${quest.durationMinutes} 分钟", color = Color(0xFF5FF2C6))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(text = quest.theme, color = Color(0xFFFFC857))
                    Badge(text = quest.mode, color = Color(0xFFFF5FA2))
                    Badge(text = "${quest.xp} XP", color = Color(0xFF8B7CFF))
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF1A2746),
            ) {
                Text(
                    text = "奖励: ${quest.reward}",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    color = Color(0xFFEFE8FF),
                    fontSize = 13.sp,
                )
            }

            when {
                timerState?.isFinished == true -> TimerStatus("时间到，回来通关吧。", Color(0xFFFFC857))
                timerState != null -> TimerCountdown(timerState.endAt)
                else -> Button(
                    onClick = onStart,
                    enabled = actionsEnabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("开始执行 ${quest.durationMinutes} 分钟")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onComplete, enabled = actionsEnabled, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("通关")
                }
                IconButton(onClick = onReroll, enabled = actionsEnabled) {
                    Icon(Icons.Filled.Refresh, contentDescription = "再来一个", tint = Color.White)
                }
                IconButton(onClick = onArchive, enabled = actionsEnabled) {
                    Icon(Icons.Outlined.Archive, contentDescription = "归档", tint = Color(0xFFB7C3E0))
                }
            }
        }
    }
}

@Composable
private fun TimerCountdown(endAt: Long) {
    var now by remember(endAt) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endAt) {
        while (now < endAt) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val remainingSeconds = ((endAt - now).coerceAtLeast(0L) + 999) / 1_000
    val minutes = remainingSeconds / 60
    val seconds = remainingSeconds % 60
    TimerStatus("执行中 · 剩余 %02d:%02d".format(minutes, seconds), Color(0xFF5FF2C6))
}

@Composable
private fun TimerStatus(text: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun CompletedQuestCard(quest: Quest) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF18223D),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(quest.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Text(
                    quest.reaction ?: "无感言，默认判定为冷酷通关。",
                    color = Color(0xFFB9C5E4),
                    fontSize = 12.sp,
                )
            }
            Badge(text = "+${quest.xp} XP", color = Color(0xFF5FF2C6))
        }
    }
}

@Composable
private fun EmptyBoard() {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF10182B)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF253356)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color(0xFFFFC857))
            }
            Text("公告栏空了", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                "抽一张支线，或者直接召唤 Boss，别让今天平平无奇地过去。",
                color = Color(0xFFB7C3E0),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    AssistChip(
        onClick = {},
        label = { Text(text, color = color, fontWeight = FontWeight.Bold, fontSize = 11.sp) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.14f),
        ),
    )
}

@Composable
private fun BossOrb(difficulty: String) {
    val color = difficultyColor(difficulty)
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (difficulty == "boss") Icons.Outlined.MilitaryTech else Icons.Filled.Bolt,
            contentDescription = null,
            tint = color,
        )
    }
}

private fun difficultyColor(difficulty: String): Color = when (difficulty) {
    "chill" -> Color(0xFF5FF2C6)
    "chaotic" -> Color(0xFFFF5FA2)
    "boss" -> Color(0xFFFFC857)
    else -> Color(0xFF8B7CFF)
}
