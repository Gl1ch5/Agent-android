package dev.agent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.agent.app.ui.AgentTheme
import dev.agent.app.ui.ChatScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentTheme { ChatScreen() }
        }
    }
}
