package tech.amditis.flack.fixture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Plays Slack's part when a reply arrives: records it and echoes it into the conversation. */
class FixtureReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conversation = intent.getStringExtra(FixtureMessages.EXTRA_CONVERSATION) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(FixtureMessages.REPLY_KEY)?.toString() ?: return
        FixtureMessages.recordReply(context, conversation, text)
    }
}
