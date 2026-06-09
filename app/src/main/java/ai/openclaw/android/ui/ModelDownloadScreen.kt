package ai.openclaw.android.ui

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
import androidx.compose.ui.unit.dp
import ai.openclaw.android.voice.ModelDownloadManager
import kotlinx.coroutines.launch

/**
 * ModelDownloadScreen — 本地 STT/TTS 模型管理。
 *
 * 功能：
 * - 列出可用模型（名称、描述、大小、状态）
 * - 下载/取消下载
 * - 显示下载进度
 * - 已下载模型可删除
 */
@Composable
fun ModelDownloadScreen(
    modelManager: ModelDownloadManager,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val models = remember { modelManager.getAvailableModels(context) }

    // Track download state per model using a MutableStateMap
    val downloadStates = remember { mutableStateMapOf<String, ModelDownloadManager.DownloadState>() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "⚡ 模型管理",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (models.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无可用模型",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(models, key = { it.id }) { model ->
                    val state = downloadStates[model.id]
                    ModelCard(
                        model = model,
                        state = state,
                        onDownload = {
                            scope.launch {
                                modelManager.downloadModel(model).collect { downloadState ->
                                    downloadStates[model.id] = downloadState
                                }
                            }
                        },
                        onCancel = { /* cancel not yet implemented in backend */ },
                        onDelete = {
                            scope.launch {
                                val success = modelManager.deleteModel(model)
                                if (success) {
                                    downloadStates.remove(model.id)
                                } else {
                                    downloadStates[model.id] = ModelDownloadManager.DownloadState(
                                        modelId = model.id,
                                        status = ModelDownloadManager.DownloadState.Status.Failed,
                                        error = "删除失败"
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelDownloadManager.ModelInfo,
    state: ModelDownloadManager.DownloadState?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
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
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = model.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatSize(model.sizeBytes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                // Status badge / Action button
                when (state?.status) {
                    null, ModelDownloadManager.DownloadState.Status.Idle, ModelDownloadManager.DownloadState.Status.Failed -> {
                        Button(onClick = onDownload) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下载")
                        }
                    }
                    ModelDownloadManager.DownloadState.Status.Downloading,
                    ModelDownloadManager.DownloadState.Status.Extracting,
                    ModelDownloadManager.DownloadState.Status.Verifying -> {
                        Column(horizontalAlignment = Alignment.End) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.width(120.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${state.status.name} · ${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) {
                                Text("取消")
                            }
                        }
                    }
                    ModelDownloadManager.DownloadState.Status.Complete -> {
                        Column(horizontalAlignment = Alignment.End) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "已下载",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            OutlinedButton(onClick = onDelete) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("删除")
                            }
                        }
                    }
                }
            }

            // Required files checklist for completed models
            if (state?.status == ModelDownloadManager.DownloadState.Status.Complete) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "模型文件",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                model.requiredFiles.forEach { file ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = file,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
        else -> String.format("%.1fGB", bytes / (1024.0 * 1024.0 * 1024.0))
    }
}
