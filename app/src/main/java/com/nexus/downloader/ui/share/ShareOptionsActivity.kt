package com.nexus.downloader.ui.share

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.downloader.AppContainer
import com.nexus.downloader.NexusApp
import com.nexus.downloader.data.model.AppConfig
import com.nexus.downloader.data.model.OptionSpec
import com.nexus.downloader.data.model.RegisteredAddon
import com.nexus.downloader.data.share.ShareIntentUrls
import com.nexus.downloader.ui.components.OptionField
import com.nexus.downloader.ui.theme.NexusTheme
import kotlinx.coroutines.delay

/**
 * 共有 / リンクを開く インテント専用のダウンロード設定ポップアップ。
 *
 * 元のアプリの上に半透明のダイアログとして重ねて表示し、開始すると
 * バックグラウンドでダウンロードを走らせて、そのまま元のアプリへ戻る。
 * 進捗と残り時間は通知（Android 16+ ではリッチ オンゴーイング通知）に表示する。
 */
class ShareOptionsActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as NexusApp).container

    /** 進行状況通知のための権限リクエスト（Android 13+）。 */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 結果は任意 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()

        val urls = ShareIntentUrls.from(intent)
        if (urls.isEmpty()) {
            // URL を 1 つも取り出せないときは何もせず元のアプリへ戻る。
            finish()
            return
        }

        val addon = container.addonRepository.findForUrl(urls.first())
        val specs = addon?.let { container.addonRepository.optionsFor(it.spec.id) }.orEmpty()

        setContent {
            val config by container.configRepository.config
                .collectAsStateWithLifecycle(initialValue = AppConfig())
            val tasks by container.downloadManager.tasks.collectAsStateWithLifecycle()

            NexusTheme(
                themeMode = config.theme,
                dynamicColor = config.dynamicColor,
                accentColor = config.accentColor,
            ) {
                ShareOptionsDialog(
                    urls = urls,
                    addon = addon,
                    specs = specs,
                    // 秒数は設定（1〜600 秒、OFF なら 0 = 手動開始）に従う。
                    autoStartSeconds = if (config.autoDownloadOnShare) {
                        config.shareAutoStartSeconds.coerceIn(1, 600)
                    } else {
                        0
                    },
                    // 実行中/待機中の件数。追加するとこの後ろに並ぶ。
                    queuedAhead = tasks.count { it.isActive },
                    onStart = ::startDownload,
                    onCancel = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 連続で共有された場合は、新しい URL でポップアップを開き直す。
        setIntent(intent)
        recreate()
    }

    /** オプション確定後にバックグラウンドで開始し、元のアプリへ戻る。 */
    private fun startDownload(urlText: String, options: Map<String, String>) {
        container.downloadManager.enqueue(urlText, options)
        finish()
    }

    /** Android 13+ では進行状況通知のために権限を求める。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

}

/**
 * ポップアップ本体。元のアプリの上にダイアログとして重ねて表示する。
 *
 * @param urls 共有 / 開いた URL（改行区切りでまとめてダウンロードする）。
 * @param addon 先頭 URL にマッチしたアドオン（無ければ開始できない）。
 * @param specs 先頭アドオンのダウンロード設定項目。
 * @param autoStartSeconds 自動開始までの秒数（0 なら手動開始のみ）。
 * @param queuedAhead 実行中/待機中の件数（追加するとその後ろに並ぶ）。
 */
@Composable
private fun ShareOptionsDialog(
    urls: List<String>,
    addon: RegisteredAddon?,
    specs: List<OptionSpec>,
    autoStartSeconds: Int,
    queuedAhead: Int,
    onStart: (String, Map<String, String>) -> Unit,
    onCancel: () -> Unit,
) {
    val values = remember { mutableStateMapOf<String, String>() }
    var started by remember { mutableStateOf(false) }
    var remaining by remember { mutableIntStateOf(autoStartSeconds) }

    val urlText = remember(urls) { urls.joinToString("\n") }
    val canStart = addon != null

    fun startNow() {
        if (started || !canStart) return
        started = true
        onStart(urlText, specs.associate { spec -> spec.key to (values[spec.key] ?: spec.default) })
    }

    // 「共有から自動ダウンロード」が有効なら数秒後に自動で開始する（設定変更も可能）。
    LaunchedEffect(autoStartSeconds, canStart, started) {
        if (autoStartSeconds <= 0 || !canStart || started) return@LaunchedEffect
        var left = autoStartSeconds
        while (left > 0) {
            delay(1_000)
            left -= 1
            remaining = left
        }
        startNow()
    }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    text = "ダウンロード設定",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (urls.size > 1) "共有された URL（${urls.size} 件）" else "共有された URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.height(12.dp))
                PopupBody(urls = urls, addon = addon, specs = specs, values = values)
                PopupFooter(
                    autoStartSeconds = autoStartSeconds,
                    remaining = remaining,
                    started = started,
                    queuedAhead = queuedAhead,
                    canStart = canStart,
                    onCancel = onCancel,
                    onStart = { startNow() },
                )
            }
        }
    }
}

/** URL 一覧と検出アドオン・設定項目（スクロール領域）。 */
@Composable
private fun PopupBody(
    urls: List<String>,
    addon: RegisteredAddon?,
    specs: List<OptionSpec>,
    values: MutableMap<String, String>,
) {
    Column(
        modifier = Modifier
            .heightIn(max = 360.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                urls.forEach { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (addon != null) {
            AssistChip(onClick = {}, label = { Text("検出: ${addon.spec.name}") })
        } else {
            Text(
                text = "対応するアドオンが見つかりません（スキップされます）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (specs.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "ダウンロード設定",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            specs.forEach { spec ->
                OptionField(
                    spec = spec,
                    value = values[spec.key] ?: spec.default,
                    onValueChange = { values[spec.key] = it },
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }
        }

        if (urls.size > 1) {
            Text(
                text = "設定は先頭の URL に適用されます。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}


/** 自動開始のカウントダウン表示と操作ボタン。 */
@Composable
private fun PopupFooter(
    autoStartSeconds: Int,
    remaining: Int,
    started: Boolean,
    queuedAhead: Int,
    canStart: Boolean,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    // 実行中/待機中があれば、追加するとその後ろに並ぶことを知らせる。
    if (queuedAhead > 0) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = "現在 ${queuedAhead} 件を処理中 / 待機中です。この URL はその後ろに並びます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when {
        autoStartSeconds > 0 && !started -> {
            Spacer(Modifier.height(12.dp))
            Text(
                text = "${remaining} 秒後に自動で開始します",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = {
                    (autoStartSeconds - remaining).toFloat() / autoStartSeconds.toFloat()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onCancel) { Text("キャンセル") }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onStart, enabled = canStart) {
            Text("ダウンロード開始")
        }
    }
}
