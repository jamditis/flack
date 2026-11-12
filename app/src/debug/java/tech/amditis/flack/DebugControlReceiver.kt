package tech.amditis.flack

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Debug builds only (src/debug). The emulator test uses it to set the switches
 * and to reply the way Android Auto does: fill in the car copy's RemoteInput and
 * send its real PendingIntent through the system.
 *
 * adb shell am broadcast -a tech.amditis.flack.debug.CONFIGURE --ez enabled true --ez car_only false
 * adb shell am broadcast -a tech.amditis.flack.debug.REPLY_FROM_CAR --es text "On my way"
 */
class DebugControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "tech.amditis.flack.debug.CONFIGURE" -> {
                if (intent.hasExtra("car_only")) BridgeState.setCarOnly(context, intent.getBooleanExtra("car_only", true))
                if (intent.hasExtra("enabled")) BridgeState.setEnabled(context, intent.getBooleanExtra("enabled", false))
                Log.i(TAG, "configured enabled=${BridgeState.isEnabled(context)} carOnly=${BridgeState.isCarOnly(context)}")
            }
            "tech.amditis.flack.debug.REPLY_FROM_CAR" -> replyFromCar(context, intent.getStringExtra("text").orEmpty())
        }
    }

    private fun replyFromCar(context: Context, text: String) {
        val copy = context.getSystemService(NotificationManager::class.java).activeNotifications
            .maxByOrNull { it.postTime }
        val action = copy?.notification?.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        if (action == null) {
            Log.w(TAG, "no car copy with a reply action")
            return
        }
        val fill = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply { putCharSequence(CarNotificationPublisher.REPLY_KEY, text) })
        action.actionIntent.send(context, 0, fill)
        Log.i(TAG, "sent car reply")
    }

    private companion object {
        const val TAG = "FlackDebug"
    }
}
