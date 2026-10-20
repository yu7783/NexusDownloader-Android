package com.nexus.downloader.data.addon

import android.content.Context
import com.nexus.downloader.R
import com.nexus.downloader.data.model.AddonOrigin
import com.nexus.downloader.data.model.AddonSpec
import com.nexus.downloader.data.model.OptionSpec
import com.nexus.downloader.data.model.RegisteredAddon
import com.nexus.downloader.data.security.SecurityGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * アドオンの読み込み/管理（Python 版 core.Core の動的ロード部分の移植）。
 *
 * - 内蔵アドオン（[BuiltinAddons]）は常に利用可能。
 * - 外部アドオンは **ローカルの JSON ファイルからのみ** 取り込む
 *   （ネットワーク経由の追加は行わない）。取り込み前に [SecurityGuard] で走査する。
 */
class AddonRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val addonDir: File get() = File(context.filesDir, "addons").apply { mkdirs() }

    private val _addons = MutableStateFlow<List<RegisteredAddon>>(emptyList())
    val addons: StateFlow<List<RegisteredAddon>> = _addons.asStateFlow()

    /** 内蔵 + 外部アドオンを再読込する。 */
    fun reload() {
        val imported = loadImported()
        _addons.value = BuiltinAddons.all(context.resources) + imported
    }

    private fun loadImported(): List<RegisteredAddon> {
        val dir = addonDir
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?.mapNotNull { file ->
                runCatching {
                    val spec = json.decodeFromString(AddonSpec.serializer(), file.readText())
                    RegisteredAddon(spec, AddonOrigin.IMPORTED, file.name)
                }.getOrNull()
            } ?: emptyList()
    }

    /**
     * 外部アドオンを取り込む。
     *
     * @return 成功なら (true, メッセージ)、失敗なら (false, 理由)。
     */
    suspend fun import(rawText: String, fileName: String, blockedKeywords: List<String>): ImportResult =
        withContext(Dispatchers.IO) {
            // 1) セキュリティゲート
            val scan = SecurityGuard.scanText(rawText, blockedKeywords)
            if (!scan.safe) {
                return@withContext ImportResult(
                    false,
                    context.getString(R.string.addon_install_rejected, scan.reason),
                    null,
                )
            }

            // 2) JSON として解釈
            val spec = runCatching {
                json.decodeFromString(AddonSpec.serializer(), rawText)
            }.getOrElse {
                return@withContext ImportResult(
                    false,
                    context.getString(R.string.addon_parse_failed, it.message.orEmpty()),
                    null,
                )
            }
            if (spec.id.isBlank()) {
                return@withContext ImportResult(false, context.getString(R.string.addon_id_empty), null)
            }
            if (spec.urlPatterns.isEmpty()) {
                return@withContext ImportResult(false, context.getString(R.string.addon_no_patterns), null)
            }

            // 3) ファイルとして保存（id をファイル名にする）
            val dest = File(addonDir, "${spec.id}.json")
            dest.writeText(json.encodeToString(AddonSpec.serializer(), spec))
            reload()
            ImportResult(true, context.getString(R.string.addon_added, spec.name), spec.id)
        }

    /** 外部アドオンを削除する。内蔵アドオンは削除できない。 */
    suspend fun delete(addon: RegisteredAddon): Boolean = withContext(Dispatchers.IO) {
        if (addon.origin != AddonOrigin.IMPORTED) return@withContext false
        val file = addon.sourceFile?.let { File(addonDir, it) } ?: return@withContext false
        val ok = !file.exists() || file.delete()
        reload()
        ok
    }

    /**
     * URL にマッチするアドオンを返す。Python 版と同じく、
     * より長いパターンで一致したものを優先する。
     */
    fun findForUrl(url: String): RegisteredAddon? {
        val lowered = url.lowercase()
        return _addons.value
            .flatMap { addon -> addon.spec.urlPatterns.map { it.lowercase() to addon } }
            .filter { (pattern, _) -> lowered.contains(pattern) }
            .maxByOrNull { (pattern, _) -> pattern.length }
            ?.second
    }

    /** アドオンのダウンロード設定項目を返す（内蔵のみ定義を持つ）。 */
    fun optionsFor(addonId: String): List<OptionSpec> =
        BuiltinAddons.optionsFor(context.resources, addonId)
}

/** インポート結果。 */
data class ImportResult(val success: Boolean, val message: String, val addonId: String?)
