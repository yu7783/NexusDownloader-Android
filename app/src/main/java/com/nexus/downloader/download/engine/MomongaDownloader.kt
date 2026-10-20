package com.nexus.downloader.download.engine

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
import org.jsoup.nodes.Document
import java.io.File

/**
 * momon-ga / ddd-smart 系の画像一括ダウンロード（Python 版 `momonga_addon.py` の
 * レガシーサイト対応を Jsoup で移植）。
 *
 * Python 版は CDN の固定 URL（`z2.momon-ga.com/galleries/<id>/001.webp`）を
 * 連番で総当たりしていたが、その後 CDN が `z3.momon-ga.me` かつファイル名が
 * `1.webp` 形式へ変更されたため、次の二段構えで取得する。
 *
 *  1. 作品ページの `<img>` から同一作品 ID の画像 URL を抽出する（通常はこれで全ページ取得できる）
 *  2. 取得できなかった場合は連番 URL を探索する（Python 版互換のフォールバック）
 */
class MomongaDownloader : DownloadEngine {

    override suspend fun download(
        url: String,
        options: Map<String, String>,
        env: DownloadEnvironment,
        onProgress: ProgressCallback,
    ): String = withContext(Dispatchers.IO) {
        val workers = options["workers"]?.toIntOrNull()?.coerceIn(1, 16) ?: DEFAULT_WORKERS
        val referer = url

        onProgress(0f, "ページ情報を取得中...")
        val html = httpGetText(env, url, referer)
        val doc = Jsoup.parse(html, url)

        val title = extractTitle(doc)
        val circle = extractCircle(doc, siteNameOf(url))
        val contentId = extractContentId(url)
        val folderName = "[$circle] $title"
        val folder = File(env.ensureDir(null), env.sanitizeFileName(folderName)).apply { mkdirs() }

        // 1) 作品ページから同一作品の画像 URL を収集する。
        var images = collectImages(doc, contentId)
        // 2) 見つからない場合は連番 URL を探索する（旧 CDN 形式のフォールバック）。
        if (images.isEmpty()) images = probeSequential(doc, contentId, env, referer, onProgress)

        if (images.isEmpty()) throw RuntimeException("画像を 1 枚も検出できませんでした")

        val total = images.size
        val semaphore = Semaphore(workers)
        var done = 0

        coroutineScope {
            images.mapIndexed { index, imageUrl ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val name = "%04d.%s".format(index + 1, extensionOf(imageUrl))
                        val dest = File(folder, name)
                        if (!dest.exists()) {
                            dest.writeBytes(httpGetBytes(env, imageUrl, referer))
                        }
                        synchronized(this@MomongaDownloader) {
                            done++
                            onProgress(
                                (0.95f * done / total).coerceIn(0f, 0.95f),
                                "ダウンロード中 $done/$total 枚",
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        onProgress(1f, "完了")
        folder.absolutePath
    }

    /** ページタイトル（`<title>` の「 - 」より前。無ければ `<h1>`）。 */
    private fun extractTitle(doc: Document): String {
        val fromTitle = doc.title().split(" - ").firstOrNull()?.trim().orEmpty()
        if (fromTitle.isNotBlank()) return fromTitle
        val h1 = doc.selectFirst("h1")?.text()?.trim()
        return if (h1.isNullOrBlank()) "gallery" else h1
    }

    /**
     * サークル名（作者名）を Python 版と同じ優先順で取得する。
     *
     *  1. `div.post-tag-title` が「サークル」を含む行の `.post-tags a`
     *  2. `th` が「サークル名」の隣の `td`
     *  3. どちらも無ければサイト名（momon-ga / ddd-smart）
     */
    private fun extractCircle(doc: Document, siteName: String): String {
        val label = doc.select("div.post-tag-title").firstOrNull { it.text().contains("サークル") }
        val fromTag = label?.closest("div.post-tag-table")
            ?.selectFirst(".post-tags a")?.text()?.trim()
        if (!fromTag.isNullOrBlank()) return fromTag

        val th = doc.select("th").firstOrNull { it.text().contains("サークル名") }
        val fromTable = th?.nextElementSibling()
            ?.takeIf { element -> element.tagName().equals("td", ignoreCase = true) }
            ?.text()?.trim()
        if (!fromTable.isNullOrBlank()) return fromTable

        return siteName
    }

    /** URL からサイト名を判定する（フォルダ名のフォールバックに使用）。 */
    private fun siteNameOf(url: String): String =
        if (url.lowercase().contains("ddd-smart")) "ddd-smart" else "momon-ga"

    /**
     * URL から作品 ID を取り出す。
     *
     * - momon-ga : `/magazine/mo2332524/` → `2332524`
     * - ddd-smart: `?cn=xxx/yyy` / `?g=xxx&dir=yyy` → `xxx_yyy`
     */
    fun extractContentId(url: String): String {
        val lower = url.lowercase()
        if (lower.contains("ddd-smart")) {
            val query = url.substringAfter('?', "")
                .split('&')
                .mapNotNull { part ->
                    val keyValue = part.split('=', limit = 2)
                    if (keyValue.size == 2) keyValue[0] to keyValue[1] else null
                }
                .toMap()
            query["cn"]?.takeIf { it.isNotBlank() }?.let { return it.replace("/", "_") }
            val group = query["g"].orEmpty()
            val dir = query["dir"].orEmpty()
            return if (group.isNotBlank() && dir.isNotBlank()) "${group}_$dir" else ""
        }
        val match = CONTENT_ID_REGEX.find(url) ?: return ""
        return match.value.replace("mo", "")
    }

    /**
     * ページ内の `<img>` から同一作品の画像 URL を抽出する。
     *
     * 同一作品 ID の画像が 1 枚も無い場合は、画像らしい URL をすべて採用する。
     */
    private fun collectImages(doc: Document, contentId: String): List<String> {
        val candidates = LinkedHashSet<String>()
        doc.select("img").forEach { el ->
            val raw = IMAGE_ATTRS.firstNotNullOfOrNull { attr ->
                el.absUrl(attr).takeIf { it.isNotBlank() }
            } ?: return@forEach
            if (raw.startsWith("http") && looksLikeImage(raw)) candidates.add(raw)
        }

        val sameWork = if (contentId.isBlank()) {
            emptyList()
        } else {
            candidates.filter { it.contains("/$contentId/") || it.contains("_$contentId/") }
        }

        val selected = sameWork.ifEmpty { candidates.toList() }
        return selected.sortedWith(compareBy({ pageNumberOf(it) }, { it }))
    }

    /**
     * 連番 URL を探索するフォールバック（Python 版互換）。
     *
     * ページ内で見つかった画像 URL のディレクトリを基点に、拡張子違いも含めて
     * `1,2,3...` と取得を試し、[MAX_CONSECUTIVE_MISSES] 回連続で失敗したら終了する。
     */
    private fun probeSequential(
        doc: Document,
        contentId: String,
        env: DownloadEnvironment,
        referer: String,
        onProgress: ProgressCallback,
    ): List<String> {
        val prefix = guessBaseUrl(doc, contentId) ?: return emptyList()
        onProgress(0f, "画像 URL を探索中...")

        val found = mutableListOf<String>()
        var misses = 0
        var page = 1
        var keptExt: String? = null
        while (page <= MAX_PROBE_PAGES && misses < MAX_CONSECUTIVE_MISSES) {
            val extensions = keptExt?.let { listOf(it) } ?: PROBE_EXTENSIONS
            var hit: String? = null
            for (ext in extensions) {
                val candidate = "$prefix$page.$ext"
                if (exists(env, candidate, referer)) {
                    hit = candidate
                    keptExt = ext
                    break
                }
            }
            if (hit != null) {
                found.add(hit)
                misses = 0
                onProgress(0.2f, "画像 URL を探索中... ${found.size} 枚")
            } else {
                misses++
            }
            page++
        }
        return found
    }

    /** ページ内の画像 URL から連番の基点（`.../galleries/<id>/`）を推定する。 */
    private fun guessBaseUrl(doc: Document, contentId: String): String? {
        if (contentId.isBlank()) return null
        val known = doc.select("img").mapNotNull { el ->
            IMAGE_ATTRS.firstNotNullOfOrNull { attr -> el.absUrl(attr).takeIf { it.isNotBlank() } }
        }.firstOrNull { it.contains("/$contentId/") }
        return known?.substringBeforeLast('/')?.plus("/")
    }

    /** 指定 URL が存在するか（HEAD、HEAD 非対応サーバ向けに GET も試す）。 */
    private fun exists(env: DownloadEnvironment, url: String, referer: String): Boolean {
        val head = runCatching {
            env.client.newCall(buildRequest(env, url, referer).head().build())
                .execute().use { it.isSuccessful }
        }.getOrNull()
        if (head == true) return true
        return runCatching {
            env.client.newCall(buildRequest(env, url, referer).build())
                .execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private fun buildRequest(
        env: DownloadEnvironment,
        url: String,
        referer: String,
    ): Request.Builder = Request.Builder()
        .url(url)
        .header("User-Agent", env.userAgent)
        .header("Referer", referer)
        .header("Accept", "*/*")

    private fun httpGetText(env: DownloadEnvironment, url: String, referer: String): String =
        httpGetBytes(env, url, referer).toString(Charsets.UTF_8)

    private fun httpGetBytes(env: DownloadEnvironment, url: String, referer: String): ByteArray =
        env.client.newCall(buildRequest(env, url, referer).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} ${resp.message}")
            resp.body?.bytes() ?: throw RuntimeException("レスポンスが空です")
        }

    /** 画像 URL かどうか（拡張子 / パスで判定）。 */
    private fun looksLikeImage(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return IMAGE_EXTENSIONS.any { lower.endsWith(".$it") } || lower.contains("/galleries/")
    }

    private fun extensionOf(url: String): String {
        val ext = url.substringBefore('?').substringAfterLast('.', "")
        return if (ext.length in 2..4 && ext.all { it.isLetterOrDigit() }) ext else "jpg"
    }

    /** ファイル名末尾の連番（`.../2332524/12.webp` → 12）。 */
    private fun pageNumberOf(url: String): Int =
        url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').toIntOrNull()
            ?: Int.MAX_VALUE

    companion object {
        private const val DEFAULT_WORKERS = 10
        private const val MAX_PROBE_PAGES = 2000
        private const val MAX_CONSECUTIVE_MISSES = 3

        /** 作品 ID 抽出（`mo2332524` または 4 桁以上の数字列）。 */
        private val CONTENT_ID_REGEX = Regex("""mo\d+|\d{4,}""", RegexOption.IGNORE_CASE)

        /** 画像 URL が入りうる img 属性。 */
        private val IMAGE_ATTRS = listOf("src", "data-src", "data-lazy-src", "data-original")

        private val IMAGE_EXTENSIONS = listOf("webp", "jpg", "jpeg", "png", "gif", "avif")
        private val PROBE_EXTENSIONS = listOf("webp", "jpg", "png", "jpeg")
    }
}
