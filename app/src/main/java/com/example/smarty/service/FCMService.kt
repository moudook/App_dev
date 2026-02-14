package com.example.smarty.service

import android.util.Log
import com.example.smarty.core.common.util.CrashLogger
import com.example.smarty.core.common.util.NotificationHelper
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FCMService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "Refreshed token: $token")

        // If you want to send messages to this application instance or
        // manage this apps subscriptions on the server side, send the
        // FCM registration token to your app server.
        sendRegistrationToServer(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d(TAG, "From: ${remoteMessage.from}")

        // Check if message contains a data payload.
        if (remoteMessage.data.isNotEmpty()) {
            Log.d(TAG, "Message data payload: ${remoteMessage.data}")
            handleDataMessage(remoteMessage.data)
        }

        // Check if message contains a notification payload.
        remoteMessage.notification?.let {
            Log.d(TAG, "Message Notification Body: ${it.body}")
            sendNotification(it.title, it.body)
        }
    }

    private fun handleDataMessage(data: Map<String, String>) {
        // Handle background data sync or other logic here
        // For now, we just log it
    }

    private fun sendNotification(title: String?, messageBody: String?) {
        val notificationTitle = title ?: "Smarty Notification"
        val notificationBody = messageBody ?: "You have a new message"

        NotificationHelper.showNotification(this, notificationTitle, notificationBody)
    }

    private fun sendRegistrationToServer(token: String) {
        // TODO: Implement API call to send token to backend
        Log.d(TAG, "Sending token to server: $token")
    }

    companion object {
        private const val TAG = "FCMService"
    }
}
