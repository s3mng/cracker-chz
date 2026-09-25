package app.cracker.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

object Mp4Muxer {
    fun mux(video: File, audio: File?, output: File, checkActive: () -> Unit = {}) {
        var muxer: MediaMuxer? = null
        val videoExtractor = MediaExtractor()
        var audioExtractor: MediaExtractor? = null
        var completed = false
        try {
            checkActive()
            videoExtractor.setDataSource(video.absolutePath)
            val writer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            val videoTrack = selectTrack(videoExtractor, "video/")
            val videoTracks = linkedMapOf(videoTrack to writer.addTrack(videoExtractor.getTrackFormat(videoTrack)))
            var separateAudioTracks: Map<Int, Int> = emptyMap()
            if (audio != null) {
                check(audio.length() > 0) { "음성 파일이 비어 있어요" }
                val extractor = MediaExtractor()
                audioExtractor = extractor
                extractor.setDataSource(audio.absolutePath)
                val track = selectTrack(extractor, "audio/")
                separateAudioTracks = mapOf(track to writer.addTrack(extractor.getTrackFormat(track)))
            } else {
                // Some DASH representations contain both video and audio.
                for (track in 0 until videoExtractor.trackCount) {
                    val format = videoExtractor.getTrackFormat(track)
                    if (format.getString("mime").orEmpty().startsWith("audio/")) {
                        videoTracks[track] = writer.addTrack(format)
                    }
                }
            }
            writer.start()
            copyTracks(videoExtractor, videoTracks, writer, checkActive)
            audioExtractor?.let { copyTracks(it, separateAudioTracks, writer, checkActive) }
            checkActive()
            writer.stop()
            completed = true
        } finally {
            runCatching { muxer?.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor?.release() }
            if (!completed) output.delete()
        }
    }

    private fun selectTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString("mime").orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        error("트랙을 찾지 못했어요")
    }

    private fun copyTracks(
        extractor: MediaExtractor,
        tracks: Map<Int, Int>,
        muxer: MediaMuxer,
        checkActive: () -> Unit,
    ) {
        tracks.keys.forEach(extractor::selectTrack)
        var buffer = ByteBuffer.allocate(1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            checkActive()
            val track = extractor.sampleTrackIndex
            if (track < 0) break
            val sampleSize = extractor.sampleSize
            check(sampleSize <= Int.MAX_VALUE) { "영상 프레임이 너무 커요" }
            if (sampleSize > buffer.capacity()) buffer = ByteBuffer.allocate(sampleSize.toInt())
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0)
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(tracks.getValue(track), buffer, info)
            extractor.advance()
        }
        tracks.keys.forEach(extractor::unselectTrack)
    }
}
