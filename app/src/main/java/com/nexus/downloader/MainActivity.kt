package com.nexus.downloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nexus.downloader.data.model.AppConfig
import com.nexus.downloader.data.share.ShareInbox
import com.nexus.downloader.data.share.ShareIntentUrls
import com.nexus.downloader.ui.NexusRoot
import com.nexus.downloader.ui.onboarding.OnboardingScreen
import com.nexus.downloader.ui.theme.NexusTheme
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** 完了通知のための権限リクエスト（Android 13+）。 */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 結果は任意 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 起動インテント（共有 / リンクを開く）から URL を取り込む。
        handleShareIntent(intent)

        val container = (application as NexusApp).container
        setContent {
            // 設定を null 許容で購読し、読み込み完了を判定できるようにする。
            val configState by container.configRepository.config
                .map<AppConfig, AppConfig?> { it }
                .collectAsStateWithLifecycle(initialValue = null)

            // 初回セットアップを表示するかは起動時に一度だけ判定する。
            // 設定の「もう一度見る」は次回の起動時に反映される。
            var setupDecided by remember { mutableStateOf(false) }
            var showSetup by remember { mutableStateOf(false) }
            LaunchedEffect(configState) {
                val loaded = configState
                if (loaded != null && !setupDecided) {
                    showSetup = !loaded.setupCompleted
                    setupDecided = true
                }
            }

            // セットアップを表示しないときだけ通知権限を確認する。
            LaunchedEffect(setupDecided, showSetup) {
                if (setupDecided && !showSetup) {
                    requestNotificationPermissionIfNeeded()
                }
            }

            val scope = rememberCoroutineScope()
            val config = configState ?: AppConfig()

            NexusTheme(
                themeMode = config.theme,
                dynamicColor = config.dynamicColor,
                accentColor = config.accentColor,
            ) {
                when {
                    // 設定読み込み中は空の画面を出す（一瞬で解決する）。
                    !setupDecided -> Surface(modifier = Modifier.fillMaxSize()) {}
                    showSetup -> OnboardingScreen(
                        onFinished = {
                            showSetup = false
                            scope.launch {
                                container.configRepository.update {
                                    it.copy(setupCompleted = true)
                                }
                            }
                        },
                    )
                    else -> NexusRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // アプリ起動中に共有された場合も受け取る。
        handleShareIntent(intent)
    }

    /** Android 13+ では通知権限を初回に求める。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * ACTION_SEND / ACTION_SEND_MULTIPLE / ACTION_VIEW の URL を ShareInbox に流す。
     *
     * 通常の共有はインテント専用ポップアップ（
     * [com.nexus.downloader.ui.share.ShareOptionsActivity]）が受け取るため、
     * ここはアプリ本体が直接インテントで起動された場合の受け口。
     */
    private fun handleShareIntent(intent: Intent?) {
        val urls = ShareIntentUrls.from(intent)
        if (urls.isEmpty()) return
        Log.d(TAG, "handleShareIntent action=${intent?.action} urls=${urls.size}")
        ShareInbox.postAll(urls)
    }

    companion object {
        private const val TAG = "NexusShare"
    }
}
