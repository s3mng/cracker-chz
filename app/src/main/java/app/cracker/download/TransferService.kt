package app.cracker.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.cracker.CrackerApplication
import app.cracker.MainActivity
import app.cracker.model.JobStatus
import app.cracker.model.isLive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification("준비 중", null, null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        val coordinator = (application as CrackerApplication).graph.coordinator
        observer = scope.launch {
            val coordinator = (application as CrackerApplication).graph.coordinator
            while (isActive) {
                val active = coordinator.jobs.value.firstOrNull {
                    it.status == JobStatus.Running || it.status == JobStatus.Paused || it.status == JobStatus.Queued
                }
                if (active == null) {
                    delay(400)
                    val still = coordinator.jobs.value.firstOrNull {
                        it.status == JobStatus.Running || it.status == JobStatus.Paused || it.status == JobStatus.Queued
                    }
                    if (still == null) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                } else {
                    val text = when {
                        active.kind.isLive -> listOfNotNull(active.elapsedLabel ?: "녹화 중", active.speedLabel).joinToString(" · ")
                        active.status == JobStatus.Paused -> "일시정지"
                        else -> listOfNotNull("${(active.progress * 100).toInt()}%", active.speedLabel).joinToString(" · ")
                    }
                    ServiceCompat.startForeground(
                        this@TransferService,
                        NOTIFICATION_ID,
                        notification(
                            active.title,
                            text,
                            active.id,
                            active.kind.isLive,
                            (active.progress * 100).toInt(),
                        ),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                    )
                }
                delay(250)
            }
        }
        coordinator.startLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val coordinator = (application as CrackerApplication).graph.coordinator
        if (intent?.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ID)?.let(coordinator::cancel)
        }
        coordinator.startLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as CrackerApplication).graph.coordinator.stopForTimeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "다운로드", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun notification(
        title: String,
        text: String?,
        jobId: String?,
        live: Boolean = false,
        progress: Int? = null,
    ): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text ?: "cracker")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (progress != null && !live) {
            builder.setProgress(100, progress.coerceIn(0, 100), false)
        } else if (live) {
            builder.setProgress(0, 0, true)
        }
        if (jobId != null) {
            val cancelIntent = PendingIntent.getService(
                this,
                jobId.hashCode(),
                Intent(this, TransferService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, jobId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, if (live) "중지" else "취소", cancelIntent)
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "transfers"
        const val NOTIFICATION_ID = 42
        const val ACTION_CANCEL = "app.cracker.CANCEL"
        const val EXTRA_ID = "id"
    }
}
