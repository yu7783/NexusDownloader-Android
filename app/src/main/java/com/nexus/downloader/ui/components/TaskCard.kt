package com.nexus.downloader.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nexus.downloader.R
import com.nexus.downloader.data.model.DownloadTask
import com.nexus.downloader.data.model.TaskStatus

/** ダウンロード 1 件を表すカード。状態に応じて色と進捗を表示する。 */
@Composable
fun TaskCard(
    task: DownloadTask,
    onRemove: () -> Unit,
    onOpen: () -> Unit = {},
    onShare: () -> Unit = {},
    onRetry: () -> Unit = {},
    /** 実行中タスクを 1 件だけ中止する。 */
    onCancel: () -> Unit = {},
    /** 待機中タスクをキューの先頭へ移動する。 */
    onPrioritize: () -> Unit = {},
    /** 待機中の並び順（1 始まり。0 なら非表示）。 */
    queuePosition: Int = 0,
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = task.progress,
        animationSpec = tween(durationMillis = 400),
        label = "taskProgress",
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = task.url,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    task.addonName?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                StatusPill(
                    status = task.status,
                    label = if (task.status == TaskStatus.QUEUED && queuePosition > 0) {
                        stringResource(R.string.task_status_queued_position, queuePosition)
                    } else {
                        null
                    },
                )
                Spacer(Modifier.width(4.dp))
                if (task.status == TaskStatus.QUEUED) {
                    IconButton(onClick = onPrioritize) {
                        Icon(Icons.Rounded.VerticalAlignTop, contentDescription = stringResource(R.string.action_move_top))
                    }
                }
                if (task.status == TaskStatus.RUNNING) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Rounded.Stop,
                            contentDescription = stringResource(R.string.action_cancel_one),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (task.status == TaskStatus.COMPLETED && task.outputPath != null) {
                    IconButton(onClick = onOpen) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.action_open))
                    }
                    IconButton(onClick = onShare) {
                        Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.action_share))
                    }
                }
                if (task.status == TaskStatus.FAILED ||
                    task.status == TaskStatus.SKIPPED ||
                    task.status == TaskStatus.CANCELED
                ) {
                    IconButton(onClick = onRetry) {
                        Icon(
                            Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.action_retry),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove))
                }
            }

            if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.QUEUED) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (task.status == TaskStatus.QUEUED) 0f else animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            val detail = task.error ?: task.message
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (task.status) {
                        TaskStatus.FAILED, TaskStatus.SKIPPED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * 状態バッジ。
 *
 * @param label 表示名の上書き（待機中の並び順など）。null なら既定の文言。
 */
@Composable
fun StatusPill(status: TaskStatus, label: String? = null) {
    val (statusLabel, color) = when (status) {
        TaskStatus.QUEUED -> stringResource(R.string.task_status_queued) to MaterialTheme.colorScheme.onSurfaceVariant
        TaskStatus.RUNNING -> stringResource(R.string.task_status_running) to MaterialTheme.colorScheme.primary
        TaskStatus.COMPLETED -> stringResource(R.string.task_status_completed) to Color(0xFF2E7D32)
        TaskStatus.FAILED -> stringResource(R.string.task_status_failed) to MaterialTheme.colorScheme.error
        TaskStatus.SKIPPED -> stringResource(R.string.task_status_skipped) to MaterialTheme.colorScheme.tertiary
        TaskStatus.CANCELED -> stringResource(R.string.task_status_canceled) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label ?: statusLabel,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}
