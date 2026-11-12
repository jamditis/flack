package tech.amditis.slackcarbridge

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import tech.amditis.slackcarbridge.core.ConsumeResult

/**
 * Receives the car's reply and mark-as-read actions.
 *
 * A reply is sent to Slack's original reply action once, under Slack's own
 * RemoteInput key. Nothing is retried, and success means "handed to Slack",
 * never "delivered" (SPEC.md section 8).
 */
class CarActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CarNotificationPublisher.ACTION_REPLY -> reply(context, intent)
            // Local car acknowledgement only. Slack's read state is not touched.
            CarNotificationPublisher.ACTION_MARK_READ -> BridgeState.countAcknowledged()
        }
    }

    private fun reply(context: Context, intent: Intent) {
        val token = intent.getStringExtra(CarNotificationPublisher.EXTRA_TOKEN) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(CarNotificationPublisher.REPLY_KEY)
            ?.toString()
            ?.trim()
        when (val result = BridgeState.registry.consume(token, text)) {
            is ConsumeResult.Rejected -> BridgeState.countReplyRejection(result.reason)
            is ConsumeResult.Dispatch -> {
                val key = result.binding.resultKey
                val fillIn = Intent()
                RemoteInput.addResultsToIntent(
                    arrayOf(RemoteInput.Builder(key).build()),
                    fillIn,
                    Bundle().apply { putCharSequence(key, result.text) },
                )
                RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
                val status = try {
                    result.binding.action.send(context, 0, fillIn)
                    HandoffStatus.HANDED_TO_SLACK
                } catch (_: PendingIntent.CanceledException) {
                    HandoffStatus.HANDOFF_FAILED
                }
                BridgeState.recordHandoff(status)
            }
        }
    }
}
