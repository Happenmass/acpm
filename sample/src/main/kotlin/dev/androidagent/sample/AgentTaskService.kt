package dev.androidagent.sample

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.androidagent.sample.androiduse.AndroidUseSession
import dev.androidagent.sdk.AgentRuntime
import dev.androidagent.sdk.RunStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Optional notification and lifetime policy for this sample's user-initiated runs. */
internal class AgentTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitoring: Job? = null
    private var watchedId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runId = intent?.getStringExtra(RUN_ID)
        val run = AgentRuntime.get(applicationContext).activeRun.value?.takeIf { it.id == runId }
        if (intent?.action == CANCEL) {
            if (runId == watchedId && run != null) {
                AndroidUseSession.revoke()
                run.cancel()
            }
            if (watchedId == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        val notification = notification(runId.orEmpty())
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION, notification)
        monitoring?.cancel()
        watchedId = runId
        monitoring = scope.launch {
            run?.state?.first { it.status != RunStatus.Running }
            watchedId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun notification(runId: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Local agent task", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancelTask = PendingIntent.getService(this, runId.hashCode(),
            Intent(this, AgentTaskService::class.java).setAction(CANCEL).putExtra(RUN_ID, runId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val controlsAndroid = (application as SampleAgentHost).androidUse.runId == runId
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(if (controlsAndroid) "AI SDK Android Use is active" else "Local agent running")
            .setContentText(if (controlsAndroid) "Tap Stop to revoke access." else "Open the app for progress or approvals.")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, if (controlsAndroid) "Stop" else "Cancel", cancelTask).build())
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val RUN_ID = "live_agent_run"
        private const val CANCEL = "dev.androidagent.sample.CANCEL_RUN"
        private const val CHANNEL = "agent_tasks"
        private const val NOTIFICATION = 91
    }
}
