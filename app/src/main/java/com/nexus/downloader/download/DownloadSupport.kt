package com.nexus.downloader.download

import com.nexus.downloader.data.model.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * 進捗コールバック（percent: 0f..1f, message）。
 *
 * StateFlow への値更新は同期で行えるため非 suspend とする。これにより
 * youtubedl-android の同期コールバックからも直接呼び出せる。
 */
typealias ProgressCallback = (percent: Float, message: String) -> Unit

/** 既定の User-Agent（Cloudflare の bot 判定回避のためブラウザ相当）。 */
const val DEFAULT_USER_AGENT: String =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

/** エンジン実行に必要な環境情報。 */
class DownloadEnvironment(
    /** 保存先ルート（アプリ専用外部ストレージ + 設定フォルダ）。 */
    val rootDir: File,
    /** 現在の設定（スナップショット）。 */
    val config: AppConfig,
    /** 共通 HTTP クライアント。 */
    val client: OkHttpClient,
) {
    val userAgent: String get() = DEFAULT_USER_AGENT

    /** ファイル名として安全な文字列に整形する。 */
    fun sanitizeFileName(name: String, maxLength: Int = 150): String {
        val cleaned = name.replace(Regex("""[\\/:*?"<>|\r\n\t]+"""), "_").trim().trim('.')
        val safe = cleaned.ifBlank { "download" }
        return if (safe.length <= maxLength) safe else safe.substring(0, maxLength)
    }

    /** 新しい保存先ディレクトリ（存在保証つき）。 */
    fun ensureDir(relative: String?): File {
        val dir = if (relative.isNullOrBlank()) rootDir else File(rootDir, relative)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}

/** 設定に応じた OkHttpClient を生成する。 */
fun buildHttpClient(config: AppConfig): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(config.timeout.coerceAtLeast(5).toLong(), TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // 巨大ファイルのストリーミングを許容
        .writeTimeout(config.timeout.coerceAtLeast(5).toLong(), TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

/** 途中まで保存したファイルの拡張子（一時停止 / 失敗からの再開に使う）。 */
const val PART_SUFFIX = ".part"

/** ダウンロードの受信バッファ。 */
private const val STREAM_BUFFER_SIZE = 256 * 1024

/** HTTP 206 Partial Content（= レジューム成功）。 */
private const val HTTP_PARTIAL = 206

/**
 * `.part` に書き込みながらダウンロードし、完了時に最終名へ移動する。
 *
 * 一時停止や失敗で残った `.part` があれば `Range` で続きから取得する
 * （サーバが 206 を返した場合）。レジューム非対応（200 が返る）なら最初から取り直す。
 * キャンセル（一時停止 / 中止）はチャンクごとに [ensureActive] で検知する。
 *
 * @param onProgress (受信済みバイト, 全体バイト。不明なら -1)
 * @return 保存したファイル（[dest]）
 */
suspend fun downloadResumable(
    client: OkHttpClient,
    url: String,
    headers: Map<String, String> = emptyMap(),
    dest: File,
    resolveFinalName: ((okhttp3.Response) -> String?)? = null,
    onProgress: (downloaded: Long, total: Long) -> Unit,
): File = withContext(Dispatchers.IO) {
    val parent = dest.parentFile ?: throw IllegalStateException("保存先が不正です: $dest")
    if (!parent.exists()) parent.mkdirs()

    // 途中まで保存したファイル（あれば続きから取得する）。
    val part = File(parent, dest.name + PART_SUFFIX)
    val existing = if (part.isFile) part.length() else 0L

    val builder = Request.Builder().url(url).header("Accept", "*/*")
    headers.forEach { (key, value) -> builder.header(key, value) }
    if (existing > 0L) builder.header("Range", "bytes=$existing-")

    var preferredName: String? = null
    client.newCall(builder.build()).execute().use { response ->
        if (!response.isSuccessful) throw RuntimeException("HTTP ${response.code} ${response.message}")
        val body = response.body ?: throw RuntimeException("レスポンスが空です")
        // Content-Disposition などから最終ファイル名が分かる場合に受け取る。
        preferredName = resolveFinalName?.invoke(response)?.takeIf { it.isNotBlank() }

        val resumed = response.code == HTTP_PARTIAL && existing > 0L
        if (!resumed && part.isFile) part.delete()

        val start = if (resumed) existing else 0L
        val length = body.contentLength()
        val total = if (length >= 0L) start + length else -1L
        var downloaded = start
        onProgress(downloaded, total)

        body.byteStream().use { input ->
            FileOutputStream(part, resumed).use { output ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    // 一時停止 / 中止はここで抜ける（.part はそのまま残す）。
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, total)
                }
            }
        }
    }

    // 完了: 最終名へ移動する（サーバ指定名があればそれを使う）。
    val finalFile = when {
        preferredName == null || preferredName == dest.name -> dest
        // 同名の既存ファイルは上書きしない（dest は採番済み）。
        File(parent, preferredName).exists() -> dest
        else -> File(parent, preferredName)
    }
    if (finalFile.exists()) finalFile.delete()
    if (!part.renameTo(finalFile)) {
        part.copyTo(finalFile, overwrite = true)
        part.delete()
    }
    finalFile
}

/**
 * 保存ファイル名の予約。
 *
 * 並列ダウンロードでは「同名の別 URL」や「サーバが同じファイル名を返す URL」で
 * 宛先が衝突し、`.part` を奪い合って壊れることがある。ディレクトリ + 名前を
 * `synchronized` で予約し、空いている名前だけを返す。
 */
object FileNameReservations {

    private val lock = Any()

    /** 予約中の `.part` パス。 */
    private val inUse = mutableSetOf<String>()

    /**
     * 使える保存先を確保して返す。
     *
     * 途中まで保存した `.part` があればレジュームのため同じ名前を再利用する。
     * それ以外は既存ファイル / 予約済みと衝突しないよう " (1)" 等を付ける。
     */
    fun reserve(dir: File, preferred: String): File = synchronized(lock) {
        val partOf = { name: String -> File(dir, name + PART_SUFFIX).absolutePath }

        if (File(dir, preferred + PART_SUFFIX).isFile && partOf(preferred) !in inUse) {
            inUse += partOf(preferred)
            return@synchronized File(dir, preferred)
        }

        val dot = preferred.lastIndexOf('.')
        val base = if (dot > 0) preferred.substring(0, dot) else preferred
        val ext = if (dot > 0) preferred.substring(dot) else ""

        var candidate = preferred
        var index = 1
        while (File(dir, candidate).exists() || partOf(candidate) in inUse) {
            candidate = "$base ($index)$ext"
            index++
        }
        inUse += partOf(candidate)
        File(dir, candidate)
    }

    /** 予約を解放する（完了 / 失敗 / 中止）。 */
    fun release(file: File) = synchronized(lock) {
        inUse.remove(File(file.parentFile, file.name + PART_SUFFIX).absolutePath)
    }
}

/** 拡張子から安全なファイル名を組み立てるユーティリティ。 */
object FileNames {
    fun fromUrl(url: String, fallback: String = "download"): String {
        val path = runCatching { java.net.URI(url).path }.getOrNull() ?: return fallback
        val last = path.substringAfterLast('/', "")
        return if (last.isBlank()) fallback else last
    }

    fun withExtension(base: String, ext: String): String {
        val cleanExt = ext.removePrefix(".")
        if (cleanExt.isBlank()) return base
        return if (base.lowercase().endsWith(".$cleanExt")) base else "$base.$cleanExt"
    }
}
