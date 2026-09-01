package com.taskflow.app.timer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.taskflow.app.ChaosQuestApp
import com.taskflow.app.MainActivity
import com.taskflow.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.min

class QuestTimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timerJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            timerJob?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val state = ChaosQuestApp.instance.questTimerController.timerState.value
            ?: run {
                stopSelf()
                return START_NOT_STICKY
            }
        if (state.isFinished) {
            showFinishedNotification(state.questTitle)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannels()
        startForeground(NOTIFICATION_ID, runningNotification(state.questTitle, state.endAt))
        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                val remaining = state.endAt - System.currentTimeMillis()
                if (remaining <= 0L) break
                notificationManager().notify(NOTIFICATION_ID, runningNotification(state.questTitle, state.endAt))
                delay(min(remaining, UPDATE_INTERVAL_MILLIS))
            }
            ChaosQuestApp.instance.questTimerController.markFinished(state.questId)
            stopForeground(STOP_FOREGROUND_REMOVE)
            showFinishedNotification(state.questTitle)
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        timerJob?.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannels() {
        val manager = notificationManager()
        manager.createNotificationChannel(
            NotificationChannel(RUNNING_CHANNEL_ID, "任务计时", NotificationManager.IMPORTANCE_LOW).apply {
                description = "进行中任务的倒计时"
                setSound(null, null)
            },
        )
        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(FINISHED_CHANNEL_ID, "任务完成提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "任务计时结束时响铃提醒"
                setSound(sound, attributes)
                enableVibration(true)
            },
        )
    }

    private fun runningNotification(title: String, endAt: Long): android.app.Notification {
        return NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("正在执行：$title")
            .setContentText("剩余 ${remainingLabel(endAt)}")
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(endAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .build()
    }

    private fun showFinishedNotification(title: String) {
        createChannels()
        notificationManager().notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, FINISHED_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("时间到：$title")
                .setContentText("任务时间已到，回来通关吧。")
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build(),
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun remainingLabel(endAt: Long): String {
        val minutes = ceil(((endAt - System.currentTimeMillis()).coerceAtLeast(0L)) / 60_000.0).toInt()
        return "$minutes 分钟"
    }

    companion object {
        const val ACTION_START = "com.taskflow.app.action.START_QUEST_TIMER"
        const val ACTION_STOP = "com.taskflow.app.action.STOP_QUEST_TIMER"
        private const val RUNNING_CHANNEL_ID = "quest_timer_running"
        private const val FINISHED_CHANNEL_ID = "quest_timer_finished"
        private const val NOTIFICATION_ID = 2100
        private const val UPDATE_INTERVAL_MILLIS = 30_000L
    }
}
