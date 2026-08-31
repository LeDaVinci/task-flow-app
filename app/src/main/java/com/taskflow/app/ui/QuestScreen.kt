package com.taskflow.app.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskflow.app.data.Quest

@Composable
fun QuestScreen(viewModel: QuestViewModel) {
    val quests by viewModel.quests.collectAsStateWithLifecycle()
    val headline by viewModel.headline.collectAsStateWithLifecycle()
    val localModelStatus by viewModel.localModelStatus.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val activeQuests = quests.filter { it.status == "active" }
    val completedQuests = quests.filter { it.status == "completed" }

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
                    localModelStatus = localModelStatus,
                    onRollChill = { viewModel.rollQuest(vibe = "chill", intensity = "chill") },
                    onRollChaos = { viewModel.rollQuest(vibe = "chaos", intensity = "chaotic", theme = "adventure") },
                    onRollLocalModel = { viewModel.rollQuestWithLocalModel(vibe = "chaos", intensity = "chaotic", theme = "story") },
                    onRollApi = { viewModel.rollQuestWithApi(vibe = "chaos", intensity = "chaotic", theme = "story") },
                    onSummonBoss = viewModel::summonBossQuest,
                    onRefreshLocalModelStatus = viewModel::refreshLocalModelStatus,
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

            item {
                SectionTitle("进行中的支线", "${activeQuests.size} 个活跃任务")
            }

            if (activeQuests.isEmpty()) {
                item {
                    EmptyBoard()
                }
            } else {
                items(activeQuests, key = { it.id }) { quest ->
                    QuestCard(
                        quest = quest,
                        onComplete = { viewModel.completeQuest(quest.id) },
                        onReroll = { viewModel.rerollQuest(quest.id) },
                        onArchive = { viewModel.archiveQuest(quest.id) },
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
    }
}

@Composable
private fun HeroPanel(
    headline: String,
    localModelStatus: String,
    onRollChill: () -> Unit,
    onRollChaos: () -> Unit,
    onRollLocalModel: () -> Unit,
    onRollApi: () -> Unit,
    onSummonBoss: () -> Unit,
    onRefreshLocalModelStatus: () -> Unit,
) {
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
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF08162D).copy(alpha = 0.35f),
                onClick = onRefreshLocalModelStatus,
            ) {
                Text(
                    text = localModelStatus,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = Color(0xFFBDF9EA),
                    fontSize = 13.sp,
                )
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
                    )
                    ActionButton(
                        text = "抽混沌支线",
                        icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        onClick = onRollChaos,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ActionButton(
                        text = "API 生成",
                        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                        onClick = onRollApi,
                    )
                    ActionButton(
                        text = "本地生成",
                        icon = { Icon(Icons.Filled.AutoAwesome, contentDescription = null) },
                        onClick = onRollLocalModel,
                    )
                }
                Button(
                    onClick = onSummonBoss,
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
) {
    Button(
        onClick = onClick,
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
    onComplete: () -> Unit,
    onReroll: () -> Unit,
    onArchive: () -> Unit,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onComplete, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("通关")
                }
                IconButton(onClick = onReroll) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重掷", tint = Color.White)
                }
                IconButton(onClick = onArchive) {
                    Icon(Icons.Outlined.Archive, contentDescription = "归档", tint = Color(0xFFB7C3E0))
                }
            }
        }
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
