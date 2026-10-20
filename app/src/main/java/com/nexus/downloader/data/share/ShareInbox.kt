package com.nexus.downloader.data.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 他アプリ（Morphe YT / ブラウザ / SNS など）から共有された URL の受け箱。
 *
 * [MainActivity] が受け取った共有テキストを [post] し、Compose 側
 * （ダウンロード画面）が [pending] を購読して URL 欄へ流し込む。
 * 受け取り後は [consume] でクリアする。
 */
object ShareInbox {

    private val _pending = MutableStateFlow<String?>(null)

    /** 未処理の共有 URL（無ければ null）。 */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    /**
     * 共有を受け取った回数の通し番号。
     *
     * [pending] はダウンロード画面が [consume] で消してしまうため、タブ切り替えの
     * トリガにはこの値を使う（消費の影響を受けない）。
     */
    private val _shareEvent = MutableStateFlow(0L)
    val shareEvent: StateFlow<Long> = _shareEvent.asStateFlow()

    /** 共有テキストを登録する（URL を抽出できた場合のみ）。 */
    fun post(rawText: String?) {
        val url = extractUrl(rawText) ?: return
        _pending.value = url
        _shareEvent.value += 1
    }

    /** まとめて複数の共有テキストから URL を抽出して登録する。 */
    fun postAll(texts: List<String>?) {
        val urls = texts.orEmpty().mapNotNull { extractUrl(it) }
        if (urls.isEmpty()) return
        _pending.value = urls.joinToString("\n")
        _shareEvent.value += 1
    }

    /** 処理済みにする。 */
    fun consume() {
        _pending.value = null
    }

    /**
     * 共有テキストから URL を抽出する。
     *
     * Morphe YT 等は「タイトル + 改行 + URL」の形で渡してくることが多いため、
     * 最初に見つかった http/https の塊を採用する。
     */
    fun extractUrl(rawText: String?): String? {
        val text = rawText?.trim().orEmpty()
        if (text.isEmpty()) return null

        // すでに URL 単体ならそのまま返す
        if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
            return text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        }

        val match = URL_REGEX.find(text) ?: return null
        return match.value.trim().trimEnd('.', ',', ')', '」', '】')
    }

    private val URL_REGEX = Regex("""https?://[^\s"'<>（）()\[\]「」【】]+""", RegexOption.IGNORE_CASE)
}
