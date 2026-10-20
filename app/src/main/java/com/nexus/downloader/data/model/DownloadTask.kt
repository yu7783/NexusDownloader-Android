package com.nexus.downloader.data.model

import java.util.UUID

/** ダウンロード 1 件の状態。 */
enum class TaskStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    SKIPPED,
    CANCELED,
}

/** 進行中/完了したダウンロード 1 件分の情報。 */
data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val addonId: String? = null,
    val addonName: String? = null,
    val status: TaskStatus = TaskStatus.QUEUED,
    /** 0f..1f。 */
    val progress: Float = 0f,
    val message: String = "",
    val outputPath: String? = null,
    val error: String? = null,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    /**
     * 同じキュー（連続して投入されたまとまり）を識別する番号。
     *
     * 画面や通知の「全体の進捗」は、この番号が一致するタスクだけで計算する
     * （過去の完了分が混ざって 100% に見えるのを防ぐ）。
     */
    val batch: Long = 0L,
) {
    val isTerminal: Boolean
        get() = status == TaskStatus.COMPLETED || status == TaskStatus.FAILED ||
            status == TaskStatus.SKIPPED || status == TaskStatus.CANCELED

    /** これから実行される / 実行中のタスクか。 */
    val isActive: Boolean
        get() = status == TaskStatus.QUEUED || status == TaskStatus.RUNNING
}

/** キュー全体の進捗（件数ベース）。 */
data class BatchProgress(
    /** 完了した件数。 */
    val done: Int,
    /** キュー全体の件数。 */
    val total: Int,
    /** 0f..1f。実行中の進捗を合算した全体進捗。 */
    val fraction: Float,
) {
    /** 表示用（0..100）。 */
    val percent: Int get() = (fraction * 100f).toInt().coerceIn(0, 100)
}
