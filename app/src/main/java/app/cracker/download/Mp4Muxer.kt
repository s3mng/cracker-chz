package app.cracker.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.media.MediaFormat
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
                separateAudioTracks = mapOf(track to writer.addTrack(mp4AudioFormat(extractor.getTrackFormat(track))))
            } else {
                // Some DASH representations contain both video and audio.
                for (track in 0 until videoExtractor.trackCount) {
                    val format = videoExtractor.getTrackFormat(track)
                    if (format.getString("mime").orEmpty().startsWith("audio/")) {
                        videoTracks[track] = writer.addTrack(mp4AudioFormat(format))
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

    private fun isAdts(format: MediaFormat): Boolean =
        format.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" &&
            format.containsKey(MediaFormat.KEY_IS_ADTS) && format.getInteger(MediaFormat.KEY_IS_ADTS) == 1

    private fun mp4AudioFormat(format: MediaFormat): MediaFormat {
        if (isAdts(format)) {
            check(format.getByteBuffer("csd-0")?.hasRemaining() == true) { "AAC 설정 정보가 없어요" }
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
        }
        return format
    }

    private fun copyTracks(
        extractor: MediaExtractor,
        tracks: Map<Int, Int>,
        muxer: MediaMuxer,
        checkActive: () -> Unit,
    ) {
        tracks.keys.forEach(extractor::selectTrack)
        val adtsRates = tracks.keys.mapNotNull { track ->
            val format = extractor.getTrackFormat(track)
            if (isAdts(format)) track to format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else null
        }.toMap()
        val sampleCounts = tracks.keys.associateWith { 0L }.toMutableMap()
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
            val adtsRate = adtsRates[track]
            if (adtsRate != null) {
                val frames = AdtsFrames.parse(buffer, size, info.presentationTimeUs, adtsRate)
                for (frame in frames) {
                    checkActive()
                    // MediaMuxer can change the buffer position/limit between writes.
                    buffer.clear()
                    info.set(frame.offset, frame.size, frame.timeUs, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                    muxer.writeSampleData(tracks.getValue(track), buffer, info)
                }
                sampleCounts[track] = sampleCounts.getValue(track) + frames.size
            } else {
                muxer.writeSampleData(tracks.getValue(track), buffer, info)
                sampleCounts[track] = sampleCounts.getValue(track) + 1
            }
            extractor.advance()
        }
        tracks.keys.forEach(extractor::unselectTrack)
        check(sampleCounts.values.all { it > 0 }) { "영상 또는 음성 트랙에 실제 데이터가 없어요" }
    }
}
