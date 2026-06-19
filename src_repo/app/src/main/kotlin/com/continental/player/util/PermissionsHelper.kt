package com.continental.player.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object PermissionsHelper {

    /** The single permission string that matters for scanning the device for videos. */
    fun mediaPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasMediaPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, mediaPermission()) ==
            PackageManager.PERMISSION_GRANTED

    /** Null on API levels that never had a runtime notification permission. */
    fun notificationPermissionOrNull(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

    fun hasNotificationPermission(context: Context): Boolean {
        val permission = notificationPermissionOrNull() ?: return true
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }
}
