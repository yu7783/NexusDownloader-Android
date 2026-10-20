package com.nexus.downloader.download

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.nexus.downloader.NexusApp
import com.nexus.downloader.notification.DownloadProgressState
import com.nexus.downloader.notification.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * ダウンロード中だけ動く前面サービス。
 *
 * アプリを離れて元のアプリへ戻ると、プロセスは Android の cached app freezer で
 * 停止（または終了）され、ダウンロードが途中で止まってしまう。前面サービスとして
 * 実行することで止めずに続け、進行状況（と残り時間）を継続通知に表示する。
 *
 * 通知の操作ボタン（一時停止 / 再開 / 中止）もここで受け取る。
 * 停止するのは「実行中も待機中もタスクが無くなったとき」だけで、
 * 一時停止中は通知（再開ボタン付き）を残す。
 *
 * 通知は Android 16+ ではリッチ オンゴーイング通知として昇格させ、
 * 対応していない端末では通常のプログレス通知として表示する
 * （[NotificationHelper] を参照）。
 */
class DownloadService : Service() {

    private val container get() = (application as NexusApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var observeJob: Job? = null
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 通知の操作ボタンからの指示を実行する。
        val action = intent?.action
        when (action) {
            ACTION_PAUSE -> container.downloadManager.pause()
            ACTION_RESUME -> container.downloadManager.resume()
            ACTION_CANCEL -> container.downloadManager.cancelAll()
        }
        promoteToForeground()
        observeDownloads()
        if (action != null) {
            // 操作直後は必ず通知を貼り直す（一時停止 / 再開の状態を見せる）。
            refreshNotification()
        }
        return START_NOT_STICKY
    }

    /** 現在の状態を通知へ即時反映する（変化が無くても送る）。 */
    private fun refreshNotification() {
        val state = container.downloadManager.progressSnapshot()
        if (state == null) {
            shutdown()
            return
        }
        container.notificationHelper.updateDownloadProgress(state, force = true)
    }

    /** 前面通知を出す（startForegroundService から 5 秒以内に必要）。 */
    private fun promoteToForeground() {
        if (isForeground) return
        val manager = container.downloadManager
        val helper = container.notificationHelper
        helper.ensureProgressChannel()

        // 起動直後はまだタスクが無い場合があるため、不定の状態を出しておく。
        val state = manager.progressSnapshot() ?: DownloadProgressState(percent = 0, indeterminate = true)
        startForeground(NotificationHelper.PROGRESS_NOTIFICATION_ID, helper.buildProgressNotification(state))
        isForeground = true
    }

    /**
     * タスクの変化を通知へ反映し、実行中も待機中も無くなったら終了する。
     *
     * 一時停止中は待機中タスクが残るため、サービス（と通知）はそのまま維持し、
     * 通知の「再開」ボタンから続きを再開できるようにする。
     */
    private fun observeDownloads() {
        if (observeJob != null) return
        val manager = container.downloadManager
        val helper = container.notificationHelper
        observeJob = scope.launch {
            var lastPaused: Boolean? = null
            combine(manager.tasks, manager.isRunning, manager.isPaused) { tasks, _, _ -> tasks }
                .collect { tasks ->
                    val state = manager.progressSnapshot(tasks)
                    if (state == null) {
                        shutdown()
                        return@collect
                    }
                    if (lastPaused != state.paused) {
                        Log.i(TAG, "progress paused=${state.paused} pending=${state.pendingCount}")
                        lastPaused = state.paused
                    }
                    helper.updateDownloadProgress(state)
                }
        }
    }

    private fun shutdown() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        container.notificationHelper.clearDownloadProgress()
        observeJob?.cancel()
        observeJob = null
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        scope.cancel()
    }

    companion object {
        private const val TAG = "NexusService"

        /** 通知の「一時停止」ボタン。 */
        const val ACTION_PAUSE = "com.nexus.downloader.action.PAUSE"

        /** 通知の「再開」ボタン。 */
        const val ACTION_RESUME = "com.nexus.downloader.action.RESUME"

        /** 通知の「中止」ボタン。 */
        const val ACTION_CANCEL = "com.nexus.downloader.action.CANCEL"
    }
}
