package app.cracker.download

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class AdtsFramesTest {
    private fun frame(crc: Boolean = false): ByteArray {
        val header = if (crc) 9 else 7
        val size = header + 4
        return ByteArray(size).apply {
            this[0] = 0xff.toByte()
            this[1] = (if (crc) 0xf0 else 0xf1).toByte()
            this[2] = 0x4c // AAC LC, 48 kHz
            this[3] = 0x80.toByte() // stereo
            this[4] = (size shr 3).toByte()
            this[5] = (((size and 7) shl 5) or 0x1f).toByte()
            this[6] = 0xfc.toByte()
            for (i in header until size) this[i] = 0x21
        }
    }

    @Test fun removesHeaderAndSplitsGroupedFramesWithTimestamps() {
        val bytes = frame() + frame() + frame()
        val frames = AdtsFrames.parse(ByteBuffer.wrap(bytes), bytes.size, 100_000, 48_000)
        assertEquals(listOf(7, 18, 29), frames.map { it.offset })
        assertEquals(listOf(4, 4, 4), frames.map { it.size })
        assertEquals(listOf(100_000L, 121_333L, 142_666L), frames.map { it.timeUs })
    }

    @Test fun removesCrcHeader() {
        val bytes = frame(crc = true)
        assertEquals(AdtsFrames.Frame(9, 4, 0),
            AdtsFrames.parse(ByteBuffer.wrap(bytes), bytes.size, 0, 48_000).single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedFrame() {
        val bytes = frame().dropLast(1).toByteArray()
        AdtsFrames.parse(ByteBuffer.wrap(bytes), bytes.size, 0, 48_000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsChangedSampleRate() {
        val bytes = frame()
        AdtsFrames.parse(ByteBuffer.wrap(bytes), bytes.size, 0, 44_100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMultipleRawBlocks() {
        val bytes = frame().apply { this[6] = 0xfd.toByte() }
        AdtsFrames.parse(ByteBuffer.wrap(bytes), bytes.size, 0, 48_000)
    }
}
