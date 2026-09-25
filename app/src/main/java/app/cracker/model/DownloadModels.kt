package app.cracker.model

enum class JobKind { Live, Vod, Clip }

val JobKind.isLive: Boolean get() = this == JobKind.Live

enum class JobStatus { Queued, Running, Paused, Completed, Failed, Stopped, Cancelled }

enum class StreamProtocol { Hls, Dash }

data class QualityOption(
    val id: String,
    val label: String,
    val note: String,
    val protocol: StreamProtocol = StreamProtocol.Hls,
    val mediaUrl: String = "",
    val dashVideoRepId: String? = null,
    val dashAudioRepId: String? = null,
)

data class VideoMeta(
    val sourceUrl: String,
    val kind: JobKind,
    val title: String,
    val channel: String,
    val isAdult: Boolean,
    val durationLabel: String?,
    val qualities: List<QualityOption>,
)

data class DownloadJob(
    val id: String,
    val kind: JobKind,
    val title: String,
    val channel: String,
    val quality: String,
    val status: JobStatus,
    val progress: Float = 0f,
    val elapsedLabel: String? = null,
    val speedLabel: String? = null,
    val processingLabel: String? = null,
    val isAdult: Boolean = false,
    val error: String? = null,
    val attempt: Int = 1,
    val maxAttempts: Int = 1,
)

sealed class ExtractResult {
    data class Ready(val meta: VideoMeta) : ExtractResult()
    data class NeedsLogin(val reason: String) : ExtractResult()
    data class Offline(val channel: String?) : ExtractResult()
    data class Failed(val message: String) : ExtractResult()
}
