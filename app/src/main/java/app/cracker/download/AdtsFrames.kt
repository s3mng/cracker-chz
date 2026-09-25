package app.cracker.download

import java.nio.ByteBuffer

/** Android's TS extractor may return several ADTS frames in one sample.
 * MP4 requires one raw AAC frame per sample, without the transport header.
 */
internal object AdtsFrames {
    data class Frame(val offset: Int, val size: Int, val timeUs: Long)

    private val sampleRates = intArrayOf(
        96000, 88200, 64000, 48000, 44100, 32000, 24000,
        22050, 16000, 12000, 11025, 8000, 7350,
    )

    fun parse(buffer: ByteBuffer, size: Int, timeUs: Long, sampleRate: Int): List<Frame> {
        require(size > 0 && size <= buffer.limit()) { "AAC 음성 데이터가 비어 있거나 잘렸어요" }
        require(sampleRate > 0) { "AAC 샘플 주파수가 올바르지 않아요" }
        fun byte(index: Int) = buffer.get(index).toInt() and 0xff
        val frames = mutableListOf<Frame>()
        var offset = 0
        while (offset < size) {
            require(size - offset >= 7) { "AAC 헤더가 잘렸어요" }
            require(byte(offset) == 0xff && byte(offset + 1) and 0xf6 == 0xf0) {
                "AAC ADTS 헤더가 올바르지 않아요"
            }
            val frequencyIndex = (byte(offset + 2) shr 2) and 0xf
            require(sampleRates.getOrNull(frequencyIndex) == sampleRate) {
                "AAC 샘플 주파수가 도중에 바뀌었어요"
            }
            require(byte(offset + 6) and 3 == 0) { "지원하지 않는 AAC 다중 블록이에요" }
            val headerSize = if (byte(offset + 1) and 1 != 0) 7 else 9
            val frameSize = ((byte(offset + 3) and 3) shl 11) or
                (byte(offset + 4) shl 3) or (byte(offset + 5) shr 5)
            require(frameSize > headerSize && frameSize <= size - offset) { "AAC 프레임이 잘렸어요" }
            frames += Frame(
                offset + headerSize,
                frameSize - headerSize,
                timeUs + frames.size.toLong() * 1024 * 1_000_000 / sampleRate,
            )
            offset += frameSize
        }
        return frames
    }
}
