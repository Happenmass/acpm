package dev.androidagent.sample.androiduse

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent

class AndroidUseService : AccessibilityService() {
    internal var grant: AndroidUseSession? = null
    internal var revision = 0L
        private set
    internal var windowRevision = 0L
        private set
    private var listeningForScreenOff = false

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            grant?.close()
        }
    }

    override fun onServiceConnected() {
        connected = this
        if (!listeningForScreenOff) {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            listeningForScreenOff = true
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (grant == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                windowRevision++
                revision++
                grant?.onWindowChanged()
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val changes = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
                    AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or
                    AccessibilityEvent.WINDOWS_CHANGE_BOUNDS
                if (event.windowChanges and changes != 0) {
                    windowRevision++
                    grant?.onWindowChanged()
                }
                revision++
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (grant?.includesPackage(event.packageName?.toString()) == true) revision++
            }
        }
    }

    override fun onInterrupt() {
        grant?.close()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        grant?.close()
        if (connected === this) connected = null
        if (listeningForScreenOff) {
            unregisterReceiver(screenOff)
            listeningForScreenOff = false
        }
    }

    companion object {
        // Read and changed on Android's main thread.
        internal var connected: AndroidUseService? = null
            private set
    }
}
