package tech.amditis.flack

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.annotation.RequiresPermission
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import tech.amditis.flack.core.Binding
import tech.amditis.flack.core.CapabilityCheck
import tech.amditis.flack.core.Conversation
import tech.amditis.flack.core.RejectReason

/** Fixed labels only; exception messages can contain private source data. */
enum class CopyPostOutcome { POSTED, NOTIFICATIONS_DISABLED, CHANNEL_BLOCKED, CHANNEL_GROUP_BLOCKED, CHANNEL_MISSING, SECURITY_EXCEPTION }

data class CopyPostAttempt(
    val outcome: CopyPostOutcome,
    val channel: String,
    val notificationsEnabled: Boolean?,
    val importance: Int?,
    val groupBlocked: Boolean?,
    val exceptionClass: String?,
)

/** Display context retained only for the lifetime of the Android copy. */
data class CarCopyContent(val conversation: Conversation, val replyProblem: RejectReason?, val conversationReply: Boolean)

/**
 * Posts the car copy of a Slack conversation as a MessagingStyle notification
 * with a reply and a mark-as-read action, the shape Android Auto reads aloud.
 */
object CarNotificationPublisher {
    private const val CHANNEL_ID = "car_messages"
    // A new ID is required: Android keeps an installed channel's importance.
    private const val QUIET_CHANNEL_ID = "car_messages_quiet_v2"
    const val REPLY_KEY = "bridge_reply"
    const val EXTRA_TOKEN = "token"
    const val ACTION_REPLY = "tech.amditis.flack.REPLY"
    const val ACTION_MARK_READ = "tech.amditis.flack.MARK_READ"
    const val ACTION_DISMISS_REPLY_STATUS = "tech.amditis.flack.DISMISS_REPLY_STATUS"
    const val ACTION_REPLY_TO_STATUS = "tech.amditis.flack.REPLY_TO_STATUS"
    private const val REPLY_STATUS_ID = 0
    private var replyStatusMessage: Int? = null

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH)
        channel.description = context.getString(R.string.channel_description)
        manager.createNotificationChannel(channel)
        // Keep car copies eligible for alerts in either mode. Phone-side quieting
        // belongs on the notification; it must not lower the channel importance.
        // Carry an old channel block forward only when creating its replacement.
        val quietImportance = if (manager.getNotificationChannel(QUIET_CHANNEL_ID) == null &&
            manager.getNotificationChannel("car_messages_quiet")?.importance == NotificationManager.IMPORTANCE_NONE
        ) NotificationManager.IMPORTANCE_NONE else NotificationManager.IMPORTANCE_HIGH
        val quiet = NotificationChannel(QUIET_CHANNEL_ID, context.getString(R.string.quiet_channel_name), quietImportance)
        quiet.description = context.getString(R.string.quiet_channel_description)
        quiet.setSound(null, null)
        quiet.enableVibration(false)
        quiet.setShowBadge(false)
        manager.createNotificationChannel(quiet)
        manager.deleteNotificationChannel("car_messages_quiet")
    }

    /**
     * Posts or replaces the car copy. With [replyEnabled] false the copy has no
     * reply action, which is how it looks after its token is used. [sentText] adds
     * the user's own reply to the copy. Returns false when nothing was posted.
     */
    fun post(
        context: Context,
        binding: Binding<PendingIntent>,
        content: CarCopyContent,
        alert: Boolean,
        replyEnabled: Boolean = binding.canReply,
        sentText: String? = null,
    ): Boolean {
        val channelId = if (BridgeState.hidesPhoneCopies(context)) QUIET_CHANNEL_ID else CHANNEL_ID
        var enabled: Boolean? = null
        var importance: Int? = null
        var groupBlocked: Boolean? = null
        var exceptionClass: String? = null
        val outcome = try {
            val manager = NotificationManagerCompat.from(context)
            enabled = manager.areNotificationsEnabled()
            ensureChannel(context)
            val channel = manager.getNotificationChannel(channelId)
            importance = channel?.importance
            groupBlocked = channel?.group?.let { manager.getNotificationChannelGroup(it)?.isBlocked } ?: false
            when {
                enabled == false -> CopyPostOutcome.NOTIFICATIONS_DISABLED
                channel == null -> CopyPostOutcome.CHANNEL_MISSING
                importance == NotificationManager.IMPORTANCE_NONE -> CopyPostOutcome.CHANNEL_BLOCKED
                groupBlocked == true -> CopyPostOutcome.CHANNEL_GROUP_BLOCKED
                else -> {
                    postCopy(context, binding, content, alert, replyEnabled, sentText, channelId)
                    CopyPostOutcome.POSTED
                }
            }
        } catch (error: SecurityException) {
            exceptionClass = error.javaClass.simpleName.take(80)
            CopyPostOutcome.SECURITY_EXCEPTION
        }
        BridgeState.recordPost(CopyPostAttempt(outcome, channelId, enabled, importance, groupBlocked, exceptionClass))
        if (outcome != CopyPostOutcome.POSTED) return false
        BridgeState.rememberCopyContent(binding.carNotificationId, content)
        BridgeState.recordCopy(binding.token, channelId)
        return true
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun postCopy(
        context: Context,
        binding: Binding<PendingIntent>,
        content: CarCopyContent,
        alert: Boolean,
        replyEnabled: Boolean,
        sentText: String?,
        channelId: String,
    ) {
        val manager = NotificationManagerCompat.from(context)
        val quiet = channelId == QUIET_CHANNEL_ID
        val (conversation, replyProblem, conversationReply) = content
        val canDispatch = replyEnabled && binding.canReply

        val me = Person.Builder().setName(context.getString(R.string.you)).setKey("self").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(conversation.title)
            .setGroupConversation(conversation.isGroup == true)
        val notice = when {
            replyEnabled && binding.apiRoute != null -> R.string.car_reply_main_dm
            canDispatch && conversationReply -> R.string.car_reply_conversation
            replyProblem == RejectReason.THREAD_ROUTE_UNKNOWN -> R.string.car_reply_thread_unknown
            replyProblem == RejectReason.SHARED_REPLY_ACTION -> R.string.car_reply_shared_action
            replyProblem != null -> R.string.car_reply_route_unknown
            else -> null
        }
        val messages = conversation.messages.takeLast(MAX_MESSAGES)
        val latestIncoming = messages.indexOfLast { !it.fromSelf }
        messages.forEachIndexed { index, message ->
            // Keys keep senders apart within this one conversation. They are never routing keys.
            val sender = if (message.fromSelf) null else Person.Builder().setName(message.senderName).setKey("sender:${message.senderName}").build()
            // The host reads MessagingStyle text. Label the cue so it is distinguishable from Slack's message.
            // The car preview shows one line, so Slack's text always comes first.
            val text = if (notice == null || index != latestIncoming) message.text
            else context.getString(R.string.car_message_with_flack_notice, message.text, context.getString(notice))
            style.addMessage(text, message.timestampMillis, sender)
        }
        // A local copy of what was handed to Slack, not proof of delivery.
        sentText?.let { style.addMessage(it, System.currentTimeMillis(), null as Person?) }

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
        val replyLabel = when {
            !canDispatch -> R.string.reply_unavailable_label
            conversationReply -> R.string.reply_conversation_label
            else -> R.string.reply_label
        }
        val reply = replyAction(context, replyIntent, replyLabel)
        val markRead = NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.mark_read), readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(CapabilityCheck.contextLabel(conversation))
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setColor(ContextCompat.getColor(context, R.color.flack_amber))
            .setSilent(quiet || !alert)
            // Only a distinct incoming message gets a new alerting post.
            .setOnlyAlertOnce(quiet || !alert)
            .setGroup("flack_car_messages")
            .addInvisibleAction(markRead)
        // Android Auto requires both actions. A reply without a binding produces
        // a local explanation; keep that action and quiet-mode actions off the phone.
        if (quiet || !canDispatch) builder.addInvisibleAction(reply) else builder.addAction(reply)
        manager.notify(binding.carNotificationId, builder.build())
    }

    private fun replyAction(context: Context, intent: PendingIntent, @StringRes labelId: Int): NotificationCompat.Action {
        val label = context.getString(labelId)
        return NotificationCompat.Action.Builder(R.drawable.ic_notification, label, intent)
            .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel(label).build())
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    /** Reply feedback can outlive its Slack copy. It contains no Slack or dictated text. */
    fun postReplyStatus(context: Context, @StringRes message: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = NotificationManagerCompat.from(context)
        val quiet = BridgeState.hidesPhoneCopies(context)
        val channel = if (quiet) QUIET_CHANNEL_ID else CHANNEL_ID
        val text = context.getString(message)
        val title = context.getString(R.string.car_reply_status_title)
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(context.getString(R.string.you)).build())
            .setConversationTitle(title)
            .setGroupConversation(false)
            .addMessage(text, System.currentTimeMillis(), Person.Builder().setName(context.getString(R.string.app_name)).build())
        val dismiss = PendingIntent.getBroadcast(context, REPLY_STATUS_ID,
            Intent(context, CarActionReceiver::class.java).setAction(ACTION_DISMISS_REPLY_STATUS), PendingIntent.FLAG_IMMUTABLE)
        val reply = PendingIntent.getBroadcast(context, REPLY_STATUS_ID,
            Intent(context, CarActionReceiver::class.java).setAction(ACTION_REPLY_TO_STATUS), PendingIntent.FLAG_MUTABLE)
        val markRead = NotificationCompat.Action.Builder(R.drawable.ic_notification, context.getString(R.string.mark_read), dismiss)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val notice = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setSilent(quiet)
            .addInvisibleAction(markRead)
            .addInvisibleAction(replyAction(context, reply, R.string.reply_unavailable_label))
            .build()
        try {
            ensureChannel(context)
            manager.notify(REPLY_STATUS_ID, notice)
            replyStatusMessage = message
        } catch (_: SecurityException) {
            // Permission can change between the check and the post. The phone report retains the result.
        }
    }

    fun dismissReplyStatus(context: Context) {
        NotificationManagerCompat.from(context).cancel(REPLY_STATUS_ID)
        replyStatusMessage = null
    }

    fun repeatReplyStatus(context: Context) {
        postReplyStatus(context, replyStatusMessage ?: R.string.car_reply_status_open_message)
    }

    fun cancel(context: Context, bindings: Collection<Binding<PendingIntent>>) {
        val manager = NotificationManagerCompat.from(context)
        bindings.forEach {
            manager.cancel(it.carNotificationId)
            BridgeState.forgetCopyContent(it.carNotificationId)
            BridgeState.updateHealth.forgetCopy(it.carNotificationId)
        }
    }

    // The token is part of the data URI, so each generation gets its own PendingIntent.
    // Extras alone would let FLAG_UPDATE_CURRENT rewrite a reply action Android Auto
    // still holds, and an old interaction would then spend the new generation's token.
    private fun actionIntent(context: Context, action: String, token: String) =
        Intent(context, CarActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.Builder().scheme("flack").authority("token").appendPath(token).build())
            .putExtra(EXTRA_TOKEN, token)

    /** Provisional product limit from SPEC.md section 7. */
    private const val MAX_MESSAGES = 10
}
