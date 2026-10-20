package com.nexus.downloader

import android.app.Application
import android.content.Context
import android.util.Log
import com.nexus.downloader.data.addon.AddonRepository
import com.nexus.downloader.data.config.ConfigRepository
import com.nexus.downloader.download.DownloadManager
import com.nexus.downloader.download.YtDlpUpdater
import com.nexus.downloader.notification.NotificationHelper
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 手動 DI 用のコンテナ（小規模アプリのため Hilt 等は使わない）。 */
class AppContainer(context: Context) {
    /** アプリケーション Context。ContentResolver 等に使用。 */
    val appContext: Context = context.applicationContext
    val configRepository = ConfigRepository(appContext)
    val addonRepository = AddonRepository(appContext).apply { reload() }
    val notificationHelper = NotificationHelper(appContext)
    /** 同梱 yt-dlp のバージョン管理（古いと YouTube で 403 になるため更新する）。 */
    val ytDlpUpdater = YtDlpUpdater(appContext)
    val downloadManager = DownloadManager(
        appContext,
        configRepository,
        addonRepository,
        notificationHelper,
        ytDlpUpdater,
    )
}

/** アプリケーション。yt-dlp / ffmpeg の初期化をここで行う。 */
class NexusApp : Application() {

    lateinit var container: AppContainer
        private set

    /** 起動時の yt-dlp 更新確認などに使うアプリ寿命のスコープ。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        initializeYtDlp()
        // 同梱 yt-dlp が古いと YouTube で 403 になるため、起動時に更新を確認する。
        appScope.launch { container.ytDlpUpdater.ensureUpToDate() }
    }

    private fun initializeYtDlp() {
        // yt-dlp は同梱 python + バイナリを初回に展開するため数秒かかる場合がある。
        runCatching { YoutubeDL.getInstance().init(this) }
            .onFailure { Log.w(TAG, "yt-dlp の初期化に失敗しました", it) }
        runCatching { FFmpeg.getInstance().init(this) }
            .onFailure { Log.w(TAG, "ffmpeg の初期化に失敗しました", it) }
        runCatching { Aria2c.getInstance().init(this) }
            .onFailure { Log.w(TAG, "aria2c の初期化に失敗しました", it) }
    }

    companion object {
        private const val TAG = "NexusApp"
    }
}
