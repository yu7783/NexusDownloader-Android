package com.nexus.downloader.download

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/** yt-dlp 本体の更新状態。 */
data class YtDlpUpdateState(
    /** 現在使用中の yt-dlp バージョン。 */
    val version: String = "",
    /** 最後に更新確認した時刻（epoch millis）。 */
    val lastCheckedAt: Long = 0L,
    /** 更新処理中か。 */
    val updating: Boolean = false,
    /** 直近の結果メッセージ。 */
    val message: String? = null,
)

/**
 * 同梱 yt-dlp のバージョン管理。
 *
 * youtubedl-android に同梱される yt-dlp はビルド時点の固定版のため、
 * 時間が経つと YouTube などで `HTTP Error 403: Forbidden` や
 * 「Unable to extract」が発生する。GitHub の最新リリースへ更新することで解消する。
 *
 * - アプリ起動時とダウンロード前に [ensureUpToDate] を呼ぶ（既定 24 時間間隔）。
 * - 設定画面から [ensureUpToDate]`(force = true)` で手動更新もできる。
 */
class YtDlpUpdater(private val context: Context) {

    private val _state = MutableStateFlow(YtDlpUpdateState(version = currentVersion()))
    val state: StateFlow<YtDlpUpdateState> = _state.asStateFlow()

    private val marker: File get() = File(context.filesDir, MARKER_NAME)

    /** 現在の yt-dlp バージョン（取得できない場合は空文字）。 */
    fun currentVersion(): String =
        runCatching { YoutubeDL.getInstance().versionName(context) }
            .getOrNull()
            .orEmpty()

    /** 手動更新ボタン用。 */
    suspend fun forceUpdate(): YtDlpUpdateState = ensureUpToDate(force = true)

    /**
     * 前回の確認から [CHECK_INTERVAL_MS] 経過していれば yt-dlp を更新する。
     *
     * @param force true なら間隔を無視して必ず確認する。
     * @param onStatus 進捗メッセージ（タスク表示用）。
     */
    suspend fun ensureUpToDate(
        force: Boolean = false,
        onStatus: (String) -> Unit = {},
    ): YtDlpUpdateState = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastChecked = marker.takeIf { it.isFile }
            ?.readText()?.trim()?.toLongOrNull() ?: 0L
        if (!force && now - lastChecked < CHECK_INTERVAL_MS) {
            return@withContext _state.value
        }

        // 未初期化（テスト等）なら更新できないので現在値を返す。
        if (currentVersion().isBlank()) {
            onStatus("yt-dlp を初期化中...")
        }

        onStatus("yt-dlp の更新を確認中...")
        _state.value = _state.value.copy(updating = true, message = "更新を確認中...")

        val message = try {
            when (YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE)) {
                YoutubeDL.UpdateStatus.DONE -> "yt-dlp を更新しました"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp は最新です"
                else -> "yt-dlp を確認しました"
            }
        } catch (e: Exception) {
            // オフライン等では失敗するが、既存バージョンで続行する。
            Log.w(TAG, "yt-dlp の更新に失敗しました", e)
            "yt-dlp の更新に失敗しました: ${e.message ?: e.javaClass.simpleName}"
        }

        // 失敗しても一定時間は再試行しないよう記録する。
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(now.toString())
        }

        val next = _state.value.copy(
            version = currentVersion(),
            lastCheckedAt = now,
            updating = false,
            message = message,
        )
        _state.value = next
        next
    }

    companion object {
        private const val TAG = "YtDlpUpdater"
        private const val MARKER_NAME = "ytdlp_last_check"

        /** 自動更新確認の間隔（24 時間）。 */
        const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
