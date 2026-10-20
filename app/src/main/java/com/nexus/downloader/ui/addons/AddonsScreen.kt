package com.nexus.downloader.ui.addons

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.downloader.data.model.AddonOrigin
import com.nexus.downloader.data.model.RegisteredAddon
import com.nexus.downloader.ui.nexusViewModel

/** アドオン管理画面。内蔵アドオン一覧と、ローカルファイルからの追加。 */
@Composable
fun AddonsScreen() {
    val viewModel = nexusViewModel { AddonsViewModel(it) }
    val addons by viewModel.addons.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showHelp by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importFromUri(it) } }

    LaunchedEffect(notice) {
        notice?.let {
            snackbarHostState.showSnackbar(it.message)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column {
                    Text(
                        text = "アドオン",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "内蔵アドオンと、ローカルの定義ファイルから追加したアドオン。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = { picker.launch(arrayOf("application/json", "text/*", "*/*")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Text(" ファイルから追加")
                    }
                    TextButton(onClick = { showHelp = true }) {
                        Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null)
                        Text(" 書式")
                    }
                }
            }

            items(items = addons, key = { it.spec.id }) { addon ->
                AddonCard(addon = addon, onDelete = { viewModel.delete(addon) })
            }
        }
    }

    if (showHelp) {
        AddonFormatDialog(onDismiss = { showHelp = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddonCard(addon: RegisteredAddon, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = addon.spec.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "v${addon.spec.version} · ${addon.spec.type} · " +
                            if (addon.origin == AddonOrigin.BUILTIN) "内蔵" else "外部",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (addon.origin == AddonOrigin.IMPORTED) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "削除")
                    }
                }
            }
            if (addon.spec.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = addon.spec.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (addon.spec.urlPatterns.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    addon.spec.urlPatterns.take(8).forEach { pattern ->
                        AssistChip(onClick = {}, label = { Text(pattern) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AddonFormatDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
        title = { Text("アドオン定義（JSON）") },
        text = {
            Text(
                text = """
                    {
                      "id": "my_site",
                      "name": "My Site",
                      "version": "1.0.0",
                      "urlPatterns": ["example.com"],
                      "type": "DIRECT",
                      "description": "任意の直リンクを保存"
                    }

                    type:
                      DIRECT  : 直リンクを保存
                      YTDLP   : yt-dlp に任せる
                      IWARA   : Iwara 動画
                      GALLERY : 画像を一括取得
                                 （gallery セレクタを指定）

                    取り込み前に危険キーワードを走査し、
                    危険な記述があれば拒否します。
                """.trimIndent(),
                style = MaterialTheme.typography.bodySmall,
            )
        },
    )
}
