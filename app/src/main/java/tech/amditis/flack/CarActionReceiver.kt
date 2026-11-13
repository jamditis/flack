package tech.amditis.flack

import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import tech.amditis.flack.core.Binding
import tech.amditis.flack.core.ConsumeRejection
import tech.amditis.flack.core.ConsumeResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Receives the car's reply and mark-as-read actions.
 *
 * A reply is sent once: to Slack's original reply action under Slack's own
 * RemoteInput key, where success means "handed to Slack", or with
 * chat.postMessage to the copy's certain API destination, where only Slack's
 * answer confirms it. Nothing is retried (SPEC.md section 8).
 */
class CarActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CarNotificationPublisher.ACTION_REPLY -> reply(context, intent)
            // Local car acknowledgement only. Slack's read state is not touched.
            CarNotificationPublisher.ACTION_MARK_READ -> {
                val token = intent.getStringExtra(CarNotificationPublisher.EXTRA_TOKEN) ?: return
                if (BridgeState.registry.findBinding { it.token == token } != null) BridgeState.acknowledgeCopy(token)
            }
        }
    }

    private fun reply(context: Context, intent: Intent) {
        val token = intent.getStringExtra(CarNotificationPublisher.EXTRA_TOKEN) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(CarNotificationPublisher.REPLY_KEY)
            ?.toString()
            ?.trim()
        when (val result = BridgeState.registry.consume(token, text)) {
            is ConsumeResult.Rejected -> {
                BridgeState.countReplyRejection(result.reason)
                // Repost so the host stops waiting. Keep the reply action only if the
                // token is still unused (empty or too-long text does not use it).
                result.binding?.let { binding ->
                    val conversation = BridgeState.conversation(binding.carNotificationId)
                    if (conversation != null && BridgeState.registry.isCurrent(binding)) {
                        val stillUsable = result.reason == ConsumeRejection.EMPTY || result.reason == ConsumeRejection.TOO_LONG
                        CarNotificationPublisher.post(context, binding, conversation, alert = false, replyEnabled = stillUsable)
                    }
                }
            }
            is ConsumeResult.Dispatch -> {
                if (result.binding.apiRoute != null) {
                    postWithApi(context, result.binding, result.text)
                    return
                }
                val key = result.binding.resultKey ?: return
                val action = result.binding.action ?: return
                val fillIn = Intent()
                RemoteInput.addResultsToIntent(
                    arrayOf(RemoteInput.Builder(key).build()),
                    fillIn,
                    Bundle().apply { putCharSequence(key, result.text) },
                )
                RemoteInput.setResultsSource(fillIn, RemoteInput.SOURCE_FREE_FORM_INPUT)
                val status = try {
                    action.send(context, 0, fillIn)
                    HandoffStatus.HANDED_TO_SLACK
                } catch (_: PendingIntent.CanceledException) {
                    HandoffStatus.HANDOFF_FAILED
                }
                BridgeState.recordHandoff(status)
                repostSpent(context, result.binding, if (status == HandoffStatus.HANDED_TO_SLACK) result.text else null)
            }
        }
    }

    /**
     * One chat.postMessage call on a background thread. The token is already
     * consumed, so a second tap cannot send again. The broadcast is not held open
     * with goAsync: a token refresh and its revokes can outlast the broadcast
     * deadline, and the bound notification listener keeps the process running.
     */
    private fun postWithApi(context: Context, binding: Binding<PendingIntent>, text: String) {
        val destination = binding.apiRoute ?: return
        val app = context.applicationContext
        API_WORKER.execute {
            val result = SlackReplySender(SlackSession.accounts(app), SlackHttp).send(destination, text)
            BridgeState.recordHandoff(result.status, result.slackCode)
            val posted = result.status == HandoffStatus.POSTED || result.status == HandoffStatus.POSTED_PLACE_UNCONFIRMED
            // The listener changes copies on the main thread. Check and repost there too,
            // so a copy withdrawn or replaced meanwhile is never posted again.
            MAIN.post { repostSpent(app, binding, if (posted) text else null) }
        }
    }

    /**
     * Android Auto waits for the copy to change after a reply. Repost it without a
     * reply action, since this token is used up. Slack's own update, if any, replaces
     * it with a fresh token.
     */
    private fun repostSpent(context: Context, binding: Binding<PendingIntent>, sentText: String?) {
        val conversation = BridgeState.conversation(binding.carNotificationId)
        // Skip if Slack already replaced this generation with a newer one.
        if (conversation != null && BridgeState.registry.isCurrent(binding)) {
            CarNotificationPublisher.post(context, binding, conversation, alert = false, replyEnabled = false, sentText = sentText)
        }
    }

    private companion object {
        /** One API reply at a time. */
        val API_WORKER: ExecutorService = Executors.newSingleThreadExecutor()
        val MAIN = Handler(Looper.getMainLooper())
    }
}
