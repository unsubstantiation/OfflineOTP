/*
 * Copyright (C) 2018 Jakob Nixdorf
 * Copyright (C) 2018 Richy HBM
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Utilities

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

import org.shadowice.flocke.andotp.R

import java.util.Locale

/** Creates backup notification channels and posts backup status notifications. */
object NotificationHelper {
    // Only read after the Android 13 (TIRAMISU) check in requestNotificationPermission().
    @SuppressLint("InlinedApi")
    private const val POST_NOTIFICATIONS_PERMISSION = Manifest.permission.POST_NOTIFICATIONS
    private const val POST_NOTIFICATIONS_REQUEST_CODE = 1

    /**
     * Asks for the notification permission on Android 13+ if it is not granted yet.
     * A no-op before Android 13 (API 33).
     */
    @JvmStatic
    fun requestNotificationPermission(fragment: android.app.Fragment) {
        val context: Context? = fragment.getActivity()
        if (context == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
            return
        }

        if (ContextCompat.checkSelfPermission(context, POST_NOTIFICATIONS_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            fragment.requestPermissions(arrayOf(POST_NOTIFICATIONS_PERMISSION), POST_NOTIFICATIONS_REQUEST_CODE)
        }
    }

    // A channel id is a persisted machine token: case-map it with Locale.ROOT, never the default
    // locale (Turkish would create "andOTP_backup_faıled" next to the existing channel).
    private fun channelId(channel: Constants.NotificationChannel): String =
        "andOTP_" + channel.name.lowercase(Locale.ROOT)

    /** Creates the channel for the given type. */
    private fun createNotificationChannel(context: Context, channel: Constants.NotificationChannel) {
        val notificationChannel = NotificationChannel(channelId(channel), context.getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT)

        when (channel) {
            Constants.NotificationChannel.BACKUP_FAILED -> {
                notificationChannel.setName(context.getString(R.string.notification_channel_name_backup_failed))
                notificationChannel.setDescription(context.getString(R.string.notification_channel_desc_backup_failed))
                notificationChannel.setImportance(NotificationManager.IMPORTANCE_HIGH)
            }
            Constants.NotificationChannel.BACKUP_SUCCESS -> {
                notificationChannel.setName(context.getString(R.string.notification_channel_name_backup_success))
                notificationChannel.setDescription(context.getString(R.string.notification_channel_desc_backup_success))
                notificationChannel.setImportance(NotificationManager.IMPORTANCE_LOW)
            }
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(notificationChannel)
    }

    /** Creates all notification channels the app can use. */
    @JvmStatic
    fun initializeNotificationChannels(context: Context) {
        for (channel in Constants.NotificationChannel.values()) {
            createNotificationChannel(context, channel)
        }
    }

    /** Posts a notification with a string resource as its body. */
    @JvmStatic
    fun notify(context: Context, channel: Constants.NotificationChannel, resIdTitle: Int, resIdBody: Int) {
        notify(context, channel, resIdTitle, context.getText(resIdBody).toString())
    }

    /**
     * Per-channel ids: a single shared id made every new notification replace the previous one, so
     * a backup success could silently overwrite the failure it followed (and vice versa).
     */
    private fun notificationId(channel: Constants.NotificationChannel): Int = when (channel) {
        Constants.NotificationChannel.BACKUP_FAILED -> NOTIFICATION_ID_BACKUP_FAILED
        Constants.NotificationChannel.BACKUP_SUCCESS -> NOTIFICATION_ID_BACKUP_SUCCESS
    }

    /** Posts a notification with an already resolved body text. */
    @JvmStatic
    fun notify(context: Context, channel: Constants.NotificationChannel, resIdTitle: Int, resBody: String?) {
        val builder = NotificationCompat.Builder(context, channelId(channel))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getText(resIdTitle))
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(resBody))

        createNotificationChannel(context, channel)

        val notificationId = notificationId(channel)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, builder.build())
    }

    private const val NOTIFICATION_ID_BACKUP_FAILED = 1
    private const val NOTIFICATION_ID_BACKUP_SUCCESS = 2
}
