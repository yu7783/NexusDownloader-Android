package com.nexus.downloader.download

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import com.nexus.downloader.R
import com.nexus.downloader.data.addon.AddonRepository
import com.nexus.downloader.data.config.ConfigRepository
import com.nexus.downloader.data.model.AddonType
import com.nexus.downloader.data.model.AppConfig
import com.nexus.downloader.data.model.Badge
import com.nexus.downloader.data.model.BatchProgress
import com.nexus.downloader.data.model.BadgeCatalog
import com.nexus.downloader.data.model.DownloadTask
import com.nexus.downloader.data.model.GallerySpec
import com.nexus.downloader.data.file.SaveLocation
import com.nexus.downloader.data.model.TaskStatus
import com.nexus.downloader.data.net.NetworkState
import com.nexus.downloader.download.engine.DirectDownloader
import com.nexus.downloader.download.engine.DownloadEngine
import com.nexus.downloader.download.engine.GalleryDownloader
import com.nexus.downloader.download.engine.IwaraDownloader
import com.nexus.downloader.download.engine.MomongaDownloader
import com.nexus.downloader.download.engine.YtDlpDownloader
import androidx.core.content.ContextCompat
import com.nexus.downloader.notification.DownloadProgressState
import com.nexus.downloader.notification.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ダウンロードのキューと実行を統括する（Python 版 main.MultiDownloadWorker 相当）。
 *
 * - 改行区切りの URL をタスク化して**キューに追加**する（実行中でも追加できる）
 * - 設定 `parallel` の並列度で、キューが空になるまで順に処理する
 * - 「一時停止 / 再開」ができ、再開時は各エンジンの続き（`.part`）から取得する
 * - 進捗・状態を [tasks] として公開し、完了時に通知と累計統計を更新
 */
class DownloadManager(
    private val context: Context,
    private val configRepo: ConfigRepository,
    private val addonRepo: AddonRepository,
    private val notificationHelper: NotificationHelper,
    private val ytDlpUpdater: YtDlpUpdater? = null,
) {
    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())

    /** 実行中 / 待機中 / 完了済みを含む全タスク（投入順）。 */
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private val _isRunning = MutableStateFlow(false)

    /** ワーカーが動いているか（一時停止中は false、待機中のタスクは残る）。 */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)

    /** 一時停止中か。 */
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _currentBatch = MutableStateFlow(0L)

    /** 表示中のバッチ番号（画面の「全体の進捗」計算に使う）。 */
    val currentBatch: StateFlow<Long> = _currentBatch.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var runJob: Job? = null

    /** ワーカーの世代。キャンセル後に古い finally が状態を上書きしないようにする。 */
    @Volatile
    private var runGeneration = 0

    /** 実行中タスクの [Job]（1 件だけの中止に使う）。 */
    private val taskJobs = ConcurrentHashMap<String, Job>()

    /** タスクごとのオプション（投入時のアドオン設定）。 */
    private val optionsByTaskId = ConcurrentHashMap<String, Map<String, String>>()

    /** バッチ番号とその管理用ロック。 */
    private val batchLock = Any()
    private var batchSeq = 0L

    /** バッチを開始した時刻（[SystemClock.elapsedRealtime]）。0 は未実行。 */
    @Volatile
    private var batchStartedAt = 0L

    /**
     * URL 群をキューに追加する。
     *
     * 実行中でも追加でき、その場合は今のバッチの後ろに積まれて順番に処理される。
     * すでに実行中 / 待機中の URL は重複追加しない。
     */
    fun enqueue(urlText: String, options: Map<String, String>): Boolean {
        val urls = parseUrls(urlText)
        if (urls.isEmpty()) return false

        val activeUrls = _tasks.value.filter { it.isActive }.map { it.url }.toSet()
        val fresh = urls.filterNot { it in activeUrls }
        if (fresh.isEmpty()) return false

        val batch = nextBatch()
        val specs = fresh.map { url ->
            val addon = addonRepo.findForUrl(url)
            DownloadTask(
                url = url,
                addonId = addon?.spec?.id,
                addonName = addon?.spec?.name,
                status = if (addon == null) TaskStatus.SKIPPED else TaskStatus.QUEUED,
                message = if (addon == null) {
                    context.getString(R.string.dl_no_addon)
                } else {
                    context.getString(R.string.dl_waiting)
                },
                batch = batch,
            )
        }
        // オプションは投入単位の先頭アドオンにのみ適用する（従来と同じ挙動）。
        val primaryAddonId = specs.firstOrNull { it.addonId != null }?.addonId
        specs.forEach { task ->
            optionsByTaskId[task.id] = if (task.addonId == primaryAddonId) options else emptyMap()
        }

        _tasks.update { current -> current + specs }
        startWorker()
        return true
    }

    /** 実行中のキューに積むときは同じバッチ、空なら新しいバッチを始める。 */
    private fun nextBatch(): Long = synchronized(batchLock) {
        if (_tasks.value.any { it.isActive }) return@synchronized _currentBatch.value
        val next = ++batchSeq
        _currentBatch.value = next
        next
    }

    // ---------------- ワーカー ----------------

    /** ワーカーが動いていなければ起動する（待機中タスクがあるときだけ）。 */
    private fun startWorker() {
        if (_isPaused.value) return
        if (_tasks.value.none { it.status == TaskStatus.QUEUED }) return
        if (runJob?.isActive == true) return

        if (batchStartedAt == 0L) batchStartedAt = SystemClock.elapsedRealtime()
        _isRunning.value = true
        // 前面サービスを立てて、元のアプリへ戻ってもダウンロードが止まらないようにする。
        startForegroundService()

        val generation = ++runGeneration
        runJob = scope.launch {
            try {
                processQueue(generation)
            } finally {
                if (generation == runGeneration) {
                    _isRunning.value = false
                    runJob = null
                }
            }
        }
    }

    /**
     * キューが空になるまで順に処理する。
     *
     * 実行中に追加されたタスクも、次のループで拾って処理する。
     * プレイリストは開始直前に 1 件 = 1 タスクへ展開する。
     */
    private suspend fun processQueue(generation: Int) {
        val config = configRepo.current()

        // Wi-Fi 限定設定のときはモバイル通信で開始しない。
        if (config.wifiOnly && !NetworkState.isUnmetered(context)) {
            updateQueued {
                it.copy(
                    status = TaskStatus.SKIPPED,
                    message = context.getString(R.string.dl_wifi_only),
                )
            }
            return
        }

        val env = DownloadEnvironment(rootDir(config), config, buildHttpClient(config))
        val concurrency = config.parallel
            .takeIf { it in 1..16 }
            ?: Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
        val semaphore = Semaphore(concurrency)

        while (true) {
            if (generation != runGeneration) return
            val ready = _tasks.value.filter { it.status == TaskStatus.QUEUED }
            if (ready.isEmpty()) return

            // プレイリストを展開（1 件 = 1 タスク）。
            val items = ready.flatMap { expandIfNeeded(it, env) }.filter { it.status == TaskStatus.QUEUED }
            if (items.isEmpty()) return

            coroutineScope {
                val handles = items.map { task ->
                    async {
                        semaphore.withPermit {
                            try {
                                runTask(task, optionsByTaskId[task.id].orEmpty(), env)
                            } catch (e: CancellationException) {
                                // 個別中止 / 一時停止 / 全体中止のいずれか。
                                // 状態は呼び出し側（cancelTask / pause / cancelAll）が整える。
                            }
                        }
                    }.also { taskJobs[task.id] = it }
                }
                try {
                    handles.awaitAll()
                } finally {
                    items.forEach { taskJobs.remove(it.id) }
                }
            }
        }
    }

    /**
     * プレイリスト URL を 1 動画 = 1 タスクへ展開する。
     *
     * yt-dlp のように一括取得できるエンジンだけが [DownloadEngine.expandEntries] を
     * 実装しており、それ以外は元のタスクをそのまま返す。
     * 設定で「最初の1件のみ」が選ばれている場合は展開しない。
     */
    private suspend fun expandIfNeeded(
        task: DownloadTask,
        env: DownloadEnvironment,
    ): List<DownloadTask> {
        val options = optionsByTaskId[task.id].orEmpty()
        if (options["playlist"] == PLAYLIST_FIRST_ONLY) return listOf(task)
        val addon = addonRepo.addons.value.firstOrNull { it.spec.id == task.addonId }
        if (addon?.spec?.type != AddonType.YTDLP) return listOf(task)

        updateTask(task.id) { it.copy(message = context.getString(R.string.dl_analyzing_playlist)) }
        val entries = runCatching { YtDlpDownloader(context, ytDlpUpdater).expandEntries(task.url, env) }
            .getOrElse {
                updateTask(task.id) {
                    t -> t.copy(message = context.getString(R.string.dl_playlist_failed))
                }
                return listOf(task)
            }
        if (entries.size <= 1) {
            updateTask(task.id) { it.copy(message = context.getString(R.string.dl_waiting)) }
            return listOf(task)
        }

        val expanded = entries.map { url ->
            task.copy(
                id = UUID.randomUUID().toString(),
                url = url,
                status = TaskStatus.QUEUED,
                progress = 0f,
                message = context.getString(R.string.dl_waiting),
                outputPath = null,
                error = null,
                bytesDownloaded = 0L,
                totalBytes = 0L,
            )
        }
        expanded.forEach { optionsByTaskId[it.id] = options }
        optionsByTaskId.remove(task.id)
        // 元のタスクを展開結果で置き換える（同じ位置に並ぶ）。
        _tasks.update { list -> list.flatMap { if (it.id == task.id) expanded else listOf(it) } }
        return expanded
    }

    private suspend fun runTask(task: DownloadTask, options: Map<String, String>, env: DownloadEnvironment) {
        val addon = addonRepo.addons.value.firstOrNull { it.spec.id == task.addonId }
        if (addon == null) {
            updateTask(task.id) {
                it.copy(
                    status = TaskStatus.SKIPPED,
                    message = context.getString(R.string.dl_addon_missing),
                )
            }
            return
        }
        updateTask(task.id) {
            it.copy(status = TaskStatus.RUNNING, message = context.getString(R.string.dl_starting))
        }

        val engine: DownloadEngine = when (addon.spec.type) {
            AddonType.YTDLP -> YtDlpDownloader(context, ytDlpUpdater)
            AddonType.IWARA -> IwaraDownloader(context)
            AddonType.GALLERY -> GalleryDownloader(context, addon.spec.gallery ?: GallerySpec())
            AddonType.MOMONGA -> MomongaDownloader(context)
            AddonType.DIRECT -> DirectDownloader(context)
        }

        try {
            val path = engine.download(task.url, options, env) { percent, message ->
                updateProgress(task.id, percent, message)
            }
            val size = sizeOf(path)
            // 保存先フォルダが選ばれていれば、完成したファイル / フォルダをそこへ移動する。
            val outputUri = moveToChosenFolder(File(path), env.config)
            updateTask(task.id) {
                it.copy(
                    status = TaskStatus.COMPLETED,
                    progress = 1f,
                    message = if (outputUri != null) "完了（保存先フォルダへ移動）" else "完了",
                    outputPath = outputUri ?: path,
                    totalBytes = size,
                    bytesDownloaded = size,
                )
            }
            onTaskCompleted(task.id, task.addonName)
        } catch (e: CancellationException) {
            // 中断は失敗として数えない（cancelAll 側で状態を更新する）。
            throw e
        } catch (e: Exception) {
            updateTask(task.id) {
                it.copy(
                    status = TaskStatus.FAILED,
                    message = "失敗",
                    error = e.message ?: e.javaClass.simpleName,
                )
            }
            recordFailure()
        }
    }

    // ---------------- 一時停止 / 再開 / 中止 ----------------

    /**
     * 実行中のダウンロードを一時停止する。
     *
     * 待機中のタスクはそのまま残り、[resume] で続きから再開する
     * （エンジンが `.part` に対応していれば途中から取得を再開する）。
     */
    fun pause() {
        if (_isPaused.value) return
        _isPaused.value = true
        stopWorker(keepQueue = true, message = "一時停止中（再開で続きから）")
    }

    /** 一時停止したキューを再開する。 */
    fun resume() {
        if (!_isPaused.value) return
        _isPaused.value = false
        if (batchStartedAt == 0L) batchStartedAt = SystemClock.elapsedRealtime()
        startWorker()
    }

    /** 実行中 + 待機中のすべてを中止する。 */
    fun cancelAll() {
        _isPaused.value = false
        stopWorker(keepQueue = false, message = "中止しました")
    }

    /** 待機中 / 実行中の 1 件だけを中止する（キュー全体は続く）。 */
    fun cancelTask(id: String) {
        val target = _tasks.value.firstOrNull { it.id == id } ?: return
        if (!target.isActive) return
        taskJobs.remove(id)?.cancel()
        optionsByTaskId.remove(id)
        updateTask(id) { it.copy(status = TaskStatus.CANCELED, message = "中止しました") }
    }

    /** ワーカーを止める（[keepQueue] なら待機中に戻して再開できるようにする）。 */
    private fun stopWorker(keepQueue: Boolean, message: String) {
        // 古い finally が isRunning を上書きしないように世代を進める。
        runGeneration++
        runJob?.cancel()
        runJob = null
        taskJobs.clear()
        _isRunning.value = false
        batchStartedAt = 0L
        _tasks.update { list ->
            list.map {
                if (!it.isActive) {
                    it
                } else if (keepQueue) {
                    it.copy(status = TaskStatus.QUEUED, message = message)
                } else {
                    it.copy(status = TaskStatus.CANCELED, message = message)
                }
            }
        }
    }

    /** 完了/失敗/中止のタスクを一覧から取り除く。 */
    fun clearFinished() {
        _tasks.update { list -> list.filterNot { it.isTerminal } }
    }

    /** タスクを 1 件取り除く（実行中なら中止する）。 */
    fun removeTask(id: String) {
        val target = _tasks.value.firstOrNull { it.id == id } ?: return
        if (target.status == TaskStatus.RUNNING) {
            taskJobs.remove(id)?.cancel()
        }
        optionsByTaskId.remove(id)
        _tasks.update { list -> list.filterNot { it.id == id } }
    }

    /**
     * 待機中のタスクをキューの先頭へ移動する。
     *
     * 実行中のタスクが終わった直後に処理されるようになる。
     */
    fun prioritize(id: String) {
        val target = _tasks.value.firstOrNull { it.id == id } ?: return
        if (target.status != TaskStatus.QUEUED) return
        _tasks.update { list ->
            val rest = list.filterNot { it.id == id }.toMutableList()
            val insertAt = (rest.indexOfLast { it.status == TaskStatus.RUNNING } + 1)
                .coerceIn(0, rest.size)
            rest.add(insertAt, target)
            rest
        }
    }

    /**
     * 失敗/スキップ/中止した 1 件だけを再試行する。
     *
     * 該当タスクを待機状態へ戻してキューに積み直す。オプションはアドオンの既定値を用いる。
     */
    fun retry(taskId: String) {
        val target = _tasks.value.firstOrNull { it.id == taskId } ?: return
        if (target.status == TaskStatus.COMPLETED || target.isActive) return

        val addon = addonRepo.findForUrl(target.url)
        val batch = nextBatch()
        updateTask(taskId) {
            it.copy(
                addonId = addon?.spec?.id ?: it.addonId,
                addonName = addon?.spec?.name ?: it.addonName,
                status = if (addon == null) TaskStatus.SKIPPED else TaskStatus.QUEUED,
                progress = 0f,
                message = if (addon == null) "対応するアドオンがありません" else "再試行します...",
                error = null,
                bytesDownloaded = 0L,
                totalBytes = 0L,
                batch = batch,
            )
        }
        if (addon == null) return

        optionsByTaskId[taskId] = addonRepo.optionsFor(addon.spec.id)
            .associate { spec -> spec.key to spec.default }
        startWorker()
    }

    /** 待機中タスクをまとめて [transform] する（Wi-Fi 限定などの一括スキップ用）。 */
    private fun updateQueued(transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list ->
            list.map { if (it.status == TaskStatus.QUEUED) transform(it) else it }
        }
    }

    /**
     * ダウンロード完了時の後処理。
     * 設定に応じて通知を出し、累計統計（件数 / 容量）を更新する。
     */
    private fun onTaskCompleted(taskId: String, addonName: String?) {
        scope.launch {
            val task = _tasks.value.firstOrNull { it.id == taskId } ?: return@launch
            val config = configRepo.current()

            if (config.notifyOnComplete) {
                val name = task.outputPath?.let { File(it).name } ?: task.url
                notificationHelper.notifyCompleted(
                    title = "ダウンロード完了",
                    detail = addonName?.let { "$it · $name" } ?: name,
                )
            }

            val size = task.totalBytes
            if (size > 0L) {
                configRepo.update {
                    it.copy(
                        statCompleted = it.statCompleted + 1,
                        statTotalBytes = it.statTotalBytes + size,
                    )
                }
            }
            awardBadges()
        }
    }

    /** 統計に応じて未獲得のバッジを付与し、解除通知を出す。 */
    fun checkBadges() {
        scope.launch { awardBadges() }
    }

    /**
     * 未獲得バッジを統計から判定して保存し、新規獲得分を通知する。
     *
     * 保存と判定を [ConfigRepository.update] の中で行うため、
     * 同時更新でも二重付与にならない。
     */
    private suspend fun awardBadges() {
        var earned: List<Badge> = emptyList()
        configRepo.update { current ->
            val fresh = BadgeCatalog.newlyEarned(current)
            earned = fresh
            if (fresh.isEmpty()) current
            else current.copy(earnedBadges = current.earnedBadges + fresh.map { it.id })
        }
        earned.forEach { badge ->
            notificationHelper.notifyBadge(
                title = "${badge.emoji} 実績解除: ${badge.title}",
                detail = badge.description,
            )
        }
    }

    /** 失敗回数の統計を加算する（実績表示用）。 */
    private fun recordFailure() {
        scope.launch {
            configRepo.update { it.copy(statFailed = it.statFailed + 1) }
        }
    }

    /**
     * 設定で保存先フォルダ（SAF）が選ばれていれば、完成したものをそこへ移動する。
     *
     * @return 移動できたら移動先 URI の文字列、未設定 / 失敗なら null。
     */
    private fun moveToChosenFolder(source: File, config: AppConfig): String? {
        val tree = SaveLocation.treeUri(config) ?: return null
        return runCatching { SaveLocation.move(context, source, tree)?.toString() }
            .onFailure { Log.w(TAG, "保存先フォルダへの移動に失敗しました", it) }
            .getOrNull()
    }

    /** ファイル / フォルダの合計サイズを求める。 */
    private fun sizeOf(path: String): Long {
        val file = File(path)
        if (!file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    private fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    // ---------------- 進行状況 ----------------

    /**
     * 表示中のバッチ（キュー）の進捗。
     *
     * 画面の「全体の進捗」と通知の進捗で同じ計算を使う。
     * 対象バッチのタスクが無ければ null。
     */
    fun batchProgress(tasks: List<DownloadTask> = _tasks.value): BatchProgress? {
        val batch = _currentBatch.value
        val target = tasks.filter { it.batch == batch }
        if (target.isEmpty()) return null

        val done = target.count { it.isTerminal }
        // 実行中 + 待機中（一時停止で中断した分の進捗を含む）を合算する。
        val partial = target.filterNot { it.isTerminal }.sumOf { it.progress.toDouble() }
        val fraction = ((done + partial) / target.size).coerceIn(0.0, 1.0).toFloat()
        return BatchProgress(done = done, total = target.size, fraction = fraction)
    }

    /**
     * 進行状況通知に表示するスナップショットを作る。
     *
     * 実行中 / 待機中のタスクが 1 件も無ければ null（= 通知を出さない）。
     */
    fun progressSnapshot(tasks: List<DownloadTask> = _tasks.value): DownloadProgressState? {
        val active = tasks.filter { it.isActive }
        if (active.isEmpty()) return null

        val running = active.filter { it.status == TaskStatus.RUNNING }
        val pending = active.count { it.status == TaskStatus.QUEUED }
        val current = running.firstOrNull() ?: active.first()
        val batch = batchProgress(tasks)
        val paused = _isPaused.value
        // バッチ進捗（一時停止中も中断した分の進捗を含む）。
        val overall = batch?.fraction ?: 0f

        return DownloadProgressState(
            percent = (overall * 100f).toInt().coerceIn(0, 100),
            currentIndex = batch?.let { (it.done + 1).coerceAtMost(it.total) } ?: 1,
            totalCount = batch?.total ?: active.size,
            pendingCount = pending,
            paused = paused,
            label = current.addonName?.takeIf { it.isNotBlank() },
            detail = current.message.takeIf { it.isNotBlank() },
            // 一時停止中は残り時間を出さない（カウントダウンが進まないため）。
            remainingMillis = if (paused) null else estimateRemaining(overall),
            indeterminate = overall <= 0.001f,
        )
    }

    /**
     * 経過時間と進捗から残り時間（ミリ秒）を推定する。
     *
     * 進捗が小さすぎる間は誤差が大きいため null を返し、通知側では
     * カウントダウンを出さない。
     */
    private fun estimateRemaining(overall: Float): Long? {
        val startedAt = batchStartedAt
        if (startedAt <= 0L || overall < MIN_ETA_PROGRESS) return null
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed < MIN_ETA_ELAPSED_MS) return null
        val remaining = (elapsed * ((1f - overall) / overall)).toLong()
        return remaining.coerceIn(MIN_ETA_MS, MAX_ETA_MS)
    }

    /**
     * 進捗を更新する。
     *
     * エンジン側の値が一時的に後退しても表示は戻さない（yt-dlp は映像→音声で
     * 0% から再カウントするため、そのまま出すと進捗が乱高下する）。
     */
    private fun updateProgress(id: String, percent: Float, message: String) {
        _tasks.update { list ->
            list.map { task ->
                if (task.id != id) {
                    task
                } else {
                    val next = percent.coerceIn(0f, 1f)
                    val stable = if (task.status == TaskStatus.RUNNING) {
                        maxOf(task.progress, next)
                    } else {
                        next
                    }
                    task.copy(progress = stable, message = message)
                }
            }
        }
    }

    /**
     * ダウンロード中だけ動く前面サービス（[DownloadService]）を開始する。
     *
     * アプリを離れるとプロセスが freezer で停止し、ダウンロードが中断してしまうため
     * 前面サービスで実行し続ける。起動できない状況（バックグラウンド起動の制限）でも
     * ダウンロード自体は続くので、失敗しても致命的ではない。
     */
    private fun startForegroundService() {
        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java),
            )
        }.onFailure { Log.w(TAG, "前面サービスの開始に失敗しました", it) }
    }

    private fun parseUrls(text: String): List<String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    private fun rootDir(config: AppConfig): File = SaveLocation.appRootDir(context, config)

    companion object {
        private const val TAG = "NexusDownload"

        /** プレイリスト設定で「1 件だけ」を選んだときの値（この場合は展開しない）。 */
        const val PLAYLIST_FIRST_ONLY = "最初の1件のみ"

        /** 残り時間を出し始める進捗（これ未満は推定誤差が大きい）。 */
        private const val MIN_ETA_PROGRESS = 0.02f

        /** 残り時間を出し始めるまでの最低経過時間。 */
        private const val MIN_ETA_ELAPSED_MS = 3_000L

        /** 残り時間の下限 / 上限。 */
        private const val MIN_ETA_MS = 1_000L
        private const val MAX_ETA_MS = 6 * 60 * 60 * 1_000L
    }
}
