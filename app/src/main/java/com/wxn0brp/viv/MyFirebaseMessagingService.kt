package com.wxn0brp.viv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.runBlocking

class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        
        Log.d("FCM_VIV", "Odebrano wiadomość! Data: ${remoteMessage.data}")
        Log.d("FCM_VIV", "Notification: ${remoteMessage.notification?.title} - ${remoteMessage.notification?.body}")

        // Szukamy tagów wyłącznie w tytule (title)
        val title = remoteMessage.data["title"] ?: remoteMessage.notification?.title
        val body = remoteMessage.data["body"] ?: remoteMessage.notification?.body

        if (title != null || body != null) {
            val database = (application as ViVApplication).database
            runBlocking {
                database.notificationDao().insert(
                    NotificationEntity(
                        title = title,
                        body = body,
                        timestamp = System.currentTimeMillis()
                    )
                )
            }
            Log.d("FCM_VIV", "Zapisano do bazy Room: $title")

            // Pobranie zdefiniowanych tagów (case-insensitive, bez [])
            val prefs = getSharedPreferences("viv_prefs", Context.MODE_PRIVATE)
            val bypassTagsString = prefs.getString("bypass_tags", "ALARM") ?: "ALARM"
            val bypassTags = bypassTagsString.split(",")
                .map { it.trim().replace("[", "").replace("]", "").lowercase() }
                .filter { it.isNotEmpty() }

            var shouldBypass = false
            val checkedTitle = (title ?: "").lowercase()
            
            for (tag in bypassTags) {
                val formattedTag = "[$tag]"
                if (checkedTitle.contains(formattedTag)) {
                    shouldBypass = true
                    break
                }
            }

            if (shouldBypass) {
                Log.d("FCM_VIV", "Wykryto tag bypassujący w tytule: $title. Wymuszam 1 wibrację sprzętową.")
                
                // Pobranie Vibratora obsługującego najnowsze wersje Androida
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vibratorManager?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }

                vibrator?.let {
                    val alarmAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM) // Kluczowe dla ominięcia DND/Wyciszenia profilu
                        .build()

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        it.vibrate(
                            VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE),
                            alarmAttributes
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        it.vibrate(1000)
                    }
                }
            }

            showNotification(title ?: "ViV", body ?: "", shouldBypass)
        } else {
            Log.w("FCM_VIV", "Otrzymano pustą wiadomość (brak title i body)")
        }
    }

    private fun showNotification(title: String, body: String, shouldBypass: Boolean) {
        val channelId = if (shouldBypass) "fcm_bypass_channel_v8" else "fcm_default_channel_v8"
        val channelName = if (shouldBypass) "Powiadomienia Ważne (Bypass)" else "Powiadomienia"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(channelId, channelName, importance).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000)
                
                if (shouldBypass) {
                    setBypassDnd(true)
                    setSound(defaultSoundUri, AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build())
                }
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher) // Używamy ikony aplikacji
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setVibrate(longArrayOf(0, 1000))
            .setSound(defaultSoundUri)

        if (shouldBypass) {
            notificationBuilder.setPriority(NotificationCompat.PRIORITY_MAX)
            notificationBuilder.setCategory(NotificationCompat.CATEGORY_ALARM)
        } else {
            notificationBuilder.setPriority(NotificationCompat.PRIORITY_HIGH)
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }
}
