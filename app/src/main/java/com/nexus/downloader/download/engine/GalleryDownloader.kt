package com.nexus.downloader.download.engine

import com.nexus.downloader.data.model.GallerySpec
import com.nexus.downloader.download.DownloadEnvironment
import com.nexus.downloader.download.ProgressCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File

/**
 * HTML を解析して画像を一括取得するギャラリーエンジン（Python 版 `momonga_addon.py`
 * の静的サイト対応部分を Jsoup で移植）。
 *
 * 抽出条件はアドオン定義の [GallerySpec] に従う。動的サイト（Selenium 前提）は
 * Android では扱わない。
 */
class GalleryDownloader(private val spec: GallerySpec) : DownloadEngine {

    override suspend fun download(
        url: String,
        options: Map<String, String>,
        env: DownloadEnvironment,
        onProgress: ProgressCallback,
    ): String = withContext(Dispatchers.IO) {
        val workers = options["workers"]?.toIntOrNull() ?: spec.maxWorkers
        onProgress(0f, "ページを解析中...")

        val referer = spec.referer ?: url
        val firstDoc = fetchDocument(url, env, referer)
        val title = spec.titleSelector?.let { sel -> firstDoc.selectFirst(sel)?.text() }
            ?.takeIf { it.isNotBlank() } ?: firstDoc.title().ifBlank { "gallery" }
        val folder = File(env.ensureDir(null), env.sanitizeFileName(title)).apply { mkdirs() }

        // ページを辿って画像 URL を収集
        val imageUrls = LinkedHashSet<String>()
        var pageUrl: String? = url
        var page = 0
        while (pageUrl != null && page < spec.maxPages) {
            val doc = if (page == 0) firstDoc else fetchDocument(pageUrl, env, referer)
            doc.select(spec.imageSelector).forEach { el ->
                val raw = el.attr(spec.imageAttr).ifBlank { el.attr("src") }
                val abs = el.absUrl(spec.imageAttr).ifBlank { el.absUrl("src") }
                val candidate = abs.ifBlank { raw }
                if (candidate.startsWith("http") && looksLikeImage(candidate)) imageUrls.add(candidate)
            }
            pageUrl = spec.nextPageSelector?.let { sel ->
                doc.selectFirst(sel)?.absUrl("href")?.takeIf { it.isNotBlank() }
            }
            page++
        }

        if (imageUrls.isEmpty()) throw RuntimeException("画像を 1 枚も検出できませんでした")

        val list = imageUrls.toList()
        val semaphore = Semaphore(workers.coerceIn(1, 16))
        var done = 0
        val total = list.size

        coroutineScope {
            list.mapIndexed { index, imgUrl ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val name = "%04d.%s".format(
                            index + 1,
                            extensionOf(imgUrl),
                        )
                        downloadImage(imgUrl, File(folder, name), env, referer)
                        synchronized(this@GalleryDownloader) {
                            done++
                            val percent = 0.95f * done / total
                            onProgress(
                                percent.coerceIn(0f, 0.95f),
                                "ダウンロード中 ${done}/$total 枚",
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        onProgress(1f, "完了")
        folder.absolutePath
    }

    private fun fetchDocument(pageUrl: String, env: DownloadEnvironment, referer: String) =
        httpGetBytes(env, pageUrl, referer)
            .toString(Charsets.UTF_8)
            .let { Jsoup.parse(it, pageUrl) }

    private fun downloadImage(imgUrl: String, dest: File, env: DownloadEnvironment, referer: String) {
        val bytes = httpGetBytes(env, imgUrl, referer)
        dest.writeBytes(bytes)
    }

    private fun httpGetBytes(env: DownloadEnvironment, url: String, referer: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", env.userAgent)
            .header("Referer", referer)
            .build()
        return env.client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} ${resp.message}")
            resp.body?.bytes() ?: throw RuntimeException("レスポンスが空です")
        }
    }

    private fun looksLikeImage(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
            lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".avif") ||
            lower.contains("/image") || lower.contains("/img")
    }

    private fun extensionOf(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "")
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ext else "jpg"
    }
}
