package com.tazkia.ai.blurfilter.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import androidx.localbroadcastmanager.content.LocalBroadcastManager

class AccessibilityMonitorService : AccessibilityService() {

    companion object {
        const val ACTION_SCROLL_EVENT = "com.tazkia.SCROLL_EVENT"
        const val ACTION_WINDOW_CHANGE = "com.tazkia.WINDOW_CHANGE"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }

    private var currentPackage: String? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // Notify about scroll event
                sendBroadcast(ACTION_SCROLL_EVENT)
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val packageName = event.packageName?.toString()

                if (packageName != null && packageName != currentPackage) {
                    currentPackage = packageName

                    // Notify about window/app change
                    val intent = Intent(ACTION_WINDOW_CHANGE)
                    intent.putExtra(EXTRA_PACKAGE_NAME, packageName)
                    sendBroadcast(intent)
                }
            }
        }
    }

    override fun onInterrupt() {
        // Service interrupted
    }

    private fun sendBroadcast(action: String) {
        val intent = Intent(action)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun sendBroadcast(intent: Intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }
}