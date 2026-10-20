package com.nexus.downloader.download.engine

import android.content.Context
import android.util.Log
import com.nexus.downloader.download.DownloadEnvironment
import com.nexus.downloader.download.DownloadManager
import com.nexus.downloader.download.ProgressCallback
import com.nexus.downloader.download.YtDlpUpdater
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/**
 * yt-dlp エンジン。Python 版 `yt_dlp_addon.py` の移植。
 * youtubedl-android が同梱する yt-dlp + python + ffmpeg を呼び出す。
 */
class YtDlpDownloader(
    private val context: Context,
    private val updater: YtDlpUpdater? = null,
) : DownloadEngine {

    override suspend fun download(
        url: String,
        options: Map<String, String>,
        env: DownloadEnvironment,
        onProgress: ProgressCallback,
    ): String = withContext(Dispatchers.IO) {
        // 同梱 yt-dlp が古いと YouTube で 403 になるため、実行前に更新を確認する。
        updater?.ensureUpToDate { message -> onProgress(0f, message) }

        val quality = options["quality"] ?: "best"
        val format = options["format"] ?: "mp4"
        val bitrate = options["audio_bitrate"] ?: "best"
        val audioOnly = quality == "audio only" || format == "mp3" || format == "m4a"
        val wholePlaylist = options["playlist"] != DownloadManager.PLAYLIST_FIRST_ONLY

        val destDir = env.ensureDir(null)
        val outputTemplate = File(destDir, "%(title)s.%(ext)s").absolutePath

        val request = YoutubeDLRequest(url)
            .addOption("-o", outputTemplate)
            .addOption(if (wholePlaylist) "--yes-playlist" else "--no-playlist")
            .addOption("--newline")
            .addOption("--no-warnings")
            .addOption("--no-mtime")
            .addOption("--retries", "3")
            .addOption("--fragment-retries", "5")

        if (audioOnly) {
            request.addOption("-x")
            request.addOption("--audio-format", if (format == "m4a") "m4a" else "mp3")
            if (bitrate != "best") request.addOption("--audio-quality", bitrate.replace("kbps", "K"))
            else request.addOption("--audio-quality", "0")
        } else {
            request.addOption("-f", qualitySelector(quality))
            if (format == "mp4" || format == "webm" || format == "mkv") {
                request.addOption("--merge-output-format", format)
            }
        }

        applyLogin(request, options)

        // yt-dlp は映像と音声を別々に取得するため、ファイルごとに % が 0 に戻る。
        // そのまま表示すると進捗が乱高下するので、全体を「取得ファイル数」で等分し、
        // (完了ファイル数 + 現在ファイルの進捗) / 総ファイル数 で割り当てる。
        // 総ファイル数は yt-dlp の "[info] Downloading N format(s): <ids>" 行から
        // ストリーム数（+ の個数 + 1）として取得する。
        var totalFiles = if (audioOnly) 1 else 0
        var seenFiles = 0
        var merging = false
        var lastReported = -1f
        var lastMessage = ""

        // YouTube の 403 など一時的な失敗は、URL を再取得させるためもう一度だけやり直す。
        var attempt = 0
        while (true) {
            try {
                // youtubedl-android 0.18.x の execute は
                // execute(request, processId, callback: ((Float, Long, String) -> Unit)?) の並び。
                // 第 3 引数には yt-dlp の生出力 1 行が渡ってくる。
                YoutubeDL.getInstance().execute(request, null) { rawPercent, etaInSeconds, line ->
                    if (DEBUG_LINES) Log.d(TAG, "yt-dlp: $line")

                    when {
                        // "[info] Downloading 1 format(s): 137+140" → 取得ストリーム数は 2。
                        "format(s):" in line -> {
                            val ids = line.substringAfter("format(s):", "").trim().substringBefore(' ')
                            if (ids.isNotBlank()) {
                                totalFiles = ids.count { it == '+' } + 1
                            }
                        }

                        line.startsWith("[download] Destination:") -> {
                            // 取得ファイルの開始。前のファイルが終わったことになる。
                            seenFiles++
                        }

                        line.startsWith("[Merger]") ||
                            line.startsWith("[ExtractAudio]") ||
                            line.startsWith("[FixupM3u8]") ||
                            line.startsWith("[Fixup") -> merging = true
                    }

                    // ライブラリの進捗は一致しない行では直前値を保持するため -1 もあり得る。
                    val fraction = (rawPercent / 100f).coerceIn(0f, 1f)
                    // 例外ケース（ファイル数が想定より多い等）でも破綻しないよう、
                    // 「検出した総数」と「実際に開始したファイル数」の大きい方を採用する。
                    val total = maxOf(totalFiles, seenFiles, if (audioOnly) 1 else 2)
                    val finished = (seenFiles - 1).coerceIn(0, total - 1)
                    val mapped = if (merging) {
                        MERGE_START
                    } else {
                        ((finished + fraction) / total).coerceIn(0f, MERGE_START)
                    }

                    val label = when {
                        merging -> "ファイルを結合中..."
                        audioOnly || seenFiles >= 2 ->
                            "音声をダウンロード中 %.1f%%".format(rawPercent.coerceAtLeast(0f))
                        else -> "映像をダウンロード中 %.1f%%".format(rawPercent.coerceAtLeast(0f))
                    }
                    val message = if (!merging && etaInSeconds > 0) {
                        "$label (残り ${etaInSeconds} 秒)"
                    } else {
                        label
                    }

                    // 同じ値の連続更新を避ける（1 行ごとに呼ばれるため）。
                    if (mapped > lastReported + 0.0005f || message != lastMessage) {
                        lastReported = maxOf(lastReported, mapped)
                        lastMessage = message
                        // 非 suspend の onProgress なので、同期コールバックから直接呼べる。
                        onProgress(mapped, message)
                    }
                }
                break
            } catch (e: Exception) {
                val reason = e.message ?: "yt-dlp の実行に失敗しました"
                if (attempt < MAX_ATTEMPTS - 1 && isRetryable(reason)) {
                    attempt++
                    // 進捗状態をリセットして最初からやり直す。
                    totalFiles = if (audioOnly) 1 else 0
                    seenFiles = 0
                    merging = false
                    lastReported = -1f
                    lastMessage = ""
                    onProgress(0f, "一時的なエラーのため再試行します (${attempt}/${MAX_ATTEMPTS - 1})...")
                    delay(RETRY_DELAY_MS)
                    continue
                }
                throw RuntimeException(withLoginHint(reason))
            }
        }

        onProgress(1f, "完了")
        newestFile(destDir)?.absolutePath ?: destDir.absolutePath
    }

    /**
     * プレイリスト URL を「1 動画 = 1 URL」へ展開する。
     *
     * `--flat-playlist --print` で各動画のページを開かずに一覧だけを取得する。
     * 展開後の URL は呼び出し側（DownloadManager）が個別タスクとしてキューへ積むため、
     * 1 本ずつ進捗を表示できる。
     */
    override suspend fun expandEntries(url: String, env: DownloadEnvironment): List<String> {
        if (!isPlaylistUrl(url)) return listOf(url)
        updater?.ensureUpToDate { }

        val request = YoutubeDLRequest(url)
            .addOption("--flat-playlist")
            .addOption("--ignore-errors")
            .addOption("--no-warnings")
            // webpage_url が無い抽出元もあるため url も同時に出力する。
            .addOption("--print", "%(webpage_url)s\t%(url)s")

        val entries = try {
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().execute(request, null).out
            }
        } catch (e: Exception) {
            Log.w(TAG, "プレイリストの解析に失敗しました", e)
            ""
        }

        return entries.lineSequence()
            .flatMap { line -> line.split('\t').asSequence() }
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
            .ifEmpty { listOf(url) }
    }

    /** プレイリスト（複数動画）を指す URL かどうか。 */
    private fun isPlaylistUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("/playlist") ||
            lower.contains("list=") ||
            lower.contains("/my/deck") ||
            lower.contains("/series/") ||
            lower.contains("/album/")
    }

    private fun qualitySelector(quality: String): String = when (quality) {
        "1080p" -> "bestvideo[height<=1080]+bestaudio/best[height<=1080]"
        "720p" -> "bestvideo[height<=720]+bestaudio/best[height<=720]"
        "480p" -> "bestvideo[height<=480]+bestaudio/best[height<=480]"
        "360p" -> "bestvideo[height<=360]+bestaudio/best[height<=360]"
        else -> "bestvideo*+bestaudio/best"
    }

    private fun applyLogin(request: YoutubeDLRequest, options: Map<String, String>) {
        when (options["login"] ?: "なし") {
            "Cookiesファイル" -> {
                val path = options["cookies_file"].orEmpty().ifBlank {
                    File(context.filesDir, "cookies.txt").absolutePath
                }
                if (File(path).isFile) request.addOption("--cookies", path)
            }
            "IDとパスワード" -> {
                val user = options["username"].orEmpty()
                val pass = options["password"].orEmpty()
                if (user.isNotBlank() && pass.isNotBlank()) {
                    request.addOption("--username", user)
                    request.addOption("--password", pass)
                    options["twofactor"]?.takeIf { it.isNotBlank() }?.let {
                        request.addOption("--twofactor", it)
                    }
                }
            }
        }
    }

    /**
     * 一時的な失敗（再試行で直る可能性が高いもの）かどうか。
     *
     * YouTube の `HTTP Error 403: Forbidden` は署名付き URL の期限切れが原因で、
     * yt-dlp が URL を取り直せば成功することが多い。
     */
    private fun isRetryable(message: String): Boolean {
        val lowered = message.lowercase()
        return listOf(
            "403", "forbidden", "timed out", "connection reset",
            "temporarily unavailable", "eof occurred", "http error 5",
        ).any { it in lowered }
    }

    private fun withLoginHint(message: String): String {
        val lowered = message.lowercase()
        return if ("login" in lowered || "sensitive" in lowered || "private" in lowered) {
            "$message\n\nヒント: ログインが必要な動画です。ダウンロード設定の「ログイン」で" +
                "「Cookiesファイル」（Netscape 形式 cookies.txt）または「IDとパスワード」を指定してください。"
        } else {
            message
        }
    }

    private fun newestFile(dir: File): File? =
        dir.listFiles { f -> f.isFile }
            ?.maxByOrNull { it.lastModified() }

    companion object {
        private const val TAG = "YtDlpDownloader"

        /** yt-dlp の生出力を logcat へ出すか（デバッグ用）。 */
        private const val DEBUG_LINES = false

        /** 一時的な失敗を含めた最大試行回数。 */
        private const val MAX_ATTEMPTS = 2

        /** 再試行までの待ち時間。 */
        private const val RETRY_DELAY_MS = 3000L

        /** 結合フェーズで表示する進捗。 */
        private const val MERGE_START = 0.99f
    }
}
