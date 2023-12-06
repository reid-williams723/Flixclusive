package com.flixclusive.service.utils

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

object IntentUtils {
    fun installApkPendingActivity(context: Context, uri: Uri): PendingIntent {
        val intent = installApkActivity(uri)

        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    fun installApkActivity(uri: Uri): Intent {
        val mime = "application/vnd.android.package-archive"
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
    }
}