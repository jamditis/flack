package tech.amditis.flack.fixture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * Posts Slack-shaped notifications: MessagingStyle, an explicit group flag and an
 * inline reply that runs in the background. Every person and message is invented.
 */
object FixtureMessages {
    const val TAG = "FlackFixture"
    const val REPLY_KEY = "fixture_reply_text"
    const val EXTRA_CONVERSATION = "conversation"
    private const val CHANNEL = "fixture"

    private data class Line(val sender: String?, val text: String, val time: Long)

    /** In-memory history per conversation, so a reply shows up the way Slack echoes it. */
    private val history = HashMap<String, MutableList<Line>>()
    private val kinds = HashMap<String, Kind>()

    @Volatile var lastReply: String? = null
        private set

    enum class Kind { DM, GROUP, CHANNEL, PRIVATE_CHANNEL, THREAD, UNKNOWN, TWO_ACTIONS }

    @Synchronized
    fun post(context: Context, conversation: String, sender: String, text: String, kind: Kind) {
        kinds[conversation] = kind
        history.getOrPut(conversation) { mutableListOf() }.add(Line(sender, text, System.currentTimeMillis()))
        show(context, conversation, kind)
    }

    @Synchronized
    fun recordReply(context: Context, conversation: String, text: String) {
        lastReply = text
        // Test-only app: the text is invented, and the emulator test reads it from logcat.
        Log.i(TAG, "reply conversation=$conversation text=$text")
        history.getOrPut(conversation) { mutableListOf() }.add(Line(null, text, System.currentTimeMillis()))
        show(context, conversation, kinds[conversation] ?: return)
    }

    @Synchronized
    fun clear(context: Context) {
        history.clear()
        kinds.clear()
        lastReply = null
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun show(context: Context, conversation: String, kind: Kind) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH))

        val me = Person.Builder().setName(context.getString(R.string.me)).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
        val title = when (kind) {
            Kind.DM -> null
            Kind.GROUP -> "Riley Example, Sam Example"
            Kind.CHANNEL -> "#paper-kites"
            Kind.PRIVATE_CHANNEL -> "Private paper room"
            Kind.THREAD -> "#paper-kites · $conversation"
            Kind.UNKNOWN, Kind.TWO_ACTIONS -> "Paper room"
        }
        style.setConversationTitle(title).setGroupConversation(kind != Kind.DM)
        history[conversation].orEmpty().forEach { line ->
            val person = line.sender?.let { Person.Builder().setName(it).setKey("sender:$it").build() }
            style.addMessage(line.text, line.time, person)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_fixture)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .addAction(replyAction(context, conversation, "reply", R.string.reply))
        if (kind == Kind.TWO_ACTIONS) builder.addAction(replyAction(context, "$conversation-thread", "thread", R.string.reply_in_thread))

        val notification = builder.build()
        if (kind == Kind.UNKNOWN) notification.extras.remove(android.app.Notification.EXTRA_IS_GROUP_CONVERSATION)
        try {
            NotificationManagerCompat.from(context).notify(conversation.hashCode(), notification)
        } catch (_: SecurityException) {
            Log.w(TAG, "notification permission missing")
        }
    }

    private fun replyAction(context: Context, conversation: String, kind: String, label: Int): NotificationCompat.Action {
        val intent = Intent(context, FixtureReplyReceiver::class.java)
            .setAction("tech.amditis.flack.fixture.REPLY.$kind")
            .setData(Uri.Builder().scheme("fixture").authority("reply").appendPath(conversation).appendPath(kind).build())
            .putExtra(EXTRA_CONVERSATION, conversation)
        val pending = PendingIntent.getBroadcast(context, conversation.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        return NotificationCompat.Action.Builder(R.drawable.ic_fixture, context.getString(label), pending)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel(context.getString(label)).build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }
}
