package com.nexus.downloader.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.downloader.data.file.SaveLocation
import com.nexus.downloader.data.model.AppConfig
import com.nexus.downloader.data.model.Badge
import com.nexus.downloader.data.model.BadgeCatalog
import com.nexus.downloader.data.model.StorageTarget
import com.nexus.downloader.data.model.ThemeMode
import com.nexus.downloader.ui.nexusViewModel

private val ACCENT_PRESETS = listOf(
    "#3B5BDB" to "Indigo",
    "#0078D4" to "Azure",
    "#00897B" to "Teal",
    "#7C4DFF" to "Violet",
    "#D81B60" to "Rose",
    "#F4511E" to "Orange",
)

/** 設定画面。変更は即時保存される。 */
@Composable
fun SettingsScreen() {
    val viewModel = nexusViewModel { SettingsViewModel(it) }
    val config by viewModel.config.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "設定",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item { AppearanceSection(config, viewModel) }
        item { MobileSection(config, viewModel) }
        item { DownloadSection(config, viewModel) }
        item { EngineSection(viewModel) }
        item { SetupSection(viewModel) }
        item { SecuritySection(config, viewModel) }
        item { StatsSection(config, viewModel) }
        item { BadgesSection(config) }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(config: AppConfig, viewModel: SettingsViewModel) {
    SettingsCard(title = "外観") {
        Text("テーマ", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        val modes = ThemeMode.entries
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = config.theme == mode,
                    onClick = { viewModel.setTheme(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(
                        when (mode) {
                            ThemeMode.SYSTEM -> "システム"
                            ThemeMode.LIGHT -> "ライト"
                            ThemeMode.DARK -> "ダーク"
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("ダイナミックカラー", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "OS の壁紙に合わせた配色（Android 12+）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = config.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
        }

        if (!config.dynamicColor) {
            Spacer(Modifier.height(16.dp))
            Text("アクセントカラー", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ACCENT_PRESETS.forEach { (hex, _) ->
                    val selected = config.accentColor.equals(hex, ignoreCase = true)
                    Surface(
                        shape = CircleShape,
                        color = runCatching { Color(android.graphics.Color.parseColor(hex)) }
                            .getOrDefault(MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape),
                        onClick = { viewModel.setAccentColor(hex) },
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun DownloadSection(config: AppConfig, viewModel: SettingsViewModel) {
    var timeout by remember(config.timeout) { mutableStateOf(config.timeout.toString()) }

    SettingsCard(title = "ダウンロード") {
        SaveLocationFields(config, viewModel)

        Spacer(Modifier.height(20.dp))
        Text(
            text = "並列数: " + if (config.parallel == 0) "自動" else config.parallel.toString(),
            style = MaterialTheme.typography.labelLarge,
        )
        Slider(
            value = config.parallel.toFloat(),
            onValueChange = { viewModel.setParallel(it.toInt()) },
            valueRange = 0f..16f,
            steps = 15,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = timeout,
            onValueChange = { timeout = it.filter(Char::isDigit); it.toIntOrNull()?.let(viewModel::setTimeout) },
            label = { Text("タイムアウト（秒）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** モバイルならではの便利設定。 */
@Composable
private fun MobileSection(config: AppConfig, viewModel: SettingsViewModel) {
    SettingsCard(title = "モバイル") {
        SwitchRow(
            title = "Wi-Fi 接続時のみ",
            description = "モバイル通信でのダウンロードを防止します。",
            checked = config.wifiOnly,
            onCheckedChange = viewModel::setWifiOnly,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "共有から自動ダウンロード",
            description = "共有時のオプション画面を下の秒数後に自動で開始します（オフなら手動で開始）。",
            checked = config.autoDownloadOnShare,
            onCheckedChange = viewModel::setAutoDownloadOnShare,
        )
        Spacer(Modifier.height(12.dp))
        ShareAutoStartField(config, viewModel)
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "完了時に通知",
            description = "ダウンロードが終わったら通知を表示します。",
            checked = config.notifyOnComplete,
            onCheckedChange = viewModel::setNotifyOnComplete,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            title = "クリップボードの URL を提案",
            description = "コピーしたリンクをダウンロード画面でワンタップ入力できます。",
            checked = config.clipNotify,
            onCheckedChange = viewModel::setClipNotify,
        )
    }
}

/**
 * 保存先の選択 UI。
 *
 * 既定はアプリ専用フォルダ（サブフォルダ名と外部 / 内部を選べる）。
 * 「フォルダを選ぶ」で SAF のフォルダを選ぶと、完了したファイルはそこへ移動する。
 */
@Composable
private fun SaveLocationFields(config: AppConfig, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val tree = SaveLocation.treeUri(config)
    var subDir by remember(config.downloadDir) { mutableStateOf(config.downloadDir) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.setSaveTreeUri(context, uri)
    }

    Text("保存先", style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(4.dp))
    Text(
        text = SaveLocation.describe(context, config),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = tree?.toString() ?: SaveLocation.appRootDir(context, config).absolutePath,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { pickFolder.launch(tree) },
            modifier = Modifier.weight(1f),
        ) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
            Text("  フォルダを選ぶ")
        }
        if (tree != null) {
            OutlinedButton(
                onClick = { viewModel.setSaveTreeUri(context, null) },
                modifier = Modifier.weight(1f),
            ) {
                Text("既定に戻す")
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(
        text = if (tree != null) {
            "完了したファイル / フォルダは選択したフォルダへ移動します。"
        } else {
            "未選択のときは下のアプリ専用フォルダへ保存します（任意のフォルダを選ぶと完了後にそこへ移動）。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // アプリ専用フォルダを使う場合のみ、種類とサブフォルダ名を編集できる。
    if (tree == null) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = subDir,
            onValueChange = {
                subDir = it
                viewModel.setDownloadDir(it)
            },
            label = { Text("サブフォルダ名（空なら直下）") },
            supportingText = { Text("「/」で区切ると階層を作れます（例: 動画/2026）。") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            StorageTarget.entries.forEachIndexed { index, target ->
                SegmentedButton(
                    selected = config.storageTarget == target,
                    onClick = { viewModel.setStorageTarget(target) },
                    shape = SegmentedButtonDefaults.itemShape(index, StorageTarget.entries.size),
                ) {
                    Text(
                        when (target) {
                            StorageTarget.EXTERNAL -> "外部ストレージ"
                            StorageTarget.INTERNAL -> "内部ストレージ"
                        },
                    )
                }
            }
        }
    }
}

/**
 * 共有ポップアップの自動開始までの秒数（1〜600）。
 *
 * 「共有から自動ダウンロード」が OFF のときは無効化する（ポップアップは手動開始のみ）。
 */
@Composable
private fun ShareAutoStartField(config: AppConfig, viewModel: SettingsViewModel) {
    var seconds by remember(config.shareAutoStartSeconds) {
        mutableStateOf(config.shareAutoStartSeconds.toString())
    }

    OutlinedTextField(
        value = seconds,
        onValueChange = { input ->
            val digits = input.filter(Char::isDigit).take(3)
            seconds = digits
            digits.toIntOrNull()?.let(viewModel::setShareAutoStartSeconds)
        },
        label = { Text("自動開始までの秒数") },
        supportingText = {
            Text(
                if (config.autoDownloadOnShare) {
                    "ポップアップを開いてから自動で開始するまでの待ち時間（1〜600 秒）。"
                } else {
                    "「共有から自動ダウンロード」を ON にすると使えます。"
                },
            )
        },
        enabled = config.autoDownloadOnShare,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 同梱エンジン（yt-dlp）の状態と更新。 */
@Composable
private fun EngineSection(viewModel: SettingsViewModel) {
    val ytDlp by viewModel.ytDlpState.collectAsStateWithLifecycle()

    SettingsCard(title = "エンジン") {
        Text(
            text = "yt-dlp: " + ytDlp.version.ifBlank { "確認中..." },
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = "同梱の yt-dlp はビルド時点のものです。更新すると YouTube などの取得失敗（403）を解消できます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ytDlp.message?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = { viewModel.updateYtDlp() }, enabled = !ytDlp.updating) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Text(if (ytDlp.updating) " 更新中..." else " yt-dlp を最新版に更新")
        }
    }
}

/** 統計カード。 */
@Composable
private fun StatsSection(config: AppConfig, viewModel: SettingsViewModel) {
    SettingsCard(title = "これまでの実績") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${config.statCompleted} 件",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "累計 ${formatBytes(config.statTotalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { viewModel.resetStats() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Text(" リセット")
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            StatItem(label = "失敗", value = "${config.statFailed} 回")
            StatItem(label = "共有から取り込み", value = "${config.statShared} 回")
        }
    }
}

/** 統計の補助表示（小さいラベル + 値）。 */
@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 実績バッジ一覧。獲得済みは色付き、未獲得は淡く表示する。 */
@Composable
private fun BadgesSection(config: AppConfig) {
    SettingsCard(title = "実績バッジ") {
        val earnedCount = BadgeCatalog.all.count { it.id in config.earnedBadges }
        Text(
            text = "$earnedCount / ${BadgeCatalog.all.size} 獲得",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        BadgeCatalog.all.forEach { badge ->
            Spacer(Modifier.height(12.dp))
            BadgeRow(config = config, badge = badge)
        }
    }
}

/** バッジ 1 件分の行。 */
@Composable
private fun BadgeRow(config: AppConfig, badge: Badge) {
    val earned = badge.id in config.earnedBadges
    val current = BadgeCatalog.value(config, badge.metric)
    val ratio = BadgeCatalog.ratio(config, badge)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = badge.emoji,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.alpha(if (earned) 1f else 0.3f),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = badge.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (earned) FontWeight.Bold else FontWeight.Normal,
                color = if (earned) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Text(
                text = badge.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!earned) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "${formatProgress(badge, current)}  /  ${formatProgress(badge, badge.required)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { ratio },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                )
            }
        }
        if (earned) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "獲得済み",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** バッジの進捗値を指標に応じて読みやすく整形する。 */
private fun formatProgress(badge: Badge, value: Long): String = when (badge.metric) {
    com.nexus.downloader.data.model.BadgeMetric.BYTES -> formatBytes(value)
    else -> "$value"
}

/** ラベル + スイッチの共通行。 */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** バイト数を読みやすい表記にする。 */
internal fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "%.0f %s".format(value, units[index])
    else "%.1f %s".format(value, units[index])
}

/** 初回セットアップをもう一度見るための設定。 */
@Composable
private fun SetupSection(viewModel: SettingsViewModel) {
    SettingsCard(title = stringResource(R.string.settings_setup_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_replay_setup),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.settings_replay_setup_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = viewModel::replaySetup) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Text(" " + stringResource(R.string.settings_setup_show))
            }
        }
    }
}

@Composable
private fun SecuritySection(config: AppConfig, viewModel: SettingsViewModel) {
    var keywords by remember(config.blockedKeywords) {
        mutableStateOf(config.blockedKeywords.joinToString("\n"))
    }

    SettingsCard(title = "セキュリティ") {
        OutlinedTextField(
            value = keywords,
            onValueChange = {
                keywords = it
                viewModel.setBlockedKeywords(
                    it.split('\n', ',').map { word -> word.trim() }.filter { word -> word.isNotEmpty() },
                )
            },
            label = { Text("ブロックする危険キーワード（1 行 1 語）") },
            supportingText = { Text("取り込むアドオン定義に含まれると拒否します。") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            viewModel.resetBlockedKeywords()
            keywords = AppConfig.DEFAULT_BLOCKED_KEYWORDS.joinToString("\n")
        }) {
            Icon(Icons.Rounded.Refresh, contentDescription = null)
            Text(" 既定に戻す")
        }
    }
}

