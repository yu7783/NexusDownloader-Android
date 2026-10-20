package com.nexus.downloader.data.model

import androidx.annotation.StringRes
import com.nexus.downloader.R

private const val MB = 1024L * 1024L
private const val GB = 1024L * MB

/** バッジの達成度を測る指標。 */
enum class BadgeMetric {
    /** 完了したダウンロードの累計件数（[AppConfig.statCompleted]）。 */
    COMPLETED,

    /** 累計のダウンロード容量（[AppConfig.statTotalBytes]）。 */
    BYTES,

    /** 共有から取り込んだ累計回数（[AppConfig.statShared]）。 */
    SHARED,
}

/**
 * 統計と連動して解除される実績バッジ。
 *
 * 達成判定は [AppConfig] の累計値だけから計算するため、[AppConfig.earnedBadges] が
 * 万一失われても統計から再判定して復元できる。
 */
enum class Badge(
    val id: String,
    /** 一覧表示に使う絵文字アイコン。 */
    val emoji: String,
    /** 表示名（文字列リソース）。 */
    @StringRes val titleRes: Int,
    /** 説明（文字列リソース）。 */
    @StringRes val descRes: Int,
    /** 解除に必要な値。 */
    val required: Long,
    val metric: BadgeMetric,
) {
    FIRST_DOWNLOAD(
        id = "first_download",
        emoji = "🌱",
        titleRes = R.string.badge_first_title,
        descRes = R.string.badge_first_desc,
        required = 1L,
        metric = BadgeMetric.COMPLETED,
    ),
    TEN_DOWNLOADS(
        id = "ten_downloads",
        emoji = "🥉",
        titleRes = R.string.badge_regular_title,
        descRes = R.string.badge_regular_desc,
        required = 10L,
        metric = BadgeMetric.COMPLETED,
    ),
    FIFTY_DOWNLOADS(
        id = "fifty_downloads",
        emoji = "🥈",
        titleRes = R.string.badge_heavy_title,
        descRes = R.string.badge_heavy_desc,
        required = 50L,
        metric = BadgeMetric.COMPLETED,
    ),
    HUNDRED_DOWNLOADS(
        id = "hundred_downloads",
        emoji = "🥇",
        titleRes = R.string.badge_master_title,
        descRes = R.string.badge_master_desc,
        required = 100L,
        metric = BadgeMetric.COMPLETED,
    ),
    FIRST_MEGABYTE(
        id = "first_megabyte",
        emoji = "📦",
        titleRes = R.string.badge_1mb_title,
        descRes = R.string.badge_1mb_desc,
        required = MB,
        metric = BadgeMetric.BYTES,
    ),
    ONE_GIGABYTE(
        id = "one_gigabyte",
        emoji = "💾",
        titleRes = R.string.badge_1gb_title,
        descRes = R.string.badge_1gb_desc,
        required = GB,
        metric = BadgeMetric.BYTES,
    ),
    TEN_GIGABYTE(
        id = "ten_gigabyte",
        emoji = "🗄️",
        titleRes = R.string.badge_10gb_title,
        descRes = R.string.badge_10gb_desc,
        required = 10L * GB,
        metric = BadgeMetric.BYTES,
    ),
    HUNDRED_GIGABYTE(
        id = "hundred_gigabyte",
        emoji = "🏛️",
        titleRes = R.string.badge_100gb_title,
        descRes = R.string.badge_100gb_desc,
        required = 100L * GB,
        metric = BadgeMetric.BYTES,
    ),
    FIRST_SHARE(
        id = "first_share",
        emoji = "📤",
        titleRes = R.string.badge_share_title,
        descRes = R.string.badge_share_desc,
        required = 1L,
        metric = BadgeMetric.SHARED,
    ),
    SHARE_MASTER(
        id = "share_master",
        emoji = "🚀",
        titleRes = R.string.badge_share_master_title,
        descRes = R.string.badge_share_master_desc,
        required = 25L,
        metric = BadgeMetric.SHARED,
    ),
}

/** バッジの達成判定と進捗計算。UI と通知の両方から利用する。 */
object BadgeCatalog {

    /** 定義順の全バッジ。 */
    val all: List<Badge> = Badge.entries

    /** 指標に対応する現在値。 */
    fun value(config: AppConfig, metric: BadgeMetric): Long = when (metric) {
        BadgeMetric.COMPLETED -> config.statCompleted
        BadgeMetric.BYTES -> config.statTotalBytes
        BadgeMetric.SHARED -> config.statShared
    }

    /** 統計上バッジの条件を満たしているか。 */
    fun isEarned(config: AppConfig, badge: Badge): Boolean =
        value(config, badge.metric) >= badge.required

    /** 統計上達成しているバッジ ID の集合。 */
    fun earnedIds(config: AppConfig): Set<String> =
        all.filter { isEarned(config, it) }.map { it.id }.toSet()

    /**
     * まだ [AppConfig.earnedBadges] に記録されていない新規獲得バッジ。
     * 通知と保存の両方に使う。
     */
    fun newlyEarned(config: AppConfig): List<Badge> {
        val known = config.earnedBadges.toSet()
        return all.filter { it.id !in known && isEarned(config, it) }
    }

    /** 達成率（0f..1f）。UI の進捗表示に使う。 */
    fun ratio(config: AppConfig, badge: Badge): Float =
        (value(config, badge.metric).toDouble() / badge.required.toDouble())
            .coerceIn(0.0, 1.0)
            .toFloat()
}
