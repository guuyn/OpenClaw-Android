package ai.openclaw.android.voice.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.openclaw.android.voice.ModelDownloadManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Compose screen for downloading sherpa-onnx models.
 *
 * Shows available STT and TTS models with download progress.
 * Typically shown on first launch when models are not yet available.
 */
@Composable
fun ModelDownloadScreen(
    onAllModelsReady: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = ModelDownloadManager.getAvailableModels(context)

    // Track download state per model
    var downloadStates by remember {
        mutableStateOf<Map<String, ModelDownloadManager.DownloadState>>(emptyMap())
    }

    // Check initial readiness
    val readyStates = models.associate { model ->
        model.id to ModelDownloadManager.isModelReady(model)
    }

    val allReady = readyStates.values.all { it }

    LaunchedEffect(allReady) {
        if (allReady) {
            onAllModelsReady()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "下载语音模型",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "语音交互需要下载本地模型，所有处理均在设备上完成，无需联网",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        // WiFi notice
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "建议在 WiFi 环境下下载，模型总计约 150-300MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        // Storage info
        val availableStorage = ModelDownloadManager.getAvailableStorageBytes()
        Text(
            text = "可用存储空间: ${formatBytes(availableStorage)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        // Model list
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(models) { model ->
                val state = downloadStates[model.id]
                val isReady = ModelDownloadManager.isModelReady(model)

                ModelDownloadCard(
                    model = model,
                    state = state,
                    isReady = isReady,
                    onDownload = {
                        scope.launch {
                            ModelDownloadManager.downloadModel(model).collectLatest { ds ->
                                downloadStates = downloadStates + (model.id to ds)
                                if (ds.status == ModelDownloadManager.DownloadState.Status.Complete) {
                                    // Check if all models are now ready
                                    val allDone = models.all { m ->
                                        m.id == model.id || ModelDownloadManager.isModelReady(m)
                                    }
                                    if (allDone) {
                                        onAllModelsReady()
                                    }
                                }
                            }
                        }
                    },
                    onDelete = {
                        scope.launch {
                            ModelDownloadManager.deleteModel(model)
                            downloadStates = downloadStates - model.id
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ModelDownloadCard(
    model: ModelDownloadManager.ModelInfo,
    state: ModelDownloadManager.DownloadState?,
    isReady: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                when {
                    isReady -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("已就绪", color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = onDelete) {
                                Text("删除")
                            }
                        }
                    }
                    state?.status == ModelDownloadManager.DownloadState.Status.Failed -> {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                "下载失败: ${state.error}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(onClick = onDownload, modifier = Modifier.padding(top = 4.dp)) {
                                Text("重试")
                            }
                        }
                    }
                    state?.status == ModelDownloadManager.DownloadState.Status.Downloading -> {
                        Column(
                            modifier = Modifier.width(120.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.size(32.dp),
                            )
                            Text(
                                "${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    state?.status == ModelDownloadManager.DownloadState.Status.Extracting -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Text("解压中...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state?.status == ModelDownloadManager.DownloadState.Status.Verifying -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                            Text("验证中...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    else -> {
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下载 (${formatBytes(model.sizeBytes)})")
                        }
                    }
                }
            }

            // Progress bar for active downloads
            if (state?.status == ModelDownloadManager.DownloadState.Status.Downloading) {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
                if (state.totalBytes > 0) {
                    Text(
                        "${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024 * 1024 -> String.format("%.0f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
