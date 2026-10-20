package com.nexus.downloader.ui.downloads

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.downloader.AppContainer
import com.nexus.downloader.R
import com.nexus.downloader.data.file.FileOpener
import com.nexus.downloader.data.model.DownloadTask
import com.nexus.downloader.data.model.OptionSpec
import com.nexus.downloader.data.model.RegisteredAddon
import com.nexus.downloader.data.share.ShareInbox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ダウンロード画面の状態管理。
 * URL 入力・オプション・実行中タスク・共有受信・クリップボード提案を扱う。
 */
class DownloadsViewModel(private val container: AppContainer) : ViewModel() {

    private val strings: android.content.Context get() = container.appContext

    private val _urlText = MutableStateFlow("")
    val urlText: StateFlow<String> = _urlText.asStateFlow()

    private val _optionValues = MutableStateFlow<Map<String, String>>(emptyMap())
    val optionValues: StateFlow<Map<String, String>> = _optionValues.asStateFlow()

    /** クリップボードから取り込める URL の提案（無ければ null）。 */
    private val _clipboardSuggestion = MutableStateFlow<String?>(null)
    val clipboardSuggestion: StateFlow<String?> = _clipboardSuggestion.asStateFlow()

    /** 軽い操作メッセージ（Snackbar 用）。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** 内蔵/外部アドオンの一覧（URL 判定に使用）。 */
    val addons: StateFlow<List<RegisteredAddon>> = container.addonRepository.addons

    val tasks: StateFlow<List<DownloadTask>> = container.downloadManager.tasks
    val isRunning: StateFlow<Boolean> = container.downloadManager.isRunning

    /** 一時停止中か（キューは残ったまま）。 */
    val isPaused: StateFlow<Boolean> = container.downloadManager.isPaused

    /** 表示中のバッチ番号（全体の進捗の計算に使う）。 */
    val currentBatch: StateFlow<Long> = container.downloadManager.currentBatch

    init {
        // 他アプリからの共有（Morphe YT 等）を購読して URL 欄へ流し込む。
        viewModelScope.launch {
            ShareInbox.pending.collect { shared ->
                Log.d(TAG, "share collect shared=$shared")
                if (shared.isNullOrBlank()) return@collect
                ShareInbox.consume()
                // 共有取り込み回数を統計へ記録し、関連バッジを判定する。
                container.configRepository.update { it.copy(statShared = it.statShared + 1) }
                container.downloadManager.checkBadges()

                val config = container.configRepository.current()
                Log.d(TAG, "share autoDownloadOnShare=${config.autoDownloadOnShare}")
                if (config.autoDownloadOnShare) {
                    _urlText.value = shared
                    enqueueCurrent()
                } else {
                    _urlText.value = appendToInput(shared)
                    _message.value = "共有された URL を入力しました"
                }
            }
        }
    }

    fun onUrlChange(value: String) {
        _urlText.value = value
    }

    fun onOptionChange(key: String, value: String) {
        _optionValues.value = _optionValues.value + (key to value)
    }

    /** 指定アドオンのダウンロード設定項目。 */
    fun optionsFor(addonId: String): List<OptionSpec> = container.addonRepository.optionsFor(addonId)

    /** 既定値 + ユーザー上書きをマージした実効オプション。 */
    fun effectiveOptions(addonId: String): Map<String, String> =
        optionsFor(addonId).associate { spec ->
            spec.key to (_optionValues.value[spec.key] ?: spec.default)
        }

    /** 先頭 URL にマッチするアドオン。 */
    fun detectAddon(): RegisteredAddon? =
        firstUrl()?.let { container.addonRepository.findForUrl(it) }

    fun start() {
        enqueueCurrent()
    }

    /**
     * 入力欄の URL をキューへ投入する。
     *
     * 実行中でも追加でき、その場合は現在のキューの後ろに積まれる。
     */
    private fun enqueueCurrent() {
        val manager = container.downloadManager
        val wasRunning = manager.isRunning.value
        val addon = detectAddon()
        val options = addon?.let { effectiveOptions(it.spec.id) } ?: emptyMap()
        val added = manager.enqueue(_urlText.value, options)
        _message.value = when {
            !added -> "すでにキューに入っています"
            wasRunning -> "キューに追加しました"
            else -> null
        }
    }

    fun cancelAll() = container.downloadManager.cancelAll()

    /** 実行中のダウンロードを一時停止する（待機中タスクは残る）。 */
    fun pause() {
        container.downloadManager.pause()
        _message.value = "一時停止しました（通知や画面の「再開」で続きから）"
    }

    /** 一時停止したキューを再開する。 */
    fun resume() {
        container.downloadManager.resume()
        _message.value = "再開しました"
    }

    /** 実行中/待機中の 1 件だけを中止する。 */
    fun cancelTask(task: DownloadTask) {
        container.downloadManager.cancelTask(task.id)
        _message.value = "1 件を中止しました"
    }

    /** 待機中のタスクをキューの先頭へ移動する。 */
    fun prioritizeTask(task: DownloadTask) {
        container.downloadManager.prioritize(task.id)
        _message.value = "キューの先頭へ移動しました"
    }

    fun clearFinished() = container.downloadManager.clearFinished()

    fun removeTask(id: String) = container.downloadManager.removeTask(id)

    /** 失敗/スキップした 1 件を再試行する。 */
    fun retryTask(task: DownloadTask) {
        container.downloadManager.retry(task.id)
        _message.value = "再試行を開始しました"
    }

    // ---------------- 便利機能 ----------------

    /** クリップボードの URL を提案する（画面表示時に呼ぶ）。 */
    fun refreshClipboardSuggestion(context: Context) {
        viewModelScope.launch {
            val config = container.configRepository.current()
            if (!config.clipNotify) return@launch
            val clip = ClipboardUtil.readText(context) ?: return@launch
            val url = ShareInbox.extractUrl(clip) ?: return@launch
            _clipboardSuggestion.value = if (_urlText.value.contains(url)) null else url
        }
    }

    /** 提案を入力欄へ反映する。 */
    fun acceptClipboardSuggestion() {
        val url = _clipboardSuggestion.value ?: return
        _urlText.value = appendToInput(url)
        _clipboardSuggestion.value = null
    }

    /** 提案を閉じる。 */
    fun dismissClipboardSuggestion() {
        _clipboardSuggestion.value = null
    }

    /** 入力欄へ URL を追記する（重複は無視）。 */
    fun addUrlToInput(url: String) {
        _urlText.value = appendToInput(url)
    }

    /** クリップボードから直接貼り付ける。 */
    fun pasteFromClipboard(context: Context) {
        val clip = ClipboardUtil.readText(context)
        if (clip.isNullOrBlank()) {
            _message.value = "クリップボードが空です"
            return
        }
        val url = ShareInbox.extractUrl(clip) ?: clip.trim()
        _urlText.value = appendToInput(url)
    }

    /** 完了タスクのファイルを開く。 */
    fun openTask(context: Context, task: DownloadTask) {
        val path = task.outputPath ?: return
        if (!FileOpener.open(context, path)) {
            _message.value = "開けるアプリが見つかりませんでした"
        }
    }

    /** 完了タスクのファイルを共有する。 */
    fun shareTask(context: Context, task: DownloadTask) {
        val path = task.outputPath ?: return
        if (!FileOpener.share(context, path)) {
            _message.value = "共有できるアプリが見つかりませんでした"
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun firstUrl(): String? =
        _urlText.value.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }

    private fun appendToInput(url: String): String {
        val current = _urlText.value.trim()
        return when {
            current.isEmpty() -> url
            current.contains(url) -> current
            else -> "$current\n$url"
        }
    }

    companion object {
        private const val TAG = "NexusShare"
    }
}
