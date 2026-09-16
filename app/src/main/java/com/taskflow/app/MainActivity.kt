package com.taskflow.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.lifecycle.ViewModelProvider
import com.taskflow.app.ui.QuestScreen
import com.taskflow.app.ui.QuestViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: QuestViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )

        viewModel = ViewModelProvider(this)[QuestViewModel::class.java]

        setContent {
            val palette = if (isSystemInDarkTheme()) {
                darkColorScheme(
                    primary = NeonMint,
                    secondary = BossGold,
                    tertiary = PlasmaPink,
                )
            } else {
                lightColorScheme(
                    primary = NightBlue,
                    secondary = PlasmaPink,
                    tertiary = BossGold,
                )
            }

            MaterialTheme(colorScheme = palette) {
                QuestScreen(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshDailyState()
    }
}

private val NightBlue = androidx.compose.ui.graphics.Color(0xFF10203A)
private val NeonMint = androidx.compose.ui.graphics.Color(0xFF5FF2C6)
private val PlasmaPink = androidx.compose.ui.graphics.Color(0xFFFF5FA2)
private val BossGold = androidx.compose.ui.graphics.Color(0xFFFFC857)
