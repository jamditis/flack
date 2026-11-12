package tech.amditis.slackcarbridge

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import tech.amditis.slackcarbridge.core.Conversation
import tech.amditis.slackcarbridge.core.Message
import tech.amditis.slackcarbridge.core.ReplyActionFacts
import tech.amditis.slackcarbridge.core.SourceFacts
import java.security.MessageDigest

/** Facts for the core rules, plus the Android handles they refer to. */
class ParsedNotification(
    val facts: SourceFacts,
    /** Same order as [SourceFacts.replyActions]. */
    val actionIntents: List<PendingIntent?>,
) {
    /**
     * Identifies this notification's content. Equal fingerprints mean a repeated
     * callback, not a new message. Kept in memory only.
     */
    val fingerprint: String by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        fun add(value: Any?) {
            digest.update(value.toString().toByteArray())
            digest.update(0)
        }
        val conversation = facts.conversation
        add(conversation?.title)
        add(conversation?.isGroup)
        conversation?.messages?.forEach {
            add(it.senderName); add(it.fromSelf); add(it.text); add(it.timestampMillis)
        }
        facts.replyActions.forEach { add(it.freeFormResultKeys); add(it.showsUserInterface) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Reads the parts of a Slack notification the core rules need. The caller must
 * check the package first, so no other app's content is ever read.
 */
object SlackNotificationParser {

    private const val EXTRA_SHOWS_USER_INTERFACE = "android.support.action.showsUserInterface"

    fun parse(sbn: StatusBarNotification): ParsedNotification {
        val notification = sbn.notification
        val actions = notification.actions.orEmpty().toList()
        val facts = SourceFacts(
            packageName = sbn.packageName,
            userId = sbn.user.hashCode(),
            notificationKey = sbn.key,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            conversation = conversation(notification),
            replyActions = actions.map { replyFacts(it, sbn.uid) },
        )
        return ParsedNotification(facts, actions.map { it.actionIntent })
    }

    private fun conversation(notification: Notification): Conversation? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?: return null
        val title = style.conversationTitle?.toString()
        val extras = notification.extras
        // Without the flag, a channel message could pass for a DM from its sender.
        // Fail closed: G1 records whether Slack sets it on direct messages.
        val isGroup = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION)) {
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        } else {
            null
        }
        val messages = style.messages.mapNotNull { message ->
            val text = message.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            // MessagingStyle marks the phone user's own messages with no sender.
            val person = message.person
            Message(
                senderName = person?.name?.toString(),
                fromSelf = person == null,
                text = text,
                timestampMillis = message.timestamp,
            )
        }
        return Conversation(title, isGroup, messages)
    }

    private fun replyFacts(action: Notification.Action, sourceUid: Int): ReplyActionFacts {
        val intent = action.actionIntent
        val freeFormKeys = action.remoteInputs.orEmpty()
            .filter { it.allowFreeFormInput }
            .map { it.resultKey }
        // An action that starts an activity opens the app, which the driver cannot use.
        val explicitUi = action.extras.containsKey(EXTRA_SHOWS_USER_INTERFACE) &&
            action.extras.getBoolean(EXTRA_SHOWS_USER_INTERFACE)
        return ReplyActionFacts(
            creatorPackage = intent?.creatorPackage,
            creatorUid = intent?.creatorUid,
            sourceUid = sourceUid,
            freeFormResultKeys = freeFormKeys,
            showsUserInterface = intent == null || intent.isActivity || explicitUi,
            isImmutable = intent == null || intent.isImmutable,
        )
    }
}
