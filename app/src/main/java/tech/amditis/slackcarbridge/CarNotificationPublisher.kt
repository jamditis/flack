package tech.amditis.slackcarbridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import tech.amditis.slackcarbridge.core.Binding
import tech.amditis.slackcarbridge.core.CapabilityCheck
import tech.amditis.slackcarbridge.core.Conversation

/**
 * Posts the car copy of a Slack conversation as a MessagingStyle notification
 * with a reply and a mark-as-read action, the shape Android Auto reads aloud.
 */
object CarNotificationPublisher {
    private const val CHANNEL_ID = "car_messages"
    const val REPLY_KEY = "bridge_reply"
    const val EXTRA_TOKEN = "token"
    const val ACTION_REPLY = "tech.amditis.slackcarbridge.REPLY"
    const val ACTION_MARK_READ = "tech.amditis.slackcarbridge.MARK_READ"

    fun ensureChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.channel_description)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun post(context: Context, binding: Binding<PendingIntent>, conversation: Conversation, alert: Boolean) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        ensureChannel(context)

        val me = Person.Builder().setName(context.getString(R.string.you)).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(conversation.title)
            .setGroupConversation(false)
        conversation.messages.takeLast(MAX_MESSAGES).forEach { message ->
            val sender = if (message.fromSelf) null else Person.Builder().setName(message.senderName).build()
            style.addMessage(message.text, message.timestampMillis, sender)
        }

        val remoteInput = RemoteInput.Builder(REPLY_KEY).setLabel(context.getString(R.string.reply_label)).build()
        // Mutable so Android Auto can add the dictated text. Explicit target, so nothing else can receive it.
        val replyIntent = PendingIntent.getBroadcast(
            context,
            binding.carNotificationId,
            actionIntent(context, ACTION_REPLY, binding.token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val readIntent = PendingIntent.getBroadcast(
            context,
            binding.carNotificationId,
            actionIntent(context, ACTION_MARK_READ, binding.token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.reply_label), replyIntent)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val markRead = NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.mark_read), readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(CapabilityCheck.contextLabel(conversation))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSilent(!alert)
            .addAction(reply)
            .addInvisibleAction(markRead)
            .build()
        try {
            manager.notify(binding.carNotificationId, notification)
        } catch (_: SecurityException) {
            // Notification permission was revoked between the check and the post.
        }
    }

    fun cancel(context: Context, bindings: Collection<Binding<PendingIntent>>) {
        val manager = NotificationManagerCompat.from(context)
        bindings.forEach { manager.cancel(it.carNotificationId) }
    }

    private fun actionIntent(context: Context, action: String, token: String) =
        Intent(context, CarActionReceiver::class.java).setAction(action).putExtra(EXTRA_TOKEN, token)

    /** Provisional product limit from SPEC.md section 7. */
    private const val MAX_MESSAGES = 10
}
