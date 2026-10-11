package dev.agent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.agent.app.ChatItem
import dev.agent.app.ChatViewModel
import dev.agent.app.PendingApproval

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel = viewModel()) {
    val transcript by viewModel.items.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val approval by viewModel.approval.collectAsStateWithLifecycle()
    var draft by rememberSaveable { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent") },
                actions = {
                    TextButton(onClick = viewModel::newChat, enabled = !busy) { Text("New") }
                    TextButton(onClick = { showSettings = true }) { Text("Settings") }
                },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask the agent to read, change or test code") },
                    maxLines = 5,
                )
                Button(
                    onClick = {
                        viewModel.send(draft)
                        draft = ""
                    },
                    enabled = !busy && draft.isNotBlank(),
                ) { Text("Send") }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(transcript, key = { _, item -> item.id }) { _, item -> ChatRow(item) }
            if (busy) {
                item {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                }
            }
        }
    }

    approval?.let { pending ->
        ApprovalDialog(pending, onDecide = viewModel::resolveApproval)
    }
    if (showSettings) {
        ProviderSettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
private fun ChatRow(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Text(item.text, modifier = Modifier.padding(12.dp))
        }
        is ChatItem.Assistant -> Text(item.text, modifier = Modifier.padding(horizontal = 4.dp))
        is ChatItem.Error -> Text(
            item.text,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        is ChatItem.Tool -> ToolCard(item)
    }
}

@Composable
private fun ToolCard(item: ChatItem.Tool) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(item.name, style = MaterialTheme.typography.labelLarge)
            Text(
                item.input.take(400),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            item.output?.let { output ->
                Text(
                    output.take(1200),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 6.dp).verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}

@Composable
private fun ApprovalDialog(pending: PendingApproval, onDecide: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDecide(false) },
        title = { Text("Allow ${pending.toolName}?") },
        text = {
            Text(
                pending.input.take(1500),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = { Button(onClick = { onDecide(true) }) { Text("Allow") } },
        dismissButton = { OutlinedButton(onClick = { onDecide(false) }) { Text("Deny") } },
    )
}
