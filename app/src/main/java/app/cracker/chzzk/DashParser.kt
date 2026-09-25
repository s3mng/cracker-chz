package app.cracker.chzzk

import android.util.Xml
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.xmlpull.v1.XmlPullParser

data class DashRepresentation(
    val id: String,
    val contentType: String,
    val bandwidth: Int,
    val width: Int?,
    val height: Int?,
    val segmentUrls: List<String>,
)

object DashParser {
    fun parse(xml: String, mpdUrl: String): List<DashRepresentation> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val root = mpdUrl.toHttpUrl()
        var event = parser.eventType
        var mpdDuration = 0.0
        val baseStack = ArrayDeque<HttpUrl>().apply { add(root) }
        val out = mutableListOf<DashRepresentation>()
        var periodDuration = 0.0
        var asType = ""
        var asBase: HttpUrl? = null
        var asTimescale = 1L
        var asDuration = 0L
        var asStartNumber = 1L
        var asInit: String? = null
        var asMedia: String? = null
        var asTimeline: List<TimelineS> = emptyList()
        var asSegmentList: List<String> = emptyList()

        data class OpenRep(
            val id: String,
            val bandwidth: Int,
            val width: Int?,
            val height: Int?,
            var timescale: Long? = null,
            var duration: Long? = null,
            var startNumber: Long? = null,
            var init: String? = null,
            var media: String? = null,
            var timeline: List<TimelineS>? = null,
            var segmentList: List<String>? = null,
            var base: HttpUrl = baseStack.last(),
        )

        var openRep: OpenRep? = null
        val timelineBuf = mutableListOf<TimelineS>()
        val listBuf = mutableListOf<String>()
        var inTimeline = false
        var inSegmentList = false

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "MPD" -> mpdDuration = parser.getAttr("mediaPresentationDuration")
                        ?.let { parseIsoDurationSeconds(it) } ?: 0.0
                    "BaseURL" -> {
                        val text = parser.nextText().trim()
                        if (text.isNotEmpty()) {
                            val resolved = baseStack.last().resolve(text) ?: baseStack.last()
                            baseStack.addLast(resolved)
                            if (openRep != null) openRep.base = resolved
                            else asBase = resolved
                        }
                    }
                    "Period" -> {
                        periodDuration = parser.getAttr("duration")
                            ?.let { parseIsoDurationSeconds(it) } ?: mpdDuration
                        asType = ""
                        asBase = null
                        asTimescale = 1L
                        asDuration = 0L
                        asStartNumber = 1L
                        asInit = null
                        asMedia = null
                        asTimeline = emptyList()
                        asSegmentList = emptyList()
                    }
                    "AdaptationSet" -> {
                        asType = parser.getAttr("contentType")
                            ?: parser.getAttr("mimeType")?.substringBefore("/")
                            ?: ""
                    }
                    "Representation" -> {
                        val mime = parser.getAttr("mimeType")
                        val type = when {
                            asType.isNotBlank() -> asType
                            mime?.startsWith("video") == true -> "video"
                            mime?.startsWith("audio") == true -> "audio"
                            parser.getAttr("height") != null -> "video"
                            else -> "audio"
                        }
                        asType = type
                        openRep = OpenRep(
                            id = parser.getAttr("id").orEmpty(),
                            bandwidth = parser.getAttr("bandwidth")?.toIntOrNull() ?: 0,
                            width = parser.getAttr("width")?.toIntOrNull(),
                            height = parser.getAttr("height")?.toIntOrNull(),
                            base = asBase ?: baseStack.last(),
                        )
                    }
                    "SegmentTemplate" -> {
                        val timescale = parser.getAttr("timescale")?.toLongOrNull() ?: 1L
                        val duration = parser.getAttr("duration")?.toLongOrNull() ?: 0L
                        val start = parser.getAttr("startNumber")?.toLongOrNull() ?: 1L
                        val init = parser.getAttr("initialization")
                        val media = parser.getAttr("media")
                        if (openRep != null) {
                            openRep.timescale = timescale
                            openRep.duration = duration
                            openRep.startNumber = start
                            openRep.init = init
                            openRep.media = media
                        } else {
                            asTimescale = timescale
                            asDuration = duration
                            asStartNumber = start
                            asInit = init
                            asMedia = media
                        }
                    }
                    "SegmentTimeline" -> {
                        inTimeline = true
                        timelineBuf.clear()
                    }
                    "S" -> if (inTimeline) {
                        timelineBuf += TimelineS(
                            t = parser.getAttr("t")?.toLongOrNull(),
                            d = parser.getAttr("d")?.toLongOrNull() ?: 0L,
                            r = parser.getAttr("r")?.toIntOrNull() ?: 0,
                        )
                    }
                    "SegmentList" -> {
                        inSegmentList = true
                        listBuf.clear()
                    }
                    "Initialization" -> {
                        val source = parser.getAttr("sourceURL") ?: parser.getAttr("media")
                        if (source != null) {
                            if (openRep != null) openRep.init = source else asInit = source
                        }
                    }
                    "SegmentURL" -> if (inSegmentList) {
                        parser.getAttr("media")?.let { listBuf += it }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "BaseURL" -> Unit
                    "SegmentTimeline" -> {
                        inTimeline = false
                        if (openRep != null) openRep.timeline = timelineBuf.toList()
                        else asTimeline = timelineBuf.toList()
                    }
                    "SegmentList" -> {
                        inSegmentList = false
                        if (openRep != null) openRep.segmentList = listBuf.toList()
                        else asSegmentList = listBuf.toList()
                    }
                    "Representation" -> {
                        val rep = openRep
                        openRep = null
                        if (rep != null) {
                            val timescale = rep.timescale ?: asTimescale
                            val duration = rep.duration ?: asDuration
                            val start = rep.startNumber ?: asStartNumber
                            val init = rep.init ?: asInit
                            val media = rep.media ?: asMedia
                            val timeline = rep.timeline ?: asTimeline
                            val list = rep.segmentList ?: asSegmentList
                            val urls = buildUrls(
                                base = rep.base,
                                repId = rep.id,
                                bandwidth = rep.bandwidth,
                                init = init,
                                media = media,
                                list = list,
                                timeline = timeline,
                                timescale = timescale,
                                duration = duration,
                                startNumber = start,
                                periodSeconds = periodDuration,
                            )
                            if (urls.isNotEmpty()) {
                                out += DashRepresentation(
                                    id = rep.id,
                                    contentType = asType.ifBlank { "video" },
                                    bandwidth = rep.bandwidth,
                                    width = rep.width,
                                    height = rep.height,
                                    segmentUrls = urls,
                                )
                            }
                        }
                    }
                    "Period", "AdaptationSet" -> {
                        while (baseStack.size > 1) baseStack.removeLast()
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun buildUrls(
        base: HttpUrl,
        repId: String,
        bandwidth: Int,
        init: String?,
        media: String?,
        list: List<String>,
        timeline: List<TimelineS>,
        timescale: Long,
        duration: Long,
        startNumber: Long,
        periodSeconds: Double,
    ): List<String> {
        fun resolve(ref: String, number: Long? = null, time: Long? = null): String {
            val expanded = expandTemplate(ref, repId, bandwidth, number, time)
            return base.resolve(expanded)?.toString() ?: expanded
        }
        val urls = mutableListOf<String>()
        if (init != null) urls += resolve(init, startNumber, 0)
        if (list.isNotEmpty()) {
            urls += list.map { resolve(it) }
            return urls
        }
        if (media == null) return urls
        if (timeline.isNotEmpty()) {
            var time = timeline.first().t ?: 0L
            var number = startNumber
            for (s in timeline) {
                if (s.t != null) time = s.t
                repeat(s.r + 1) {
                    urls += resolve(media, number, time)
                    time += s.d
                    number++
                }
            }
            return urls
        }
        if (duration > 0 && periodSeconds > 0) {
            val count = kotlin.math.ceil(periodSeconds * timescale / duration.toDouble()).toInt().coerceAtLeast(1)
            for (i in 0 until count) {
                val number = startNumber + i
                val time = i * duration
                urls += resolve(media, number, time)
            }
        }
        return urls
    }

    private fun expandTemplate(
        template: String,
        repId: String,
        bandwidth: Int,
        number: Long?,
        time: Long?,
    ): String {
        var value = template.replace("\$RepresentationID\$", repId)
            .replace("\$Bandwidth\$", bandwidth.toString())
        value = replaceToken(value, "Number", number)
        value = replaceToken(value, "Time", time)
        return value
    }

    private fun replaceToken(input: String, name: String, raw: Long?): String {
        val regex = Regex("""\$$name(%[^$]+)?\$""")
        return regex.replace(input) { match ->
            if (raw == null) return@replace match.value
            val fmt = match.groupValues[1]
            if (fmt.isEmpty()) raw.toString() else fmt.substring(1).let { String.format("%$it", raw) }
        }
    }

    private fun XmlPullParser.getAttr(name: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i) == name) return getAttributeValue(i)
        }
        return null
    }

    private data class TimelineS(val t: Long?, val d: Long, val r: Int)
}
