package com.nexus.downloader.ui.addons

import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexus.downloader.AppContainer
import com.nexus.downloader.data.addon.ImportResult
import com.nexus.downloader.data.model.RegisteredAddon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 一時的な通知（Snackbar 表示用）。 */
data class AddonNotice(val success: Boolean, val message: String)

/** アドオン管理画面の状態管理。 */
class AddonsViewModel(private val container: AppContainer) : ViewModel() {

    val addons: StateFlow<List<RegisteredAddon>> = container.addonRepository.addons

    private val _notice = MutableStateFlow<AddonNotice?>(null)
    val notice: StateFlow<AddonNotice?> = _notice.asStateFlow()

    /** ローカルのファイル（SAF で選択した URI）からアドオンを取り込む。 */
    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val fileName = queryDisplayName(uri) ?: "addon.json"
                    val text = container.appContext.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("ファイルを読み込めませんでした")
                    val config = container.configRepository.current()
                    container.addonRepository.import(text, fileName, config.blockedKeywords)
                }.getOrElse { e ->
                    ImportResult(false, "取り込みに失敗しました: ${e.message}", null)
                }
            }
            _notice.value = AddonNotice(result.success, result.message)
        }
    }

    fun delete(addon: RegisteredAddon) {
        viewModelScope.launch {
            val ok = container.addonRepository.delete(addon)
            _notice.value = AddonNotice(
                ok,
                if (ok) "${addon.spec.name} を削除しました。"
                else "削除できませんでした（内蔵アドオンは削除不可）。",
            )
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
            container.appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()
}
