package com.nexus.downloader.ui.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.downloader.data.model.TaskStatus
import com.nexus.downloader.ui.components.ClipboardSuggestionCard
import com.nexus.downloader.ui.components.OptionField
import com.nexus.downloader.ui.components.TaskCard
import com.nexus.downloader.ui.nexusViewModel

/** ダウンロード画面。複数 URL の並列ダウンロードと進捗表示を行う。 */
@Composable
fun DownloadsScreen() {
    val viewModel = nexusViewModel { DownloadsViewModel(it) }
    val context = LocalContext.current

    val url by viewModel.urlText.collectAsStateWithLifecycle()
    val optionValues by viewModel.optionValues.collectAsStateWithLifecycle()
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val isPaused by viewModel.isPaused.collectAsStateWithLifecycle()
    val currentBatch by viewModel.currentBatch.collectAsStateWithLifecycle()
    val suggestion by viewModel.clipboardSuggestion.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 画面表示時にクリップボードの URL を提案する。
    LaunchedEffect(Unit) {
        viewModel.refreshClipboardSuggestion(context)
    }

    LaunchedEffect(message) {
        message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val addon = remember(url, addons) { viewModel.detectAddon() }
    val specs = remember(addon) { addon?.let { viewModel.optionsFor(it.spec.id) }.orEmpty() }

    val activeTasks = tasks.filter { it.isActive }
    // 全体の進捗は「いま表示しているバッチ（キュー）」だけで計算する。
    val batchTasks = tasks.filter { it.batch == currentBatch }
    val batchDone = batchTasks.count { it.isTerminal }
    val overall = if (batchTasks.isEmpty()) 0f else {
        // sumOf は Float 版が無いため Double に変換して合計する。
        // 実行中 + 待機中（一時停止で中断した分の進捗を含む）を合算する。
        val partial = batchTasks.filterNot { it.isTerminal }.sumOf { it.progress.toDouble() }
        ((batchDone + partial) / batchTasks.size).coerceIn(0.0, 1.0).toFloat()
    }
    val pendingTasks = tasks.filter { it.status == TaskStatus.QUEUED }
    val queueActive = activeTasks.isNotEmpty()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { scaffoldPadding ->
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(scaffoldPadding),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column {
                Text(
                    text = "ダウンロード",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "URL を貼り付けて保存。共有やコピーからも取り込めます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // クリップボードに URL があればワンタップで提案する。
        if (suggestion != null) {
            item {
                ClipboardSuggestionCard(
                    url = suggestion!!,
                    onAccept = viewModel::acceptClipboardSuggestion,
                    onDismiss = viewModel::dismissClipboardSuggestion,
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = viewModel::onUrlChange,
                        label = { Text("URL（複数は改行区切り）") },
                        placeholder = { Text("https://example.com/video/1") },
                        minLines = 3,
                        maxLines = 6,
                        trailingIcon = {
                            IconButton(onClick = { viewModel.pasteFromClipboard(context) }) {
                                Icon(Icons.Rounded.ContentPaste, contentDescription = "貼り付け")
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    AnimatedVisibility(
                        visible = addon != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        if (addon != null) {
                            AssistChip(
                                onClick = {},
                                label = { Text("検出: ${addon.spec.name}") },
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        } else if (url.isNotBlank()) {
                            Text(
                                text = "対応するアドオンが見つかりません（直リンク以外はスキップされます）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        if (specs.isNotEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "ダウンロード設定",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        specs.forEach { spec ->
                            OptionField(
                                spec = spec,
                                value = optionValues[spec.key] ?: spec.default,
                                onValueChange = { viewModel.onOptionChange(spec.key, it) },
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }

        item {
            Column {
                Button(
                    onClick = { viewModel.start() },
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null)
                    Text(if (queueActive) "  キューに追加" else "  ダウンロード")
                }

                // 実行中/待機中があるときだけ、一時停止・再開・中止を出す。
                if (queueActive) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        FilledTonalButton(
                            onClick = { if (isPaused) viewModel.resume() else viewModel.pause() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                if (isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = null,
                            )
                            Text(if (isPaused) "  再開" else "  一時停止")
                        }
                        FilledTonalButton(
                            onClick = { viewModel.cancelAll() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Stop, contentDescription = null)
                            Text("  すべて中止")
                        }
                    }
                }
            }
        }

        if (queueActive) {
            item {
                Column {
                    Text(
                        text = buildString {
                            append("全体の進捗  ${(overall * 100).toInt()}%")
                            if (batchTasks.isNotEmpty()) {
                                append("（${batchDone}/${batchTasks.size} 件）")
                            }
                            if (isPaused) append("  一時停止中")
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { overall },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                    )
                }
            }
        }

        if (tasks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = if (pendingTasks.isEmpty()) {
                            "キュー（${tasks.size}）"
                        } else {
                            "キュー（待機 ${pendingTasks.size} 件 / 全体 ${tasks.size}）"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    FilledTonalButton(onClick = { viewModel.clearFinished() }) {
                        Icon(Icons.Rounded.DeleteSweep, contentDescription = null)
                        Text(" 完了を消す")
                    }
                }
            }
            items(items = tasks, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onRemove = { viewModel.removeTask(task.id) },
                    onOpen = { viewModel.openTask(context, task) },
                    onShare = { viewModel.shareTask(context, task) },
                    onRetry = { viewModel.retryTask(task) },
                    onCancel = { viewModel.cancelTask(task) },
                    onPrioritize = { viewModel.prioritizeTask(task) },
                    queuePosition = pendingTasks.indexOfFirst { it.id == task.id } + 1,
                    modifier = Modifier.animateItem(),
                )
            }
        }
    }
    }
}
