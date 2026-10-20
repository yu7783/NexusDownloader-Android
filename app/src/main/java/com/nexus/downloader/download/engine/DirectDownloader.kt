package com.nexus.downloader.download.engine

import com.nexus.downloader.download.DownloadEnvironment
import com.nexus.downloader.download.FileNameReservations
import com.nexus.downloader.download.FileNames
import com.nexus.downloader.download.ProgressCallback
import com.nexus.downloader.download.downloadResumable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 直リンクのメディア/ファイルを保存するエンジン。
 * ストリーミング受信しながら進捗を通知し、途中で止まっても続きから再開できる
 * （`.part` + HTTP Range）。
 */
class DirectDownloader : DownloadEngine {

    override suspend fun download(
        url: String,
        options: Map<String, String>,
        env: DownloadEnvironment,
        onProgress: ProgressCallback,
    ): String = withContext(Dispatchers.IO) {
        onProgress(0f, "接続中...")

        // 保存名は URL から先に決める（`.part` を見つけてレジュームするため）。
        // Content-Disposition があればダウンロード完了時にその名前へ移す。
        val destDir = env.ensureDir(null)
        val fileName = env.sanitizeFileName(FileNames.fromUrl(url, "download"))
        // 並列実行での名前衝突を避け、途中の `.part` があれば同じ名前で続きから再開する。
        val dest = FileNameReservations.reserve(destDir, fileName)

        try {
            downloadResumable(
                client = env.client,
                url = url,
                headers = mapOf("User-Agent" to env.userAgent),
                dest = dest,
                resolveFinalName = { response ->
                    response.header("Content-Disposition")
                        ?.let { header ->
                            Regex("""filename\*?=(?:UTF-8''|")?([^\";]+)""", RegexOption.IGNORE_CASE)
                                .find(header)?.groupValues?.get(1)
                        }
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { env.sanitizeFileName(it) }
                },
            ) { downloaded, total ->
                val percent = if (total > 0) downloaded.toFloat() / total else 0f
                val text = if (total > 0) {
                    "ダウンロード中 %.1f%% (%.1f/%.1f MB)".format(
                        percent * 100f, mb(downloaded), mb(total),
                    )
                } else {
                    "ダウンロード中 %.1f MB".format(mb(downloaded))
                }
                onProgress(percent.coerceIn(0f, 1f), text)
            }
        } finally {
            FileNameReservations.release(dest)
        }
        onProgress(1f, "完了")
        dest.absolutePath
    }

    private fun mb(bytes: Long): Double = bytes / 1048576.0
}
