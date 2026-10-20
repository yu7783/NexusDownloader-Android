package com.nexus.downloader.data.share

import android.content.Intent

/**
 * 共有（ACTION_SEND / ACTION_SEND_MULTIPLE）と「リンクを開く」（ACTION_VIEW）の
 * インテントから URL を取り出す。
 *
 * 送信元アプリによって URL の入れ方が異なるため、取り出せるものを順に試す。
 *
 * - [Intent.EXTRA_TEXT] に String / CharSequence / String[] / ArrayList で入る
 * - [Intent.getClipData] に入る（YouTube / Morphe YT など）
 * - ACTION_VIEW は [Intent.getData] に URI が入る
 *
 * [MainActivity] と [com.nexus.downloader.ui.share.ShareOptionsActivity] の両方から使う。
 */
object ShareIntentUrls {

    /** インテントに含まれる URL を重複なしで返す。 */
    fun from(intent: Intent?): List<String> {
        intent ?: return emptyList()
        val raw: List<String> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.firstText())
            Intent.ACTION_SEND_MULTIPLE -> intent.multiTexts()
            Intent.ACTION_VIEW -> listOfNotNull(intent.dataString ?: intent.firstText())
            else -> emptyList()
        }
        return raw.mapNotNull { ShareInbox.extractUrl(it) }.distinct()
    }

    /**
     * 共有テキストを取り出す。
     *
     * YouTube / Morphe YT など一部のアプリは [Intent.EXTRA_TEXT] ではなく
     * [Intent.getClipData] に URL を入れて共有してくるため、両方を確認する。
     */
    private fun Intent.firstText(): String? {
        getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { return it }
        // EXTRA_TEXT が String 以外（Spanned 等）で入っている場合の保険。
        getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return clipTexts().firstOrNull()
    }

    /**
     * [Intent.ACTION_SEND_MULTIPLE] の複数テキストを取り出す。
     *
     * 送信元によって型が異なる（`ArrayList<String>` / `ArrayList<CharSequence>` /
     * `String[]` / `CharSequence`）ため、型付き getter を順に試し、
     * どれも取れなければ [getClipData] を使う。
     */
    private fun Intent.multiTexts(): List<String> {
        val values = getStringArrayListExtra(Intent.EXTRA_TEXT)
            ?: getCharSequenceArrayListExtra(Intent.EXTRA_TEXT)?.map { it.toString() }
            ?: getStringArrayExtra(Intent.EXTRA_TEXT)?.toList()
            ?: getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { listOf(it.toString()) }
            ?: emptyList()
        val cleaned = values.map { it.trim() }.filter { it.isNotEmpty() }
        return cleaned.ifEmpty { clipTexts() }
    }

    private fun Intent.clipTexts(): List<String> {
        val clip = clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { index ->
            clip.getItemAt(index).text?.toString()?.takeIf { it.isNotBlank() }
        }
    }
}
