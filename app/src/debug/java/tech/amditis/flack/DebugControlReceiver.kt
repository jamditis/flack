package tech.amditis.flack

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * Debug builds only (src/debug). The emulator test uses it to set the switches
 * and to reply the way Android Auto does: fill in the car copy's RemoteInput and
 * send its real PendingIntent through the system.
 *
 * A reply needs an explicit conversation from the test-only fake Slack app. It
 * never picks "the newest copy": debug builds also accept real Slack, and a test
 * reply must never reach a real conversation.
 *
 * adb shell am broadcast -a tech.amditis.flack.debug.CONFIGURE --ez enabled true --ez car_only false
 * adb shell am broadcast -a tech.amditis.flack.debug.REPLY_FROM_CAR --es conversation dm-riley --es text "On my way"
 */
class DebugControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "tech.amditis.flack.debug.CONFIGURE" -> {
                if (intent.hasExtra("car_only")) BridgeState.setCarOnly(context, intent.getBooleanExtra("car_only", true))
                if (intent.hasExtra("enabled")) BridgeState.setEnabled(context, intent.getBooleanExtra("enabled", false))
                if (intent.hasExtra("quiet")) {
                    val quiet = intent.getBooleanExtra("quiet", false)
                    if (quiet != BridgeState.hidesPhoneCopies(context)) BridgeState.setHidePhoneCopies(context, quiet)
                }
                Log.i(TAG, "configured enabled=${BridgeState.isEnabled(context)} carOnly=${BridgeState.isCarOnly(context)}")
            }
            "tech.amditis.flack.debug.REPLY_FROM_CAR" -> replyFromCar(
                context,
                conversation = intent.getStringExtra("conversation"),
                text = intent.getStringExtra("text").orEmpty(),
                repeat = intent.getBooleanExtra("repeat", false),
            )
        }
    }

    private fun replyFromCar(context: Context, conversation: String?, text: String, repeat: Boolean) {
        val binding = conversation?.let { fixtureBinding(it) }
        if (binding == null) {
            Log.w(TAG, "refused: no car copy for that fake Slack conversation")
            return
        }
        val copy = context.getSystemService(NotificationManager::class.java).activeNotifications
            .firstOrNull { it.id == binding.carNotificationId }
        val action = copy?.notification?.let { notification ->
            val visible = (0 until NotificationCompat.getActionCount(notification))
                .mapNotNull { NotificationCompat.getAction(notification, it) }
            (visible + NotificationCompat.getInvisibleActions(notification)).firstOrNull {
                it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY && !it.remoteInputs.isNullOrEmpty()
            }
        }
        val target = action?.actionIntent
        val inputs = action?.remoteInputs
        if (target == null || inputs == null) {
            Log.w(TAG, "refused: the car copy has no reply action")
            return
        }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, Bundle().apply { putCharSequence(CarNotificationPublisher.REPLY_KEY, text) })
        target.send(context, 0, fill)
        if (repeat) target.send(context, 0, fill)
        Log.i(TAG, "sent car reply")
    }

    /**
     * The binding for one fake Slack conversation. A notification key reads
     * "user|package|id|tag|uid", and the fake app uses the conversation's hash
     * code as its notification id.
     */
    private fun fixtureBinding(conversation: String) = BridgeState.registry.findBinding { binding ->
        val parts = binding.notificationKey.split("|")
        parts.size >= 3 &&
            parts[1] == BuildConfig.FIXTURE_PACKAGE &&
            parts[2] == conversation.hashCode().toString()
    }

    private companion object {
        const val TAG = "FlackDebug"
    }
}
