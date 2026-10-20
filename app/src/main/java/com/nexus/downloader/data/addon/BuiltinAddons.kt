package com.nexus.downloader.data.addon

import android.content.res.Resources
import com.nexus.downloader.R
import com.nexus.downloader.data.model.AddonOrigin
import com.nexus.downloader.data.model.AddonSpec
import com.nexus.downloader.data.model.AddonType
import com.nexus.downloader.data.model.GallerySpec
import com.nexus.downloader.data.model.OptionSpec
import com.nexus.downloader.data.model.RegisteredAddon

/**
 * 本体に内蔵するアドオン群。
 *
 * Python 版の `addons/yt_dlp_addon.py` / `iwara_addon.py` / `momonga_addon.py` を
 * Kotlin 実装へ移植したもの。UI 側は [optionsFor] で各アドオンの設定項目を取得する。
 *
 * 表示文字列は端末の言語に合わせるため、[Resources] を受け取って解決する
 * （内蔵アドオンの説明や設定項目のラベルはすべて文字列リソースから取得する）。
 */
object BuiltinAddons {

    /** yt-dlp アドオン（Python: yt_dlp_addon.py / version 1.2.0）。 */
    const val YT_DLP_ID = "yt_dlp_core"

    /** Iwara アドオン（Python: iwara_addon.py / version 1.0.0）。 */
    const val IWARA_ID = "iwara"

    /** momon-ga / ddd-smart アドオン（Python: momonga_addon.py のレガシーサイト部分）。 */
    const val MOMONGA_ID = "momonga_multi"

    /** 画像ギャラリーアドオン（動的サイトは HTML 解析のみ対応）。 */
    const val MANGA_ID = "manga_gallery"

    /** 直リンク用フォールバック（任意の直リンクメディア）。 */
    const val DIRECT_ID = "direct_link"

    /** 内蔵アドオン一覧（優先度順）。 */
    fun all(resources: Resources): List<RegisteredAddon> = listOf(
        AddonSpec(
            id = YT_DLP_ID,
            name = "Universal Video Addon (yt-dlp)",
            version = "1.2.0",
            urlPatterns = listOf(
                "youtube.com", "youtu.be", "twitter.com", "x.com", "tiktok.com",
                "nicovideo.jp", "vimeo.com", "dailymotion.com", "instagram.com",
                "facebook.com", "twitch.tv", "bilibili.com", "soundcloud.com",
            ),
            type = AddonType.YTDLP,
            description = resources.getString(R.string.addon_ytdlp_desc),
        ),
        AddonSpec(
            id = IWARA_ID,
            name = "Iwara Downloader Addon",
            version = "1.0.0",
            urlPatterns = listOf("iwara.tv", "iwara.ai"),
            type = AddonType.IWARA,
            description = resources.getString(R.string.addon_iwara_desc),
        ),
        AddonSpec(
            id = MOMONGA_ID,
            name = "Manga Image Addon (momon-ga / ddd-smart)",
            version = "1.1.0",
            urlPatterns = listOf("momon-ga.com", "ddd-smart.net"),
            type = AddonType.MOMONGA,
            description = resources.getString(R.string.addon_momonga_desc),
        ),
        AddonSpec(
            id = MANGA_ID,
            name = "Manga Image Addon (doujin-freee / hentaipaw)",
            version = "1.0.0",
            urlPatterns = listOf("doujin-freee.cc", "hentaipaw.com"),
            type = AddonType.GALLERY,
            description = resources.getString(R.string.addon_manga_desc),
            gallery = GallerySpec(
                imageSelector = "img",
                imageAttr = "src",
                titleSelector = "h1",
            ),
        ),
        AddonSpec(
            id = DIRECT_ID,
            name = "Direct Link Downloader",
            version = "1.0.0",
            urlPatterns = listOf("://"),
            type = AddonType.DIRECT,
            description = resources.getString(R.string.addon_direct_desc),
        ),
    ).map { RegisteredAddon(it, AddonOrigin.BUILTIN) }

    /** 各アドオンのダウンロード設定項目（Python 版 `get_options()` 相当）。 */
    fun optionsFor(resources: Resources, addonId: String): List<OptionSpec> = when (addonId) {
        YT_DLP_ID -> listOf(
            OptionSpec.select(
                "quality", resources.getString(R.string.opt_quality),
                listOf("best", "1080p", "720p", "480p", "360p", "audio only"),
                "best", resources.getString(R.string.opt_quality_desc),
            ),
            OptionSpec.select(
                "format", resources.getString(R.string.opt_format),
                listOf("mp4", "webm", "mkv", "mp3", "m4a"), "mp4",
                resources.getString(R.string.opt_format_desc),
            ),
            OptionSpec.select(
                "audio_bitrate", resources.getString(R.string.opt_audio_bitrate),
                listOf("best", "320kbps", "192kbps", "128kbps"), "best",
                resources.getString(R.string.opt_audio_bitrate_desc),
            ),
            OptionSpec.select(
                "login", resources.getString(R.string.opt_login),
                listOf("none", "cookies", "credentials"), "none",
                resources.getString(R.string.opt_login_desc),
            ),
            OptionSpec.select(
                "playlist", resources.getString(R.string.opt_playlist),
                listOf("all", "first"), "all",
                resources.getString(R.string.opt_playlist_desc),
            ),
            OptionSpec.text(
                "cookies_file", resources.getString(R.string.opt_cookies_path), "",
                resources.getString(R.string.opt_cookies_path_desc),
            ),
            OptionSpec.text(
                "username", resources.getString(R.string.opt_username), "",
                resources.getString(R.string.opt_username_desc),
            ),
            OptionSpec.text(
                "password", resources.getString(R.string.opt_password), "",
                resources.getString(R.string.opt_password_desc), password = true,
            ),
            OptionSpec.text(
                "twofactor", resources.getString(R.string.opt_2fa), "",
                resources.getString(R.string.opt_2fa_desc),
            ),
        )

        IWARA_ID -> listOf(
            OptionSpec.select(
                "quality", resources.getString(R.string.opt_quality),
                listOf("Source", "1080", "720", "540", "360", "preview"), "Source",
                resources.getString(R.string.opt_iwara_quality_desc),
            ),
        )

        MOMONGA_ID -> listOf(
            OptionSpec.select(
                "workers", resources.getString(R.string.opt_workers), listOf("5", "10", "20"), "10",
                resources.getString(R.string.opt_workers_desc),
            ),
        )

        MANGA_ID -> listOf(
            OptionSpec.select(
                "workers", resources.getString(R.string.opt_workers), listOf("4", "8", "12"), "8",
                resources.getString(R.string.opt_workers_desc),
            ),
        )

        else -> emptyList()
    }
}
