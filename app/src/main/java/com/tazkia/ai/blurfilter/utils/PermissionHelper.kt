package com.tazkia.ai.blurfilter.utils

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AlertDialog
import com.tazkia.ai.blurfilter.R

object PermissionHelper {

    const val REQUEST_MEDIA_PROJECTION = 1001
    const val REQUEST_OVERLAY_PERMISSION = 1002

    /**
     * Check if overlay permission is granted
     */
    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Check if accessibility service is enabled
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val serviceName = "${context.packageName}/.service.AccessibilityMonitorService"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        return enabledServices?.contains(serviceName) == true
    }

    /**
     * Request overlay permission
     */
    fun requestOverlayPermission(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.permission_overlay_title)
            .setMessage(R.string.permission_overlay_message)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}"))
                launcher.launch(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
    /**
     * Request accessibility permission
     */
    fun requestAccessibilityPermission(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.permission_accessibility_title)
            .setMessage(R.string.permission_accessibility_message)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                activity.startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Request media projection permission
     */
    fun requestMediaProjection(
        activity: Activity,
        launcher: ActivityResultLauncher<Intent>
    ) {
        AlertDialog.Builder(activity)
            .setTitle(R.string.permission_media_projection_title)
            .setMessage(R.string.permission_media_projection_message)
            .setPositiveButton(R.string.grant_permission) { _, _ ->
                val mediaProjectionManager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                        as MediaProjectionManager
                launcher.launch(mediaProjectionManager.createScreenCaptureIntent())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Check all required permissions
     */
    fun hasAllPermissions(context: Context): Boolean {
        return hasOverlayPermission(context) && isAccessibilityServiceEnabled(context)
    }
}