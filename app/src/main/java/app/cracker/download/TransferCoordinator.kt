package app.cracker.download

import android.content.Context
import android.content.Intent
import app.cracker.chzzk.formatClock
import app.cracker.chzzk.formatSpeed
import app.cracker.model.DownloadJob
import app.cracker.model.JobKind
import app.cracker.model.isLive
import app.cracker.model.JobStatus
import app.cracker.model.QualityOption
import app.cracker.model.StreamProtocol
import app.cracker.model.VideoMeta
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

data class TransferRequest(
    val job: DownloadJob,
    val quality: QualityOption,
    val title: String,
)

private class SaveFailure(cause: Exception) : Exception("파일 저장에 실패했어요. 저장 공간과 폴더 권한을 확인해 주세요", cause)

class TransferCoordinator(
    private val context: Context,
    private val http: OkHttpClient,
    private val locationStore: DownloadLocationStore,
    private val settings: TransferSettings,
) {
    private val transfer = MediaTransfer(http)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pause = ConcurrentHashMap<String, AtomicBoolean>()
    private val cancel = ConcurrentHashMap<String, AtomicBoolean>()
    private val discarded = CopyOnWriteArraySet<String>()
    private val queue = ConcurrentHashMap<String, TransferRequest>()
    private val history = JobHistoryStore(context)
    private val notifier = TransferNotifier(context)
    private val _jobs = MutableStateFlow(restore(history.load()))
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()
    private var loop: Job? = null
    private var timedOut = false

    fun stopForTimeout() {
        timedOut = true
        _jobs.value.filter { it.status in listOf(JobStatus.Queued, JobStatus.Running, JobStatus.Paused) }
            .forEach { job ->
                cancel[job.id]?.set(true)
                pause[job.id]?.set(false)
                upsert(job.copy(status = JobStatus.Failed, speedLabel = null,
                    error = "백그라운드 실행 시간이 초과되어 중단됐어요"))
            }
        loop?.cancel()
        http.dispatcher.cancelAll()
        queue.clear()
        StagingFile.sweep(context)
    }

    init {
        history.save(_jobs.value)
        StagingFile.sweep(context)
    }

    fun enqueue(meta: VideoMeta, quality: QualityOption): String {
        val id = UUID.randomUUID().toString()
        val job = DownloadJob(
            id = id,
            kind = meta.kind,
            title = meta.title,
            channel = meta.channel,
            quality = quality.label,
            status = JobStatus.Queued,
            isAdult = meta.isAdult,
        )
        queue[id] = TransferRequest(job, quality, meta.title)
        pause[id] = AtomicBoolean(false)
        cancel[id] = AtomicBoolean(false)
        upsert(job)
        startService()
        return id
    }

    fun cancel(id: String) {
        cancel[id]?.set(true)
        pause[id]?.set(false)
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        val status = if (job.kind == JobKind.Live) JobStatus.Stopped else JobStatus.Cancelled
        upsert(job.copy(status = status, error = null))
    }

    fun remove(id: String) {
        discarded.add(id)
        cancel[id]?.set(true)
        pause[id]?.set(false)
        queue.remove(id)
        StagingFile.deleteFor(context, id)
        _jobs.update { jobs -> jobs.filterNot { it.id == id } }
        persist()
    }

    fun clearAll() {
        _jobs.value.forEach { job ->
            discarded.add(job.id)
            cancel[job.id]?.set(true)
            pause[job.id]?.set(false)
        }
        queue.clear()
        StagingFile.sweep(context)
        _jobs.value = emptyList()
        persist()
    }

    fun togglePause(id: String) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.processingLabel != null) return
        if (job.kind.isLive) return
        when (job.status) {
            JobStatus.Running -> {
                pause[id]?.set(true)
                upsert(job.copy(status = JobStatus.Paused, speedLabel = null))
            }
            JobStatus.Paused -> {
                pause[id]?.set(false)
                upsert(job.copy(status = JobStatus.Running))
                startService()
            }
            else -> Unit
        }
    }

    fun startLoop() {
        if (loop?.isActive == true) return
        timedOut = false
        loop = scope.launch {
            mutex.withLock {
                while (true) {
                    val next = _jobs.value.firstOrNull {
                        it.status == JobStatus.Queued || it.status == JobStatus.Running || it.status == JobStatus.Paused
                    } ?: break
                    if (cancel[next.id]?.get() == true) {
                        StagingFile.deleteFor(context, next.id)
                        upsert(
                            next.copy(
                                status = if (next.kind == JobKind.Live) JobStatus.Stopped else JobStatus.Cancelled,
                                error = null,
                            ),
                        )
                        continue
                    }
                    runCatching { process(next.id) }
                        .onFailure { error ->
                            if (error is CancellationException) throw error
                            val current = _jobs.value.firstOrNull { it.id == next.id } ?: next
                            if (cancel[next.id]?.get() == true && error !is SaveFailure) {
                                upsert(
                                    current.copy(
                                        status = if (current.kind == JobKind.Live) JobStatus.Stopped else JobStatus.Cancelled,
                                        error = null,
                                    ),
                                )
                            } else {
                                upsert(
                                    current.copy(
                                        status = JobStatus.Failed,
                                        error = error.message ?: "실패",
                                        speedLabel = null,
                                    ),
                                )
                            }
                        }
                }
            }
            loop = null
            StagingFile.sweep(context)
            context.stopService(Intent(context, TransferService::class.java))
        }
    }

    private fun cancelled(id: String): Boolean = cancel[id]?.get() == true

    private suspend fun process(id: String) {
        val request = queue[id] ?: return
        if (cancelled(id)) {
            StagingFile.deleteFor(context, id)
            return
        }
        val live = request.job.kind.isLive
        val extension = when {
            live -> "ts"
            request.quality.protocol == StreamProtocol.Hls -> "ts"
            else -> "mp4"
        }
        val maxAttempts = if (live) 1 else settings.vodRetries.value.coerceIn(0, TransferSettings.MAX_RETRIES) + 1
        upsert(
            currentJob(id, request.job).copy(
                status = JobStatus.Running,
                progress = 0f,
                attempt = 1,
                maxAttempts = maxAttempts,
                processingLabel = null,
                error = null,
            ),
        )
        var lastError: Exception? = null
        try {
            for (attempt in 1..maxAttempts) {
                if (cancelled(id)) {
                    StagingFile.deleteFor(context, id)
                    markCancelled(id, request.job, live)
                    return
                }
                if (attempt > 1) {
                    upsert(
                        currentJob(id, request.job).copy(
                            status = JobStatus.Running,
                            progress = 0f,
                            attempt = attempt,
                            processingLabel = null,
                            speedLabel = null,
                            maxAttempts = maxAttempts,
                            error = null,
                        ),
                    )
                    delay((1_000L * attempt).coerceAtMost(5_000L))
                    if (cancelled(id)) {
                        StagingFile.deleteFor(context, id)
                        markCancelled(id, request.job, live)
                        return
                    }
                }
                val staging = StagingFile(context, id, extension)
                val startedAt = System.currentTimeMillis()
                val sampler = SpeedSampler()
                val transferred = AtomicLong(0)
                val ticker = if (live) {
                    scope.launch {
                        while (!cancelled(id) && _jobs.value.any { it.id == id && it.status == JobStatus.Running }) {
                            upsertElapsed(id, startedAt, sampler.sample(transferred.get()))
                            delay(1000)
                        }
                    }
                } else {
                    null
                }
                var publishing = false
                try {
                    if (live) {
                        transfer.recordLive(
                            mediaPlaylistUrl = request.quality.mediaUrl,
                            output = staging.outputStream(),
                            onBytes = { transferred.set(it) },
                            isCancelled = { cancelled(id) },
                        )
                    } else {
                        transfer.downloadVod(
                            quality = request.quality,
                            output = staging.file,
                            onProgress = { value, bytes ->
                                val current = _jobs.value.firstOrNull { it.id == id } ?: return@downloadVod
                                val paused = pause[id]?.get() == true
                                upsert(
                                    current.copy(
                                        progress = value,
                                        status = if (paused) JobStatus.Paused else JobStatus.Running,
                                        speedLabel = if (paused || current.processingLabel != null) null else formatSpeed(sampler.sample(bytes)),
                                    ),
                                )
                            },
                            isPaused = { pause[id]?.get() == true },
                            isCancelled = { cancelled(id) },
                            onProcessing = { markProcessing(id, "MP4 정리 중") },
                        )
                    }
                    ticker?.cancel()
                    coroutineContext.ensureActive()
                    val transferContext = coroutineContext
                    val checkSavingActive = {
                        transferContext.ensureActive()
                        if (!live && cancelled(id)) throw CancellationException("다운로드 취소")
                    }
                    val current = currentJob(id, request.job)
                    if (cancelled(id)) {
                        if (live && staging.file.exists() && staging.file.length() > 0) {
                            publishing = true
                            markProcessing(id, "저장 중")
                            check(staging.publish(request.title, mime(extension), locationStore.uri.value, checkSavingActive)) {
                                "파일 저장에 실패했어요"
                            }
                            val stopped = current.copy(
                                status = JobStatus.Stopped,
                                elapsedLabel = formatClock(System.currentTimeMillis() - startedAt),
                                speedLabel = null,
                            )
                            upsert(stopped)
                            notifier.notifyFinished(stopped)
                        } else {
                            staging.delete()
                            markCancelled(id, request.job, live)
                        }
                        return
                    }
                    publishing = true
                    markProcessing(id, "저장 중")
                    check(staging.publish(request.title, mime(extension), locationStore.uri.value, checkSavingActive)) {
                        "파일 저장에 실패했어요"
                    }
                    val finished = currentJob(id, request.job).copy(
                        status = JobStatus.Completed,
                        progress = 1f,
                        elapsedLabel = if (live) formatClock(System.currentTimeMillis() - startedAt) else null,
                        speedLabel = null,
                    )
                    upsert(finished)
                    notifier.notifyFinished(finished)
                    return
                } catch (error: Exception) {
                    staging.delete()
                    if (error is CancellationException) {
                        coroutineContext.ensureActive()
                        markCancelled(id, request.job, live)
                        return
                    }
                    if (publishing) throw SaveFailure(error)
                    if (cancelled(id)) {
                        markCancelled(id, request.job, live)
                        return
                    }
                    lastError = error
                    if (attempt == maxAttempts) throw error
                } finally {
                    ticker?.cancel()
                    staging.delete()
                }
            }
            throw lastError ?: IllegalStateException("실패")
        } finally {
            queue.remove(id)
            discarded.remove(id)
        }
    }

    private fun currentJob(id: String, fallback: DownloadJob): DownloadJob =
        _jobs.value.firstOrNull { it.id == id } ?: fallback

    private fun markProcessing(id: String, label: String) {
        val current = _jobs.value.firstOrNull { it.id == id } ?: return
        if (cancelled(id)) return
        pause[id]?.set(false)
        upsert(current.copy(processingLabel = label, speedLabel = null, status = JobStatus.Running))
    }

    private fun markCancelled(id: String, fallback: DownloadJob, live: Boolean) {
        upsert(
            currentJob(id, fallback).copy(
                status = if (live) JobStatus.Stopped else JobStatus.Cancelled,
                error = null,
                speedLabel = null,
            ),
        )
    }

    private fun mime(extension: String): String = when (extension) {
        "mp4" -> "video/mp4"
        else -> "video/mp2t"
    }

    private fun upsertElapsed(id: String, startedAt: Long, bytesPerSec: Long) {
        val current = _jobs.value.firstOrNull { it.id == id } ?: return
        if (current.status != JobStatus.Running) return
        upsert(
            current.copy(
                elapsedLabel = formatClock(System.currentTimeMillis() - startedAt),
                speedLabel = formatSpeed(bytesPerSec),
            ),
        )
    }

    private fun restore(jobs: List<DownloadJob>): List<DownloadJob> =
        jobs.map { job ->
            when (job.status) {
                JobStatus.Queued, JobStatus.Running, JobStatus.Paused ->
                    job.copy(status = JobStatus.Failed, error = "앱이 종료되어 중단됐어요")
                else -> job
            }
        }

    private fun persist() {
        history.save(_jobs.value)
    }

    private fun upsert(job: DownloadJob) {
        if (job.id in discarded) return
        if (timedOut && job.status != JobStatus.Failed) return
        val previous = _jobs.value.firstOrNull { it.id == job.id }
        _jobs.update { list ->
            val without = list.filterNot { it.id == job.id }
            (listOf(job) + without).take(JobHistoryStore.MAX)
        }
        if (previous == null || previous.status != job.status) persist()
    }

    private fun startService() {
        context.startForegroundService(Intent(context, TransferService::class.java))
    }
}

private class SpeedSampler {
    private var lastBytes = 0L
    private var lastAt = 0L
    private var bytesPerSec = 0L

    fun sample(totalBytes: Long): Long {
        val now = System.nanoTime()
        if (lastAt == 0L) {
            lastBytes = totalBytes
            lastAt = now
            return 0L
        }
        val elapsedSec = (now - lastAt) / 1_000_000_000.0
        if (elapsedSec >= 0.4) {
            val delta = (totalBytes - lastBytes).coerceAtLeast(0L)
            val instant = (delta / elapsedSec).toLong()
            bytesPerSec = if (bytesPerSec == 0L) instant else (bytesPerSec * 3 + instant) / 4
            lastBytes = totalBytes
            lastAt = now
        }
        return bytesPerSec
    }
}
