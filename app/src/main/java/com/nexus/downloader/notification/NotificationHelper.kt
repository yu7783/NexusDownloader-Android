package com.nexus.downloader.notification

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.nexus.downloader.MainActivity
import com.nexus.downloader.R
import com.nexus.downloader.download.DownloadService
import kotlin.math.abs

/**
 * 進行中のダウンロード通知に表示するスナップショット。
 *
 * 表示文字列の組み立ては [NotificationHelper] が担当し、ここには
 * ダウンロード側（[com.nexus.downloader.download.DownloadManager]）が知っている
 * 生の値だけを持たせる。
 */
data class DownloadProgressState(
    /** 全体の進捗（0..100）。 */
    val percent: Int,
    /** 処理中の件数（1 始まり）。1 件だけなら 0 でもよい。 */
    val currentIndex: Int = 0,
    /** キュー全体の件数。 */
    val totalCount: Int = 1,
    /** この後に待っている件数。 */
    val pendingCount: Int = 0,
    /** 一時停止中か。 */
    val paused: Boolean = false,
    /** 例: アドオン名。 */
    val label: String? = null,
    /** 例: "45.2% (45.2/100.0 MB)"。 */
    val detail: String? = null,
    /** 推定の残り時間（ミリ秒）。算出できないときは null。 */
    val remainingMillis: Long? = null,
    /** 進捗が不明（不定）か。 */
    val indeterminate: Boolean = false,
)

/**
 * ダウンロードの進行状況 / 完了通知を表示するヘルパ。
 *
 * Android 13+ では POST_NOTIFICATIONS 権限が必要。未許可なら黙ってスキップする。
 *
 * 進行状況は Android 16 (API 36) 以降では「リッチ オンゴーイング通知（ライブ更新）」
 * として昇格させ、ステータスバーに進捗と残り時間をリアルタイム表示する。
 * 非対応の端末では、同じ内容を通常の継続通知（プログレスバー + 残り時間の
 * カウントダウン）として表示する。
 */
class NotificationHelper(private val context: Context) {

    /** 直前に通知へ反映した内容（同じ内容での再送を避ける）。 */
    private var lastProgress: DownloadProgressState? = null

    /** 進行状況通知を表示中か。 */
    private var progressVisible = false

    /** 最後に通知を更新した時刻（[SystemClock.elapsedRealtime]）。 */
    private var lastPostedAt = 0L

    /** 通知チャンネルを作成する（Android 8.0+）。 */
    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ダウンロード",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "ダウンロードの完了を通知します"
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 進行状況チャンネルを作成する。
     *
     * 進行状況は高頻度に更新するため、完了通知とはチャンネルを分けて
     * 通知音やバイブレーションを抑える（[NotificationCompat.Builder.setSilent]）。
     */
    fun ensureProgressChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(PROGRESS_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            PROGRESS_CHANNEL_ID,
            "ダウンロードの進行状況",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "進行中のダウンロードと残り時間を表示します"
            setShowBadge(false)
            // 高頻度に更新するため、音とバイブレーションは出さない。
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 進行中のダウンロード通知を更新する。
     *
     * 残り時間はシステムが描画するカウントダウン（`setWhen` + クロノメーター）に
     * 任せるため、同じ内容の再送は行わない。
     */
    fun updateDownloadProgress(state: DownloadProgressState, force: Boolean = false) {
        if (!canNotify()) return

        val now = SystemClock.elapsedRealtime()
        val previous = lastProgress
        if (previous != null && !force) {
            // 内容が変わったか、残り時間のカウントダウンを更新すべき時刻か。
            val changed = isMeaningfulChange(previous, state)
            val heartbeat = now - lastPostedAt >= HEARTBEAT_REFRESH_MS
            if (!changed && !heartbeat) return
            // プラットフォームの通知レート制限（毎秒 5 件）に引っかからないよう間引く。
            if (now - lastPostedAt < MIN_POST_INTERVAL_MS) return
        }
        ensureProgressChannel()

        val notification = if (canUseRichOngoing()) {
            buildRichOngoingNotification(state)
        } else {
            buildProgressNotification(state)
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(PROGRESS_NOTIFICATION_ID, notification)
            lastProgress = state
            lastPostedAt = now
            progressVisible = true
        }
    }

    /** 進行中のダウンロード通知を片付ける。 */
    fun clearDownloadProgress() {
        lastProgress = null
        if (!progressVisible) return
        progressVisible = false
        runCatching {
            NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID)
        }
    }

    /** 完了通知を表示する。 */
    fun notifyCompleted(title: String, detail: String?) {
        if (!canNotify()) return
        ensureChannel()

        val pending = contentIntent()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .apply { detail?.takeIf { it.isNotBlank() }?.let { setContentText(it) } }
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }
    }

    /** 実績バッジ解除の通知を表示する（完了通知とは別 ID で上書きしない）。 */
    fun notifyBadge(title: String, detail: String?) {
        if (!canNotify()) return
        ensureChannel()

        val pending = contentIntent()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .apply { detail?.takeIf { it.isNotBlank() }?.let { setContentText(it) } }
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(BADGE_NOTIFICATION_ID, notification)
        }
    }

    // ---------------- 進行状況通知の組み立て ----------------

    /** 通知タップでアプリ（ダウンロード画面）を開く PendingIntent。 */
    private fun contentIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** この端末がリッチ オンゴーイング通知（Android 16+）に対応しているか。 */
    private fun canUseRichOngoing(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA

    /**
     * プログレスバー + 残り時間のカウントダウンを持つ継続通知。
     *
     * Android 16 未満ではこの通知を使い、Android 16+ では前面サービスの起動通知
     * としても使う（[buildRichOngoingNotification] は API 36 以降でのみ呼ぶため）。
     *
     * `setWhen` を未来時刻にすると、通知の時刻欄がシステム管理のカウントダウン
     * （残り時間）になる。アプリ側で毎秒更新しなくてもリアルタイムに減っていく。
     */
    fun buildProgressNotification(state: DownloadProgressState): Notification {
        val builder = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(progressTitle(state))
            .setContentText(progressText(state))
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setProgress(PROGRESS_MAX, state.percent, state.indeterminate)

        progressActions(state).forEach { action ->
            builder.addAction(NotificationCompat.Action(action.iconRes, action.title, action.pending))
        }
        applyCompatTiming(builder, state)
        return builder.build()
    }

    /**
     * リッチ オンゴーイング通知（Android 16+ のライブ更新）。
     *
     * [Notification.ProgressStyle] を付けて
     * [Notification.Builder.setRequestPromotedOngoing] を要求することで、
     * ステータスバーのチップに進捗と残り時間がリアルタイム表示される。
     * Android 16 未満では呼ばれない（[canUseRichOngoing] を参照）。
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildRichOngoingNotification(state: DownloadProgressState): Notification {
        val accent = ContextCompat.getColor(context, R.color.nexus_progress_accent)
        val style = Notification.ProgressStyle()
            .setStyledByProgress(true)
            .setProgress(state.percent)
            .setProgressIndeterminate(state.indeterminate)
            // セグメントの合計長 = 進捗の最大値（1 本のバーとして表示される）。
            .setProgressSegments(
                listOf(Notification.ProgressStyle.Segment(PROGRESS_MAX).setColor(accent)),
            )
            // 完了位置のマーカー（ライブ更新の要件としてポイントが 1 つ必要）。
            // 残り時間は [applyRemaining] の `when` + クロノメーターから
            // システムがチップのタイマーとして描画する。
            .setProgressPoints(
                listOf(Notification.ProgressStyle.Point(PROGRESS_MAX).setColor(accent)),
            )
            .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_stat_download))

        val builder = Notification.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(progressTitle(state))
            .setContentText(progressText(state))
            .setContentIntent(contentIntent())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // 音とバイブレーションは進行状況チャンネル側で無効にしている。
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setProgress(PROGRESS_MAX, state.percent, state.indeterminate)
            .setStyle(style)
            // ステータスバーのチップに出す短いテキスト（7 文字以内）。
            .setShortCriticalText("${state.percent}%")
            // ライブ更新（リッチ オンゴーイング通知）への昇格を要求する。
            // androidx.core の setRequestPromotedOngoing と同じ extra を直接指定する
            // （プラットフォーム側が内容を検証して昇格可否を決める）。
            .addExtras(
                Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) },
            )

        progressActions(state).forEach { action ->
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, action.iconRes),
                    action.title,
                    action.pending,
                ).build(),
            )
        }
        applyTiming(builder, state)
        return builder.build()
    }

    /**
     * 残り時間があれば、システム管理のカウントダウンを設定する。
     *
     * 一時停止中など残り時間が分からないときはカウントダウンを出さない
     * （経過時間がカウントアップして見えるのを避ける）。
     */
    private fun applyCompatTiming(builder: NotificationCompat.Builder, state: DownloadProgressState) {
        val remaining = state.remainingMillis?.takeIf { it > 0L }
        if (remaining == null) {
            // 一時停止中などはカウントダウン自体を消す（マイナス表示の防止）。
            builder.setShowWhen(false)
            builder.setUsesChronometer(false)
            return
        }
        builder.setShowWhen(true)
        builder.setUsesChronometer(true)
        builder.setChronometerCountDown(true)
        builder.setWhen(System.currentTimeMillis() + remaining)
        // プロセスが落ちたときに古い通知が残り続けないようにする。
        builder.setTimeoutAfter(remaining + TIMEOUT_GRACE_MS)
    }

    /** [applyCompatTiming] のプラットフォーム版（リッチ オンゴーイング通知用）。 */
    private fun applyTiming(builder: Notification.Builder, state: DownloadProgressState) {
        val remaining = state.remainingMillis?.takeIf { it > 0L }
        if (remaining == null) {
            // 一時停止中などはカウントダウン自体を消す（マイナス表示の防止）。
            builder.setShowWhen(false)
            builder.setUsesChronometer(false)
            return
        }
        builder.setShowWhen(true)
        builder.setUsesChronometer(true)
        builder.setChronometerCountDown(true)
        builder.setWhen(System.currentTimeMillis() + remaining)
        builder.setTimeoutAfter(remaining + TIMEOUT_GRACE_MS)
    }

    /**
     * 進行状況通知の操作ボタン（一時停止 / 再開 / 中止）。
     *
     * タップすると [DownloadService] が対応する操作を実行する。
     */
    private fun progressActions(state: DownloadProgressState): List<ProgressAction> {
        val pauseOrResume = if (state.paused) {
            ProgressAction(
                iconRes = android.R.drawable.ic_media_play,
                title = "再開",
                pending = servicePendingIntent(DownloadService.ACTION_RESUME, REQUEST_RESUME),
            )
        } else {
            ProgressAction(
                iconRes = android.R.drawable.ic_media_pause,
                title = "一時停止",
                pending = servicePendingIntent(DownloadService.ACTION_PAUSE, REQUEST_PAUSE),
            )
        }
        return listOf(
            pauseOrResume,
            ProgressAction(
                iconRes = android.R.drawable.ic_menu_close_clear_cancel,
                title = "中止",
                pending = servicePendingIntent(DownloadService.ACTION_CANCEL, REQUEST_CANCEL),
            ),
        )
    }

    /** 通知の操作ボタンから [DownloadService] に届ける PendingIntent。 */
    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, DownloadService::class.java).setAction(action)
        return PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** 進行状況通知の操作ボタン 1 つ分。 */
    private data class ProgressAction(
        val iconRes: Int,
        val title: String,
        val pending: PendingIntent,
    )

    private fun progressTitle(state: DownloadProgressState): String = when {
        state.paused -> "一時停止中"
        state.totalCount > 1 && state.currentIndex > 0 ->
            "ダウンロード中 (${state.currentIndex}/${state.totalCount})"

        else -> "ダウンロード中"
    }

    private fun progressText(state: DownloadProgressState): String {
        val parts = mutableListOf<String>()
        state.label?.takeIf { it.isNotBlank() }?.let { parts += it }
        if (state.paused) {
            // 一時停止中は待機中タスク（= 再開待ち）の件数をそのまま出す。
            parts += if (state.pendingCount > 1) {
                "残り ${state.pendingCount} 件 · 再開できます"
            } else {
                "再開できます"
            }
        } else {
            state.detail?.takeIf { it.isNotBlank() }?.let { parts += it }
            state.remainingMillis?.let { parts += "残り約 ${formatRemaining(it)}" }
            if (state.pendingCount > 0) parts += "待機 ${state.pendingCount} 件"
        }
        return parts.joinToString(" · ").ifBlank { "ダウンロードしています" }
    }

    private fun formatRemaining(millis: Long): String = when {
        millis < 1_000L -> "1 秒未満"
        millis < 60_000L -> "${millis / 1_000L} 秒"
        millis < 3_600_000L -> "${millis / 60_000L} 分"
        else -> "${millis / 3_600_000L} 時間"
    }

    /**
     * 再送する価値のある変化か。
     *
     * 残り時間はシステムが描画するため、進捗も残り時間もほぼ同じ更新は捨てる
     * （電池消費とちらつきの抑制）。
     */
    private fun isMeaningfulChange(
        previous: DownloadProgressState,
        next: DownloadProgressState,
    ): Boolean {
        if (previous.percent != next.percent) return true
        if (previous.indeterminate != next.indeterminate) return true
        if (previous.currentIndex != next.currentIndex) return true
        if (previous.pendingCount != next.pendingCount) return true
        if (previous.paused != next.paused) return true
        if (previous.label != next.label) return true
        // detail には転送バイト数が入り高頻度に変わるため、比較には使わない
        // （プラットフォーム側の通知レート制限に引っかかるのを避ける）。
        val before = previous.remainingMillis
        val after = next.remainingMillis
        return when {
            before == null && after == null -> false
            before == null || after == null -> true
            else -> abs(before - after) >= REFRESH_REMAINING_STEP_MS
        }
    }

    private fun canNotify(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    companion object {
        const val CHANNEL_ID = "nexus_downloads"

        /** 進行状況専用チャンネル（完了通知とは分けて静かに更新する）。 */
        const val PROGRESS_CHANNEL_ID = "nexus_downloads_progress"

        const val NOTIFICATION_ID = 1001
        const val BADGE_NOTIFICATION_ID = 1002

        /** 進行状況通知の ID。 */
        const val PROGRESS_NOTIFICATION_ID = 1003

        /** 進捗の最大値（= セグメントの合計長）。 */
        private const val PROGRESS_MAX = 100

        /** 残り時間の再送間隔。これ未満の差では通知を更新しない。 */
        private const val REFRESH_REMAINING_STEP_MS = 1_000L

        /** 通知の最短更新間隔（プラットフォームの毎秒 5 件制限に対する余裕）。 */
        private const val MIN_POST_INTERVAL_MS = 900L

        /**
         * 変化が無くてもこの間隔で通知を再送する。
         *
         * 残り時間のカウントダウン（`when` + クロノメーター）は投稿時の値から
         * 進み続けるため、推定が外れるとマイナス表示になる。定期的に貼り直して防ぐ。
         */
        private const val HEARTBEAT_REFRESH_MS = 15_000L

        /** タイムアウトの余裕（プロセス終了時に通知を自動で片付ける）。 */
        private const val TIMEOUT_GRACE_MS = 10_000L

        /** 通知の操作ボタン用 PendingIntent のリクエストコード。 */
        private const val REQUEST_PAUSE = 101
        private const val REQUEST_RESUME = 102
        private const val REQUEST_CANCEL = 103

        /**
         * リッチ オンゴーイング通知（ライブ更新）への昇格要求。
         *
         * `NotificationCompat.Builder#setRequestPromotedOngoing` が設定する extra と
         * 同じキー。Android 16 以降のプラットフォームだけが解釈する。
         */
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
