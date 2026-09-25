package app.cracker.download

import android.app.NotificationChannel
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.cracker.MainActivity
import app.cracker.model.DownloadJob
import app.cracker.model.JobKind

class TransferNotifier(context: Context) {
    private val app = context.applicationContext

    fun notifyFinished(job: DownloadJob) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "완료", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = if (job.kind == JobKind.Live) "녹화가 끝났어요" else "다운로드가 끝났어요"
        manager.notify(
            job.id.hashCode(),
            NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(job.title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        const val CHANNEL_ID = "transfers_done"
    }
}
