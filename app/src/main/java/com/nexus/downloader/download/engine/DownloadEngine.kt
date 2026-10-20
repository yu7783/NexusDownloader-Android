package com.nexus.downloader.download.engine

import com.nexus.downloader.download.DownloadEnvironment
import com.nexus.downloader.download.ProgressCallback

/** 各アドオン種別が実装するダウンロード処理。 */
interface DownloadEngine {
    /**
     * @param url ダウンロード対象 URL。
     * @param options UI で選択/入力されたオプション。
     * @param env 環境（保存先・設定・HTTP クライアント）。
     * @param onProgress 進捗コールバック。
     * @return 保存したファイル/フォルダの絶対パス。
     */
    suspend fun download(
        url: String,
        options: Map<String, String>,
        env: DownloadEnvironment,
        onProgress: ProgressCallback,
    ): String

    /**
     * プレイリスト等を「1 件ずつのダウンロード対象 URL」へ展開する。
     *
     * 既定では展開せず、渡された URL をそのまま 1 件として返す。
     * yt-dlp のように複数動画をまとめて処理できるエンジンだけが実装する。
     * これにより queue 側は 1 動画 = 1 タスクとして進捗を表示できる。
     */
    suspend fun expandEntries(url: String, env: DownloadEnvironment): List<String> = listOf(url)
}
