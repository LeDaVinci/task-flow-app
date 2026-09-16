package com.taskflow.app.data

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class QuestDurationTest {
    @Test fun acceptsActualIntegerMinutesWithoutFifteenMinuteRounding() {
        for (minutes in 5..30) {
            assertEquals(minutes, QuestDuration.parse(minutes))
            assertEquals(minutes, QuestDuration.normalize(minutes))
        }
        listOf(null, "10", 0, -10, 31, 10.5, Double.NaN, Double.POSITIVE_INFINITY).forEach {
            assertNull(QuestDuration.parse(it))
        }
        assertEquals(5, QuestDuration.normalize(0))
        assertEquals(30, QuestDuration.normalize(Int.MAX_VALUE))
    }

    @Test fun breathingTaskUsesTenMinutesEvenWithLongDurationPreference() {
        val planner = QuestBlueprintPlanner(Random(1))
        val quests = (1..100).map {
            planner.plan("chill", "chill", null, false, "meditation", preferredDurationMinutes = 30)
        }
        val breathing = quests.filter { "20 次" in it.description }
        assertTrue(breathing.isNotEmpty())
        breathing.forEach {
            assertEquals(10, it.durationMinutes)
            assertTrue(it.reward.endsWith("+10 XP"))
        }
    }

    @Test fun durationDependsOnActionNotDifficultyOrBoss() {
        val planner = QuestBlueprintPlanner(Random(2))
        for (boss in listOf(false, true)) {
            for (difficulty in listOf("chill", "spicy", "chaotic")) {
                val quest = planner.plan("random", difficulty, null, boss, "expression")
                assertEquals(5, quest.durationMinutes)
                assertTrue(quest.reward.endsWith("+5 XP"))
            }
        }
        val tidy = (1..100).map { planner.plan("random", "spicy", null, false, "tidy") }
        assertEquals(setOf(10, 15), tidy.map { it.durationMinutes }.toSet())
    }

    @Test fun explicitDurationIsNotRoundedAndOverridesPreference() {
        val quest = QuestBlueprintPlanner(Random(3)).plan("random", "spicy", 12, false, "tidy", preferredDurationMinutes = 30)
        assertEquals(12, quest.durationMinutes)
        assertTrue(quest.reward.endsWith("+12 XP"))
    }

    @Test fun rewardCannotKeepStaleBlueprintXp() {
        assertEquals("平静值 +10 XP", QuestDuration.rewardText("平静值 +23 XP", 10))
        assertEquals("平静值 +7 XP", QuestDuration.rewardText("平静值 ＋15 xp", 7))
        assertEquals("一点平静", QuestDuration.rewardText("一点平静", 10))
    }
}
