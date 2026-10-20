package com.nexus.downloader.data.security

import com.nexus.downloader.data.model.AppConfig

/**
 * 危険コード/危険記述を実行前に弾くセキュリティ・ガードマン（Python 版 security.py の移植）。
 *
 * Android 版ではアドオンは宣言的 JSON だが、外部ファイルを取り込む以上、
 * 「危険なキーワードを含むファイルは読み込まない」という防御は維持する。
 */
object SecurityGuard {

    /** テキストを走査する。安全なら (true, "")、危険なら (false, 検知したキーワード)。 */
    fun scanText(text: String, blockedKeywords: List<String> = AppConfig.DEFAULT_BLOCKED_KEYWORDS): ScanResult {
        val keywords = blockedKeywords.ifEmpty { AppConfig.DEFAULT_BLOCKED_KEYWORDS }
        val lowered = text.lowercase()
        for (kw in keywords) {
            if (kw.isBlank()) continue
            if (lowered.contains(kw.lowercase())) {
                return ScanResult(false, kw)
            }
        }
        return ScanResult(true, "")
    }

    /** ファイル内容を走査する。 */
    fun scanFile(bytes: ByteArray, blockedKeywords: List<String>): ScanResult =
        scanText(bytes.toString(Charsets.UTF_8), blockedKeywords)
}

/**
 * スキャン結果。
 *
 * @property reason 危険と判定した場合に、検知したキーワードが入る。
 *   表示用の文言は呼び出し側で文字列リソースから組み立てる。
 */
data class ScanResult(val safe: Boolean, val reason: String)
