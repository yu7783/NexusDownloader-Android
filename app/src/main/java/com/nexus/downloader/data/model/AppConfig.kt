package com.nexus.downloader.data.model

import kotlinx.serialization.Serializable

/**
 * アプリ全体の設定。
 *
 * Python 版 config.json のうち、デスクトップ前提だった項目
 * （本体自動更新 / 更新リポジトリ / プロキシ / User-Agent / チャンクサイズ）は
 * スマホでは不要なため削除し、モバイル向けの項目だけを保持する。
 */
@Serializable
data class AppConfig(
    /** 保存先フォルダ名（アプリ専用外部ストレージ配下）。空なら外部ストレージ直下。 */
    val downloadDir: String = "NexusDownloader",
    /** アプリ専用領域の種類（外部 / 内部）。 */
    val storageTarget: StorageTarget = StorageTarget.EXTERNAL,
    /**
     * ユーザーが選んだ保存先フォルダ（SAF のツリー URI 文字列）。
     *
     * null のときはアプリ専用フォルダ（[downloadDir]）に保存し、設定されているときは
     * ダウンロード完了後にそのフォルダへ移動する。
     */
    val saveTreeUri: String? = null,
    /** テーマモード。 */
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** OS のダイナミックカラーを使用するか。 */
    val dynamicColor: Boolean = true,
    /** ダイナミックカラー無効時のアクセントカラー（#RRGGBB）。 */
    val accentColor: String = "#0078D4",
    /** 同時ダウンロード数（1..16）。0 で自動（=CPU コア数ベース）。 */
    val parallel: Int = 8,
    /** ネットワークタイムアウト（秒）。 */
    val timeout: Int = 30,
    /** ブロックする危険キーワード。 */
    val blockedKeywords: List<String> = DEFAULT_BLOCKED_KEYWORDS,

    // ---- モバイル向けの便利設定（追加機能） ----
    /** Wi-Fi 接続時のみダウンロードを実行する。 */
    val wifiOnly: Boolean = false,
    /** 共有（Morphe YT 等）から受け取った URL を自動でダウンロード開始する。 */
    val autoDownloadOnShare: Boolean = true,
    /** 共有ポップアップを開いてから自動で開始するまでの秒数（1〜600）。 */
    val shareAutoStartSeconds: Int = 15,
    /** ダウンロード完了時に通知を出す。 */
    val notifyOnComplete: Boolean = true,
    /** 起動時/復帰時にクリップボードの URL を提案する。 */
    val clipNotify: Boolean = true,
    /** 完了したダウンロードの累計件数（統計）。 */
    val statCompleted: Long = 0L,
    /** 累計のダウンロード量（バイト・統計）。 */
    val statTotalBytes: Long = 0L,
    /** 失敗したダウンロードの累計回数（統計）。 */
    val statFailed: Long = 0L,
    /** 他アプリの共有から URL を取り込んだ累計回数（統計）。 */
    val statShared: Long = 0L,
    /** 獲得済み実績バッジの ID 一覧（[Badge] を参照）。 */
    val earnedBadges: List<String> = emptyList(),
    /** 初回セットアップ（オンボーディング）を完了したか。false のとき起動時に表示する。 */
    val setupCompleted: Boolean = false,
) {
    companion object {
        val DEFAULT_BLOCKED_KEYWORDS = listOf(
            "os.remove",
            "shutil.rmtree",
            "subprocess",
            "os.system",
            "Runtime.getRuntime",
            "ProcessBuilder",
        )
    }
}

/** テーマモード。 */
@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** アプリ専用領域の種類（保存先）。 */
@Serializable
enum class StorageTarget {
    /** 外部ストレージのアプリ専用領域（ファイルアプリから見える）。 */
    EXTERNAL,

    /** 内部ストレージのアプリ専用領域（他アプリからは見えない）。 */
    INTERNAL,
}
