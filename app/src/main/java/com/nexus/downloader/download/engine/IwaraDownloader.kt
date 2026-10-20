package com.nexus.downloader.download.engine

import com.nexus.downloader.download.DownloadEnvironment
import com.nexus.downloader.download.FileNameReservations
import com.nexus.downloader.download.ProgressCallback
import com.nexus.downloader.download.downloadResumable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Iwara (iwara.tv / iwara.ai) 動画エンジン。Python 版 `iwara_addon.py` の移植。
 *
 *   1. 動画 ID 抽出 → 2. GET {API}/video/{id} → 3. GET {fileUrl}(X-Version)
 *   → 4. 選択品質の src.download をダウンロード
 *   X-Version = SHA1("<fileUrl 最終パス要素>_<expires>_<固定シークレット>")
 */
class IwaraDownloader : DownloadEngine {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val videoIdRegex =
        Regex("""iwara\.(?:tv|ai)/video/([0-9A-Za-z]+)""", RegexOption.IGNORE_CASE)
    private val priority = mapOf(
        "Source" to 100, "1080" to 99, "720" to 98, "540" to 90, "360" to 80, "preview" to 1,
    )

    override suspend fun download(
        url: String,
        options: Map<String, String>,
        env: DownloadEnvironment,
        onProgress: ProgressCallback,
    ): String = withContext(Dispatchers.IO) {
        val quality = options["quality"] ?: "Source"
        val videoId = videoIdRegex.find(url)?.groupValues?.get(1)
            ?: throw RuntimeException("Iwara の動画 ID を URL から抽出できません: $url")

        onProgress(0f, "動画情報を取得中...")

        val headers = mapOf(
            "Accept" to "application/json",
            "User-Agent" to env.userAgent,
            "Referer" to REFERER,
            "X-Site" to "www.iwara.tv",
        )

        val meta = getJson(env.client, "$API_HOST/video/$videoId", headers)
            ?: throw RuntimeException("動画情報を取得できませんでした")
        val title = meta["title"].str() ?: videoId
        val fileUrl = meta["fileUrl"].str()
            ?: throw RuntimeException("fileUrl を取得できませんでした")

        val sources = getJsonArray(env.client, fileUrl, headers + ("X-Version" to xVersion(fileUrl)))
            ?: throw RuntimeException("動画ソース一覧を取得できませんでした")

        val source = selectSource(sources, quality)
        val downloadUrl = buildDownloadUrl(source)
        val sourceName = source["name"].str() ?: quality

        val fileName = env.sanitizeFileName("$title [$sourceName]") + guessExtension(downloadUrl)
        // 並列実行での名前衝突を避け、途中の `.part` があれば続きから再開する。
        val dest = FileNameReservations.reserve(env.ensureDir(null), fileName)

        onProgress(0f, "接続中... ($sourceName)")
        try {
            streamToFile(
                env.client, downloadUrl,
                mapOf(
                    "User-Agent" to env.userAgent, "Referer" to REFERER,
                    "Accept" to "*/*", "Accept-Language" to "en-US,en;q=0.9",
                ),
                dest, onProgress,
            )
        } finally {
            FileNameReservations.release(dest)
        }
        onProgress(1f, "完了")
        dest.absolutePath
    }

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun xVersion(fileUrl: String): String {
        val uri = URI(fileUrl)
        val filename = uri.path.trimEnd('/').substringAfterLast('/')
        val expires = uri.rawQuery?.split('&')
            ?.firstOrNull { it.substringBefore('=') == "expires" }
            ?.substringAfter('=', "null") ?: "null"
        return sha1Hex(listOf(filename, expires, X_VERSION_SECRET).joinToString("_"))
    }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun getJson(client: OkHttpClient, url: String, headers: Map<String, String>): JsonObject? =
        httpGet(client, url, headers)?.let {
            runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull()
        }

    private fun getJsonArray(client: OkHttpClient, url: String, headers: Map<String, String>): JsonArray? =
        httpGet(client, url, headers)?.let {
            runCatching { json.parseToJsonElement(it) as? JsonArray }.getOrNull()
        }

    private fun httpGet(client: OkHttpClient, url: String, headers: Map<String, String>): String? {
        val builder = Request.Builder().url(url)
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} ${resp.message}")
            resp.body?.string()
        }
    }

    private fun selectSource(sources: JsonArray, quality: String): JsonObject {
        val parsed = sources.mapNotNull { it as? JsonObject }
        parsed.firstOrNull {
            val name = it["name"].str() ?: ""
            name.equals(quality, true) || name.startsWith(quality)
        }?.let { return it }
        return parsed.maxByOrNull {
            val name = it["name"].str() ?: ""
            priority.entries.firstOrNull { e -> name.contains(e.key) }?.value ?: 0
        } ?: throw RuntimeException("動画ソースを選択できませんでした")
    }

    private fun buildDownloadUrl(source: JsonObject): String {
        val src = (source["src"] as? JsonObject)?.get("download").str()
            ?: throw RuntimeException("ダウンロード URL を取得できませんでした")
        return when {
            src.startsWith("http://") || src.startsWith("https://") -> src
            src.startsWith("//") -> "https:$src"
            else -> URI(REFERER).resolve(src).toString()
        }
    }

    private fun guessExtension(downloadUrl: String): String {
        val ext = downloadUrl.substringBefore('?').substringAfterLast('.', "")
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ".$ext" else ".mp4"
    }

    /** `.part` + HTTP Range で保存する（一時停止 / 失敗後は続きから再開）。 */
    private suspend fun streamToFile(
        client: OkHttpClient,
        url: String,
        headers: Map<String, String>,
        dest: File,
        onProgress: ProgressCallback,
    ) {
        downloadResumable(client, url, headers, dest) { downloaded, total ->
            if (total > 0) {
                val percent = downloaded.toFloat() / total
                onProgress(
                    percent.coerceIn(0f, 1f),
                    "ダウンロード中 %.1f%% (%.1f/%.1f MB)".format(
                        percent * 100, downloaded / 1048576.0, total / 1048576.0,
                    ),
                )
            } else {
                onProgress(0f, "ダウンロード中 %.1f MB".format(downloaded / 1048576.0))
            }
        }
    }

    companion object {
        const val API_HOST = "https://api.iwara.tv"
        const val REFERER = "https://www.iwara.tv/"
        const val X_VERSION_SECRET = "mSvL05GfEmeEmsEYfGCnVpEjYgTJraJN"
    }
}
