package dev.agent.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.agent.app.AppSettings
import dev.agent.core.provider.DEFAULT_ANTHROPIC_URL
import dev.agent.core.provider.ProviderConfig
import dev.agent.core.provider.ProviderKind
import dev.agent.core.llm.OpenAiClient
import kotlinx.coroutines.launch
import java.util.UUID

/** Lists providers, and opens an editor to add, change or remove one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings(context) }
    var providers by remember { mutableStateOf(settings.providers) }
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }

    val editor = editing
    if (editor != null) {
        ProviderEditor(
            initial = editor,
            onSave = { saved ->
                val updated = if (providers.any { it.id == saved.id }) {
                    providers.map { if (it.id == saved.id) saved else it }
                } else providers + saved
                providers = updated
                settings.providers = updated
                editing = null
            },
            onDelete = if (providers.any { it.id == editor.id } && providers.size > 1) {
                {
                    providers = providers.filter { it.id != editor.id }
                    settings.providers = providers
                    if (settings.activeProviderId == editor.id) settings.activeProviderId = providers.first().id
                    editing = null
                }
            } else null,
            onCancel = { editing = null },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Model providers") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                providers.forEach { provider ->
                    val active = provider.id == settings.activeProviderId
                    Column {
                        Text(
                            "${provider.name}${if (active) "  (active)" else ""}",
                            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "${provider.kind.label} · ${provider.selectedModel.ifBlank { "no model selected" }}",
                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!active) {
                                TextButton(onClick = {
                                    settings.activeProviderId = provider.id
                                    providers = settings.providers
                                }) { Text("Use") }
                            }
                            TextButton(onClick = { editing = provider }) { Text("Edit") }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { editing = newProvider() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add provider") }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Done") } },
    )
}

/** Editor for one provider: endpoint, key, model discovery and manual model entry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderEditor(
    initial: ProviderConfig,
    onSave: (ProviderConfig) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var kind by remember { mutableStateOf(initial.kind) }
    var baseUrl by remember { mutableStateOf(initial.baseUrl.ifBlank { DEFAULT_ANTHROPIC_URL }) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var models by remember { mutableStateOf(initial.models) }
    var selected by remember { mutableStateOf(initial.selectedModel) }
    var manualModel by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (initial.models.isEmpty() && initial.apiKey.isEmpty()) "New provider" else "Edit provider") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ProviderKind.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = kind == option,
                            onClick = {
                                kind = option
                                if (option == ProviderKind.ANTHROPIC && baseUrl.isBlank()) baseUrl = DEFAULT_ANTHROPIC_URL
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, ProviderKind.entries.size),
                        ) { Text(option.label) }
                    }
                }
                OutlinedTextField(
                    baseUrl, { baseUrl = it },
                    label = { Text("Base URL") },
                    supportingText = { Text("For example https://openrouter.ai/api/v1 or http://192.168.1.5:11434/v1") },
                    singleLine = true,
                )
                OutlinedTextField(
                    apiKey, { apiKey = it },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                if (kind == ProviderKind.OPENAI_COMPATIBLE) Button(
                    enabled = baseUrl.isNotBlank(),
                    onClick = {
                        status = "Fetching models…"
                        scope.launch {
                            runCatching {
                                OpenAiClient.listModels(baseUrl, apiKey)
                            }.onSuccess { list ->
                                models = (models + list).distinct()
                                status = "Found ${list.size} models"
                            }.onFailure {
                                status = "Could not list models: ${it.message}"
                            }
                        }
                    },
                ) { Text("Add models from /v1/models") }
                status?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }

                if (models.isNotEmpty()) {
                    Text("Pick the model to use:", style = androidx.compose.material3.MaterialTheme.typography.labelLarge)
                    models.forEach { id ->
                        FilterChip(
                            selected = selected == id,
                            onClick = { selected = id },
                            label = { Text(id) },
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
                OutlinedTextField(
                    manualModel, { manualModel = it },
                    label = { Text("Or enter one model ID") },
                    singleLine = true,
                )
                OutlinedButton(
                    enabled = manualModel.isNotBlank(),
                    onClick = {
                        val id = manualModel.trim()
                        models = (models + id).distinct()
                        selected = id
                        manualModel = ""
                    },
                ) { Text("Add this model") }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank() && baseUrl.isNotBlank(),
                onClick = {
                    onSave(
                        ProviderConfig(
                            id = initial.id,
                            name = name.trim(),
                            kind = kind,
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            models = models,
                            selectedModel = selected.ifBlank { models.firstOrNull().orEmpty() },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCancel) { Text("Cancel") }
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
            }
        },
    )
}

private val ProviderKind.label: String
    get() = when (this) {
        ProviderKind.ANTHROPIC -> "Anthropic"
        ProviderKind.OPENAI_COMPATIBLE -> "OpenAI-compatible"
    }

private fun newProvider() = ProviderConfig(
    id = UUID.randomUUID().toString(),
    name = "",
    kind = ProviderKind.OPENAI_COMPATIBLE,
    baseUrl = "",
)
