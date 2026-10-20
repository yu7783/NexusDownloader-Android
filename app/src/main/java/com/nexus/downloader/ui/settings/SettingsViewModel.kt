package com.nexus.downloader.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.downloader.AppContainer
import com.nexus.downloader.data.file.SaveLocation
import com.nexus.downloader.data.model.AppConfig
import com.nexus.downloader.data.model.StorageTarget
import com.nexus.downloader.data.model.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 設定画面の状態管理。変更は即時に DataStore へ保存される。 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val config: StateFlow<AppConfig> = container.configRepository.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig())

    /** 同梱 yt-dlp のバージョン・更新状態。 */
    val ytDlpState: StateFlow<com.nexus.downloader.download.YtDlpUpdateState> =
        container.ytDlpUpdater.state

    private fun update(transform: (AppConfig) -> AppConfig) {
        viewModelScope.launch { container.configRepository.update(transform) }
    }

    // ---- 保存先 ----

    /** アプリ専用フォルダのサブフォルダ名（空なら直下）。 */
    fun setDownloadDir(value: String) =
        update { it.copy(downloadDir = SaveLocation.sanitizeSubDir(value)) }

    /** アプリ専用領域の種類（外部 / 内部）。 */
    fun setStorageTarget(target: StorageTarget) = update { it.copy(storageTarget = target) }

    /**
     * 保存先フォルダ（SAF）を設定する。
     *
     * @param uri 選ばれたフォルダ。null ならアプリ専用フォルダに戻す。
     */
    fun setSaveTreeUri(context: Context, uri: Uri?) {
        viewModelScope.launch {
            if (uri == null) {
                update { it.copy(saveTreeUri = null) }
                return@launch
            }
            // 再起動後も書き込めるように読み書きの永続権限を取得する。
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            update { it.copy(saveTreeUri = uri.toString()) }
        }
    }

    fun setTheme(mode: ThemeMode) = update { it.copy(theme = mode) }
    fun setDynamicColor(enabled: Boolean) = update { it.copy(dynamicColor = enabled) }
    fun setAccentColor(hex: String) = update { it.copy(accentColor = hex) }
    fun setParallel(value: Int) = update { it.copy(parallel = value.coerceIn(0, 16)) }
    fun setTimeout(value: Int) = update { it.copy(timeout = value.coerceIn(5, 300)) }
    fun setBlockedKeywords(keywords: List<String>) = update { it.copy(blockedKeywords = keywords) }

    fun resetBlockedKeywords() =
        update { it.copy(blockedKeywords = AppConfig.DEFAULT_BLOCKED_KEYWORDS) }

    // ---- モバイル向けの便利設定 ----
    fun setWifiOnly(enabled: Boolean) = update { it.copy(wifiOnly = enabled) }
    fun setAutoDownloadOnShare(enabled: Boolean) = update { it.copy(autoDownloadOnShare = enabled) }

    /** 共有ポップアップの自動開始までの秒数（1〜600）。 */
    fun setShareAutoStartSeconds(value: Int) =
        update { it.copy(shareAutoStartSeconds = value.coerceIn(1, 600)) }

    fun setNotifyOnComplete(enabled: Boolean) = update { it.copy(notifyOnComplete = enabled) }
    fun setClipNotify(enabled: Boolean) = update { it.copy(clipNotify = enabled) }

    /** 初回セットアップをもう一度表示する（次回の起動時に表示される）。 */
    fun replaySetup() = update { it.copy(setupCompleted = false) }

    /** 統計（累計件数・容量・失敗・共有）と獲得バッジをリセットする。 */
    fun resetStats() = update {
        it.copy(
            statCompleted = 0L,
            statTotalBytes = 0L,
            statFailed = 0L,
            statShared = 0L,
            earnedBadges = emptyList(),
        )
    }

    /** yt-dlp を今すぐ最新版へ更新する（設定画面のボタン）。 */
    fun updateYtDlp() {
        viewModelScope.launch { container.ytDlpUpdater.forceUpdate() }
    }
}
