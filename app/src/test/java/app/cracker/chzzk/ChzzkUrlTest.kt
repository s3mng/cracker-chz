package app.cracker.chzzk

import app.cracker.model.JobKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChzzkUrlTest {
    @Test
    fun parsesLiveAndVideo() {
        val live = ChzzkUrl.parse("https://chzzk.naver.com/live/c68b8ef525fb3d2fa146344d84991753")
        val video = ChzzkUrl.parse("https://chzzk.naver.com/video/1754?foo=1")
        val clip = ChzzkUrl.parse("https://chzzk.naver.com/clips/LeqAmSuEQy")
        val embed = ChzzkUrl.parse("https://chzzk.naver.com/embed/clip/R9BilMGLOS?foo=1")
        assertEquals(JobKind.Live, live?.kind)
        assertEquals("c68b8ef525fb3d2fa146344d84991753", live?.id)
        assertEquals(JobKind.Vod, video?.kind)
        assertEquals("1754", video?.id)
        assertEquals(JobKind.Clip, clip?.kind)
        assertEquals("LeqAmSuEQy", clip?.id)
        assertEquals(JobKind.Clip, embed?.kind)
        assertEquals("R9BilMGLOS", embed?.id)
        assertNull(ChzzkUrl.parse("https://youtube.com/watch?v=1"))
    }

    @Test
    fun parsesMasterPlaylist() {
        val body = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=1280x720
            720.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1920x1080
            1080.m3u8
        """.trimIndent()
        val variants = HlsParser.parseMaster(body, "https://cdn.example/live/master.m3u8")
        assertEquals(2, variants.size)
        assertEquals(1080, variants.first().height)
        assertTrue(variants.first().uri.endsWith("1080.m3u8"))
    }

    @Test
    fun parsesMediaPlaylist() {
        val body = """
            #EXTM3U
            #EXT-X-TARGETDURATION:2
            #EXT-X-MEDIA-SEQUENCE:10
            #EXTINF:2.0,
            seg10.ts
            #EXTINF:2.0,
            seg11.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val media = HlsParser.parseMedia(body, "https://cdn.example/live/720.m3u8")
        assertEquals(10L, media.mediaSequence)
        assertEquals(2, media.segments.size)
        assertTrue(media.ended)
        assertTrue(media.segments[0].uri.endsWith("seg10.ts"))
    }

    @Test
    fun formatsSpeed() {
        assertEquals("512 B/s", formatSpeed(512))
        assertEquals("2 KB/s", formatSpeed(2048))
        assertEquals("1.5 MB/s", formatSpeed((1.5 * 1024 * 1024).toLong()))
        assertEquals(null, formatSpeed(0))
    }

    @Test
    fun sanitizesXmlWithUnescapedAmpersands() {
        val input = """
            <MPD>
                <BaseURL>https://example.com/vod?token=xyz&expire=123&sign=abc</BaseURL>
                <Title>Q&A with Devs & Gamers</Title>
                <AlreadyEscaped>&amp; &lt; &gt; &quot; &apos; &#123; &#x2F;</AlreadyEscaped>
            </MPD>
        """.trimIndent()

        val expected = """
            <MPD>
                <BaseURL>https://example.com/vod?token=xyz&amp;expire=123&amp;sign=abc</BaseURL>
                <Title>Q&amp;A with Devs &amp; Gamers</Title>
                <AlreadyEscaped>&amp; &lt; &gt; &quot; &apos; &#123; &#x2F;</AlreadyEscaped>
            </MPD>
        """.trimIndent()

        assertEquals(expected, DashParser.sanitizeXml(input))
    }
}
