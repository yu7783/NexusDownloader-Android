package com.nexus.downloader.ui.downloads

import android.content.ClipboardManager
import android.content.Context
import androidx.core.content.getSystemService

/** クリップボード読み取りユーティリティ（Android 13+ のプライバシー制約にも対応）。 */
object ClipboardUtil {

    /**
     * クリップボードのテキストを読む。取得できない場合は null。
     * ※ 他アプリを前面にした直後などは OS が空を返すことがある。
     */
    fun readText(context: Context): String? {
        val manager = context.getSystemService<ClipboardManager>() ?: return null
        if (!manager.hasPrimaryClip()) return null
        val clip = manager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0).coerceToText(context)?.toString()
        return text?.trim()?.takeIf { it.isNotEmpty() }
    }
}
