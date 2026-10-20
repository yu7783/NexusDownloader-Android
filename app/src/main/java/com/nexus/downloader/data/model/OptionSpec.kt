package com.nexus.downloader.data.model

import androidx.annotation.StringRes
import com.nexus.downloader.R

/** オプション入力の種類。 */
enum class OptionType { SELECT, TEXT, PASSWORD }

/**
 * アドオンが提示する「ダウンロード設定」の 1 項目。
 * Python 版 `get_options()` の dict を型付きで表現したもの（UI 専用で永続化しない）。
 *
 * [choices] はエンジンへ渡す内部値（ASCII の ID）で、画面には [choiceLabelRes] で
 * 文字列リソースに変換した表示名を出す。値そのものを翻訳すると、
 * 言語を切り替えたときにエンジン側の判定と食い違うため ID を固定している。
 */
data class OptionSpec(
    val key: String,
    val label: String,
    val type: OptionType = OptionType.SELECT,
    val choices: List<String> = emptyList(),
    val default: String = "",
    val content: String = "",
) {
    companion object {
        fun select(key: String, label: String, choices: List<String>, default: String, content: String = "") =
            OptionSpec(key, label, OptionType.SELECT, choices, default, content)

        fun text(key: String, label: String, default: String = "", content: String = "", password: Boolean = false) =
            OptionSpec(key, label, if (password) OptionType.PASSWORD else OptionType.TEXT, emptyList(), default, content)

        /**
         * 選択肢の内部値を画面表示用の文字列リソースへ変換する。
         *
         * 表示名を持たない選択肢（"best" / "mp4" / "1080p" など言語に依存しない値）は
         * null を返し、呼び出し側は内部値をそのまま表示する。
         */
        @StringRes
        fun choiceLabelRes(key: String, value: String): Int? = when (key) {
            "login" -> when (value) {
                "none" -> R.string.choice_login_none
                "cookies" -> R.string.choice_login_cookies
                "credentials" -> R.string.choice_login_credentials
                else -> null
            }

            "playlist" -> when (value) {
                "all" -> R.string.choice_playlist_all
                "first" -> R.string.choice_playlist_first
                else -> null
            }

            "quality" -> if (value == "audio only") R.string.choice_audio_only else null

            else -> null
        }
    }
}
