package app.cracker.download

import app.cracker.chzzk.DashParser
import app.cracker.chzzk.HlsParser
import app.cracker.model.QualityOption
import app.cracker.model.StreamProtocol
import app.cracker.net.DASH_XML_ACCEPT
import app.cracker.net.download
import app.cracker.net.getText
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import kotlin.coroutines.coroutineContext

class MediaTransfer(
    private val http: OkHttpClient,
) {
    suspend fun downloadVod(
        quality: QualityOption,
        output: File,
        onProgress: (progress: Float, bytes: Long) -> Unit,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onProcessing: () -> Unit,
    ) {
        when (quality.protocol) {
            StreamProtocol.Hls -> downloadHls(quality.mediaUrl, output.outputStream(), onProgress, isPaused, isCancelled)
            StreamProtocol.Dash -> downloadDash(quality, output, onProgress, isPaused, isCancelled, onProcessing)
        }
    }

    suspend fun recordLive(
        mediaPlaylistUrl: String,
        output: OutputStream,
        onBytes: (Long) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        var nextSequence = -1L
        var wroteMap = false
        var bytes = 0L
        output.use { sink ->
            while (!isCancelled()) {
                coroutineContext.ensureActive()
                val playlist = HlsParser.parseMedia(http.getText(mediaPlaylistUrl), mediaPlaylistUrl)
                if (!wroteMap && playlist.mapUri != null) {
                    http.download(playlist.mapUri, CountingSink(sink) { bytes += it; onBytes(bytes) })
                    wroteMap = true
                }
                val newSegments = if (nextSequence < 0) {
                    playlist.segments.takeLast(3)
                } else {
                    val drop = (nextSequence - playlist.mediaSequence).toInt().coerceAtLeast(0)
                    playlist.segments.drop(drop)
                }
                val startSeq = if (nextSequence < 0) {
                    playlist.mediaSequence + (playlist.segments.size - newSegments.size)
                } else {
                    nextSequence
                }
                for ((index, segment) in newSegments.withIndex()) {
                    if (isCancelled()) return
                    http.download(segment.uri, CountingSink(sink) { bytes += it; onBytes(bytes) })
                    nextSequence = startSeq + index + 1
                }
                if (playlist.ended) break
                delay((playlist.targetDurationSec * 500).toLong().coerceIn(400, 4000))
            }
        }
    }

    private suspend fun downloadHls(
        mediaUrl: String,
        output: OutputStream,
        onProgress: (progress: Float, bytes: Long) -> Unit,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
    ) {
        val playlist = HlsParser.parseMedia(http.getText(mediaUrl), mediaUrl)
        val total = playlist.segments.size.coerceAtLeast(1) + if (playlist.mapUri != null) 1 else 0
        var done = 0
        var bytes = 0L
        output.use { sink ->
            val counted = CountingSink(sink) { bytes += it }
            if (playlist.mapUri != null) {
                waitIfPaused(isPaused, isCancelled)
                http.download(playlist.mapUri, counted) { copied, length ->
                    onProgress(segmentProgress(done, copied, length, total), bytes)
                }
                done++
                onProgress(done / total.toFloat(), bytes)
            }
            for (segment in playlist.segments) {
                if (isCancelled()) return
                waitIfPaused(isPaused, isCancelled)
                coroutineContext.ensureActive()
                http.download(segment.uri, counted) { copied, length ->
                    onProgress(segmentProgress(done, copied, length, total), bytes)
                }
                done++
                onProgress(done / total.toFloat(), bytes)
            }
        }
    }

    private suspend fun downloadDash(
        quality: QualityOption,
        output: File,
        onProgress: (progress: Float, bytes: Long) -> Unit,
        isPaused: () -> Boolean,
        isCancelled: () -> Boolean,
        onProcessing: () -> Unit,
    ) {
        val reps = DashParser.parse(
            http.getText(quality.mediaUrl, DASH_XML_ACCEPT),
            quality.mediaUrl,
        )
        val video = reps.first { it.id == quality.dashVideoRepId }
        val audio = reps.firstOrNull { it.id == quality.dashAudioRepId }
        check(quality.dashAudioRepId == null || audio != null) { "선택한 음성 트랙을 찾지 못했어요" }
        val videoFile = File(output.parentFile, "${output.nameWithoutExtension}.video.m4s")
        val audioFile = File(output.parentFile, "${output.nameWithoutExtension}.audio.m4s")
        val urls = video.segmentUrls + (audio?.segmentUrls ?: emptyList())
        var done = 0
        var bytes = 0L
        try {
            videoFile.outputStream().use { sink ->
                val counted = CountingSink(sink) { bytes += it }
                for (url in video.segmentUrls) {
                    if (isCancelled()) return
                    waitIfPaused(isPaused, isCancelled)
                    http.download(url, counted) { copied, length ->
                        onProgress(segmentProgress(done, copied, length, urls.size.coerceAtLeast(1)) * 0.9f, bytes)
                    }
                    done++
                    onProgress(done / urls.size.coerceAtLeast(1).toFloat() * 0.9f, bytes)
                }
            }
            if (audio != null) {
                audioFile.outputStream().use { sink ->
                    val counted = CountingSink(sink) { bytes += it }
                    for (url in audio.segmentUrls) {
                        if (isCancelled()) return
                        waitIfPaused(isPaused, isCancelled)
                        http.download(url, counted) { copied, length ->
                            onProgress(segmentProgress(done, copied, length, urls.size.coerceAtLeast(1)) * 0.9f, bytes)
                        }
                        done++
                        onProgress(done / urls.size.coerceAtLeast(1).toFloat() * 0.9f, bytes)
                    }
                }
            }
            val transferContext = coroutineContext
            waitIfPaused(isPaused, isCancelled)
            transferContext.ensureActive()
            if (isCancelled()) return
            onProcessing()
            Mp4Muxer.mux(videoFile, if (audio != null) audioFile else null, output) {
                transferContext.ensureActive()
                if (isCancelled()) throw CancellationException("다운로드 취소")
            }
            onProgress(1f, bytes)
        } finally {
            videoFile.delete()
            audioFile.delete()
        }
    }

    private fun segmentProgress(done: Int, copied: Long, length: Long, total: Int): Float {
        val fraction = if (length > 0) (copied.toFloat() / length).coerceIn(0f, 1f) else 0f
        return (done + fraction) / total.coerceAtLeast(1).toFloat()
    }

    private suspend fun waitIfPaused(isPaused: () -> Boolean, isCancelled: () -> Boolean) {
        while (isPaused() && !isCancelled()) {
            delay(250)
        }
    }
}

private class CountingSink(
    private val delegate: OutputStream,
    private val onWrite: (Long) -> Unit,
) : OutputStream() {
    override fun write(b: Int) {
        delegate.write(b)
        onWrite(1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        onWrite(len.toLong())
    }

    override fun flush() = delegate.flush()
}
