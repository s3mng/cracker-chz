package app.cracker.chzzk

import app.cracker.model.ExtractResult
import app.cracker.model.JobKind
import app.cracker.model.QualityOption
import app.cracker.model.StreamProtocol
import app.cracker.model.VideoMeta
import app.cracker.net.DASH_XML_ACCEPT
import app.cracker.net.getText
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

class ChzzkExtractor(private val http: OkHttpClient) {
    fun resolve(rawUrl: String): ExtractResult {
        val target = ChzzkUrl.parse(rawUrl) ?: return ExtractResult.Failed("치지직 라이브, 다시보기, 클립 링크를 붙여 주세요")
        return try {
            when (target.kind) {
                JobKind.Live -> resolveLive(rawUrl, target.id)
                JobKind.Vod -> resolveVideo(rawUrl, target.id)
                JobKind.Clip -> resolveClip(rawUrl, target.id)
            }
        } catch (error: Exception) {
            ExtractResult.Failed(error.message ?: "영상을 찾지 못했어요")
        }
    }

    private fun resolveLive(sourceUrl: String, channelId: String): ExtractResult {
        val content = getJson("https://api.chzzk.naver.com/service/v3/channels/$channelId/live-detail")
        val channel = content.optJSONObject("channel")?.optString("channelName").orEmpty()
            .ifBlank { null }
        if (content.optString("status") == "CLOSE") {
            return ExtractResult.Offline(channel)
        }
        val adult = content.optBoolean("adult")
        if (adult && !isAdultAllowed(content)) {
            return ExtractResult.NeedsLogin(adultReason(content))
        }
        val playbackRaw = content.optString("livePlaybackJson")
        if (playbackRaw.isNullOrBlank() || playbackRaw == "null") {
            return if (adult) ExtractResult.NeedsLogin(adultReason(content))
            else ExtractResult.Failed("재생 정보를 받지 못했어요")
        }
        val playback = JSONObject(playbackRaw)
        val media = playback.optJSONArray("media") ?: return ExtractResult.Failed("스트림 주소를 찾지 못했어요")
        var hlsPath: String? = null
        for (i in 0 until media.length()) {
            val item = media.optJSONObject(i) ?: continue
            val path = item.optString("path")
            if (path.isNullOrBlank()) continue
            if (item.optString("mediaId") != "LLHLS") {
                hlsPath = path
                break
            }
            if (hlsPath == null) hlsPath = path
        }
        val masterUrl = hlsPath ?: return ExtractResult.Failed("HLS 주소를 찾지 못했어요")
        val qualities = hlsQualities(masterUrl)
        if (qualities.isEmpty()) return ExtractResult.Failed("화질 목록을 읽지 못했어요")
        return ExtractResult.Ready(
            VideoMeta(
                sourceUrl = sourceUrl,
                kind = JobKind.Live,
                title = content.optString("liveTitle").ifBlank { "치지직 라이브" },
                channel = channel ?: "치지직",
                isAdult = adult,
                durationLabel = null,
                qualities = qualities,
            ),
        )
    }

    private fun resolveVideo(sourceUrl: String, videoId: String): ExtractResult {
        val content = getJson("https://api.chzzk.naver.com/service/v3/videos/$videoId")
        val adult = content.optBoolean("adult")
        if (adult && !isAdultAllowed(content)) {
            return ExtractResult.NeedsLogin(adultReason(content))
        }
        val channel = content.optJSONObject("channel")?.optString("channelName").orEmpty()
            .ifBlank { "치지직" }
        val title = content.optString("videoTitle").ifBlank { "치지직 다시보기" }
        val duration = content.optInt("duration")
        val vodStatus = content.optString("vodStatus")
        val qualities = if (vodStatus == "ABR_HLS") {
            val nid = content.optString("videoId")
            val key = content.optString("inKey")
            if (nid.isBlank() || key.isBlank() || nid == "null" || key == "null") {
                return ExtractResult.Failed("재생 키가 없어요. 로그인 상태를 확인해 주세요")
            }
            val mpdUrl = "https://apis.naver.com/neonplayer/vodplay/v1/playback/$nid"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("key", key)
                .addQueryParameter("env", "real")
                .addQueryParameter("lc", "en_US")
                .addQueryParameter("cpl", "en_US")
                .build()
                .toString()
            dashQualities(mpdUrl)
        } else {
            val rewind = content.optString("liveRewindPlaybackJson")
            if (rewind.isNullOrBlank() || rewind == "null") {
                return if (adult) ExtractResult.NeedsLogin(adultReason(content))
                else ExtractResult.Failed("아직 받을 수 없는 다시보기예요")
            }
            val playback = JSONObject(rewind)
            val path = playback.optJSONArray("media")?.optJSONObject(0)?.optString("path")
            if (path.isNullOrBlank()) return ExtractResult.Failed("HLS 주소를 찾지 못했어요")
            hlsQualities(path)
        }
        if (qualities.isEmpty()) return ExtractResult.Failed("화질 목록을 읽지 못했어요")
        return ExtractResult.Ready(
            VideoMeta(
                sourceUrl = sourceUrl,
                kind = JobKind.Vod,
                title = title,
                channel = channel,
                isAdult = adult,
                durationLabel = formatDuration(duration).ifBlank { null },
                qualities = qualities,
            ),
        )
    }

    private fun resolveClip(sourceUrl: String, clipId: String): ExtractResult {
        val content = getJson("https://api.chzzk.naver.com/service/v1/play-info/clip/$clipId")
        val adult = content.optBoolean("adult")
        val nid = content.optString("videoId")
        val key = content.optString("inKey")
        val hasPlayback = nid.isNotBlank() && nid != "null" && key.isNotBlank() && key != "null"
        if (!hasPlayback) {
            return if (adult) ExtractResult.NeedsLogin(adultReason(content))
            else ExtractResult.Failed("클립 재생 정보를 받지 못했어요")
        }
        val mpdUrl = "https://apis.naver.com/neonplayer/vodplay/v2/playback/$nid"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("key", key)
            .build()
            .toString()
        val qualities = dashQualities(mpdUrl)
        if (qualities.isEmpty()) return ExtractResult.Failed("화질 목록을 읽지 못했어요")
        val duration = content.optInt("duration")
        return ExtractResult.Ready(
            VideoMeta(
                sourceUrl = sourceUrl,
                kind = JobKind.Clip,
                title = content.optString("contentTitle").ifBlank { "치지직 클립" },
                channel = content.optJSONObject("ownerChannel")?.optString("channelName").orEmpty()
                    .ifBlank { "치지직" },
                isAdult = adult,
                durationLabel = formatDuration(duration).ifBlank { null },
                qualities = qualities,
            ),
        )
    }

    private fun hlsQualities(url: String): List<QualityOption> {
        val body = http.getText(url)
        if (!HlsParser.isMaster(body)) {
            return listOf(
                QualityOption(
                    id = "source",
                    label = "원본",
                    note = "HLS",
                    protocol = StreamProtocol.Hls,
                    mediaUrl = url,
                ),
            )
        }
        return HlsParser.parseMaster(body, url).mapIndexed { index, variant ->
            val label = variant.height?.let { "${it}p" } ?: "${variant.bandwidth / 1000}k"
            QualityOption(
                id = "hls-$index-${variant.height ?: variant.bandwidth}",
                label = label,
                note = if (index == 0) "HLS · 추천" else "HLS",
                protocol = StreamProtocol.Hls,
                mediaUrl = variant.uri,
            )
        }
    }

    private fun dashQualities(mpdUrl: String): List<QualityOption> {
        val xml = http.getText(mpdUrl, DASH_XML_ACCEPT)
        val reps = DashParser.parse(xml, mpdUrl)
        val videos = reps.filter { it.contentType == "video" }.sortedByDescending { it.height ?: 0 }
        val audioId = reps.filter { it.contentType == "audio" }.maxByOrNull { it.bandwidth }?.id
        return videos.mapIndexed { index, video ->
            QualityOption(
                id = "dash-${video.id}",
                label = video.height?.let { "${it}p" } ?: video.id,
                note = if (index == 0) "DASH · 추천" else "DASH",
                protocol = StreamProtocol.Dash,
                mediaUrl = mpdUrl,
                dashVideoRepId = video.id,
                dashAudioRepId = audioId,
            )
        }
    }

    private fun getJson(url: String): JSONObject {
        val root = JSONObject(http.getText(url))
        return root.optJSONObject("content") ?: error("치지직 응답이 비어 있어요")
    }

    private fun isAdultAllowed(content: JSONObject): Boolean =
        content.optString("userAdultStatus") == "ADULT"

    private fun adultReason(content: JSONObject): String {
        return when (content.optString("userAdultStatus")) {
            "ADULT" -> "성인 인증이 필요해요"
            else -> "성인 영상은 본인 인증된 네이버 로그인이 필요해요"
        }
    }
}
