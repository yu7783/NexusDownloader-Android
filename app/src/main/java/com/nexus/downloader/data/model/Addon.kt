package com.nexus.downloader.data.model

import kotlinx.serialization.Serializable

/**
 * アドオンの種別。
 *
 * Python 版では `.py` を動的 import していたが、Android では任意コード実行が
 * できないため、あらかじめ本体に組み込んだエンジン（[YTDLP]/[IWARA]/[GALLERY]/
 * [DIRECT]）へ振り分ける「宣言的な定義」としてアドオンを表現する。
 */
@Serializable
enum class AddonType {
    /** yt-dlp エンジン（Youtube / Twitter / TikTok / ニコニコ など多数対応）。 */
    YTDLP,

    /** Iwara 公式 API を使う内蔵エンジン。 */
    IWARA,

    /** HTML を解析して画像を一括取得するギャラリーエンジン。 */
    GALLERY,

    /**
     * momon-ga / ddd-smart 系の連番画像エンジン。
     * 作品ページから同一作品の画像 URL を収集し、必要なら連番 URL を探索する。
     */
    MOMONGA,

    /** 直接リンク（直リンクのメディアファイル）をダウンロードする。 */
    DIRECT,
}

/** ギャラリー型アドオンの抽出設定。 */
@Serializable
data class GallerySpec(
    /** 画像要素（img など）の CSS セレクタ。 */
    val imageSelector: String = "img",
    /** 画像 URL を取り出す属性（src / data-src など）。 */
    val imageAttr: String = "src",
    /** タイトル取得の CSS セレクタ（フォルダ名に使用）。 */
    val titleSelector: String? = null,
    /** 次ページリンクの CSS セレクタ（空なら単一ページ）。 */
    val nextPageSelector: String? = null,
    /** ダウンロード時の Referer。 */
    val referer: String? = null,
    /** 最大ページ数（暴走防止）。 */
    val maxPages: Int = 50,
    /** 画像取得の並列数。 */
    val maxWorkers: Int = 8,
)

/**
 * アドオン定義。内蔵アドオンはコード内で生成し、
 * 外部アドオンはローカルの JSON ファイルから読み込む。
 */
@Serializable
data class AddonSpec(
    val id: String,
    val name: String = id,
    val version: String = "1.0.0",
    val urlPatterns: List<String> = emptyList(),
    val type: AddonType = AddonType.DIRECT,
    val description: String = "",
    /** [AddonType.GALLERY] のときのみ使用。 */
    val gallery: GallerySpec? = null,
    /** 自動更新元（内蔵アドオンでは未使用。将来の配布用に保持）。 */
    val repo: String? = null,
)

/** アドオンの由来。 */
enum class AddonOrigin { BUILTIN, IMPORTED }

/** 読み込み済みアドオン（定義 + 由来）。 */
data class RegisteredAddon(
    val spec: AddonSpec,
    val origin: AddonOrigin,
    /** インポートしたファイル名（内蔵は null）。 */
    val sourceFile: String? = null,
)
