package app.cracker.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.cracker.model.DownloadJob
import app.cracker.model.JobKind
import app.cracker.model.JobStatus
import app.cracker.model.isLive
import app.cracker.ui.theme.AdultClay
import app.cracker.ui.theme.Cheddar
import app.cracker.ui.theme.LiveCoral
import app.cracker.ui.theme.OkSage
import kotlin.math.absoluteValue

@Composable
fun JobCard(
    job: DownloadJob,
    onCancel: () -> Unit,
    onTogglePause: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .combinedClickable(
                onClick = {},
                onLongClick = onLongPress,
            )
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChannelMark(name = job.channel, kind = job.kind, running = job.status == JobStatus.Running)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(job)
                    if (job.isAdult) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "19",
                            style = MaterialTheme.typography.labelSmall,
                            color = AdultClay,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        job.quality,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    job.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOfNotNull(job.channel, job.elapsedLabel).joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            JobActions(job, onCancel, onTogglePause)
        }
        statusCaption(job)?.let { caption ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = caption,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = true,
            )
        }
        if (!job.kind.isLive && job.status != JobStatus.Completed && job.status != JobStatus.Failed && job.status != JobStatus.Cancelled) {
            Spacer(Modifier.height(14.dp))
            if (job.processingLabel != null) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)),
                    color = Cheddar,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            } else {
                LinearProgressIndicator(
                    progress = { job.progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(RoundedCornerShape(50)),
                    color = Cheddar,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
            }
        }
        if (job.kind.isLive && job.status == JobStatus.Running) {
            Spacer(Modifier.height(14.dp))
            LivePulseBar()
        }
    }
}

@Composable
private fun JobActions(
    job: DownloadJob,
    onCancel: () -> Unit,
    onTogglePause: () -> Unit,
) {
    Row {
        if (!job.kind.isLive && job.processingLabel == null && job.status in listOf(JobStatus.Running, JobStatus.Paused)) {
            IconButton(onClick = onTogglePause) {
                Icon(
                    if (job.status == JobStatus.Paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (job.status == JobStatus.Paused) "다시 받기" else "일시정지",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (job.status == JobStatus.Running || job.status == JobStatus.Paused || job.status == JobStatus.Queued) {
            IconButton(onClick = onCancel) {
                Icon(
                    if (job.kind.isLive) Icons.Filled.Stop else Icons.Filled.Close,
                    contentDescription = if (job.kind.isLive) "녹화 중지" else "취소",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StatusChip(job: DownloadJob) {
    val (label, color) = when {
        job.kind.isLive && job.status == JobStatus.Running -> "LIVE" to LiveCoral
        job.status == JobStatus.Paused -> "PAUSED" to Cheddar
        job.status == JobStatus.Completed -> "DONE" to OkSage
        job.status == JobStatus.Cancelled -> "CANCEL" to MaterialTheme.colorScheme.onSurfaceVariant
        job.status == JobStatus.Failed -> "FAIL" to LiveCoral
        job.status == JobStatus.Stopped -> "STOP" to MaterialTheme.colorScheme.onSurfaceVariant
        job.kind == JobKind.Clip -> "CLIP" to Cheddar
        job.kind == JobKind.Vod -> "VOD" to Cheddar
        else -> "WAIT" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ChannelMark(name: String, kind: JobKind, running: Boolean) {
    val palette = listOf(Cheddar, LiveCoral, OkSage, AdultClay, Color(0xFF7AA2C4))
    val color = palette[name.hashCode().absoluteValue % palette.size]
    Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(color.copy(alpha = 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name.take(1),
                style = MaterialTheme.typography.titleLarge,
                color = color,
            )
        }
        if (kind == JobKind.Live && running) {
            val pulse by rememberInfiniteTransition(label = "live").animateFloat(
                initialValue = 1f,
                targetValue = 1.35f,
                animationSpec = infiniteRepeatable(
                    tween(900, easing = LinearEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot",
            )
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(10.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(LiveCoral),
            )
        }
    }
}

@Composable
private fun LivePulseBar() {
    val shift by rememberInfiniteTransition(label = "bar").animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "shift",
    )
    LinearProgressIndicator(
        progress = { shift },
        modifier = Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(50)),
        color = LiveCoral,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        drawStopIndicator = {},
    )
}

private fun statusCaption(job: DownloadJob): String? = when (job.status) {
    JobStatus.Running -> when {
        job.processingLabel != null -> job.processingLabel
        job.kind.isLive -> listOfNotNull("녹화 중", job.speedLabel).joinToString(" · ")
        job.attempt > 1 -> listOfNotNull(
            "${(job.progress * 100).toInt()}%",
            "재시도 ${job.attempt}/${job.maxAttempts}",
            job.speedLabel,
        ).joinToString(" · ")
        else -> listOfNotNull("${(job.progress * 100).toInt()}%", job.speedLabel).joinToString(" · ")
    }
    JobStatus.Paused -> "일시정지"
    JobStatus.Completed -> "저장됨"
    JobStatus.Cancelled -> "취소"
    JobStatus.Failed -> "실패"
    JobStatus.Stopped -> "중지"
    JobStatus.Queued -> "대기"
}
