package dev.agent.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agent.app.ui.AgentTheme
import dev.agent.app.ui.ChatScreen
import dev.agent.app.ui.ProjectsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AgentTheme { AppRoot() }
        }
    }
}

/** Shows the project list, or the chat of the open project. Back returns to the list. */
@Composable
private fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val open by viewModel.openProject.collectAsState()
    val project = open
    BackHandler(enabled = project != null) { viewModel.closeProject() }
    if (project == null) {
        ProjectsScreen(viewModel)
    } else {
        ChatScreen(viewModel, project)
    }
}
