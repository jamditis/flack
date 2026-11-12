package tech.amditis.slackcarbridge.core

/**
 * What the Android parser observed about one source notification.
 *
 * This type has no Android imports so the eligibility and routing rules can be
 * tested on the JVM. Message content lives only in memory.
 */
data class SourceFacts(
    val packageName: String,
    val userId: Int,
    val notificationKey: String,
    val isGroupSummary: Boolean,
    /** Null when the notification has no MessagingStyle. */
    val conversation: Conversation?,
    val replyActions: List<ReplyActionFacts>,
)

data class Conversation(
    /** The title Slack shows, for context only. Never a routing key. */
    val title: String?,
    /** True for group conversations and channels. Null when Slack did not say. */
    val isGroup: Boolean?,
    val messages: List<Message>,
)

data class Message(
    val senderName: String?,
    /** True when Slack marks the message as sent by the phone's user. */
    val fromSelf: Boolean,
    val text: String,
    val timestampMillis: Long,
)

data class ReplyActionFacts(
    /** The package that created the action's PendingIntent. */
    val creatorPackage: String?,
    val creatorUid: Int?,
    val sourceUid: Int?,
    /** RemoteInput result keys that accept free-form text. */
    val freeFormResultKeys: List<String>,
    val showsUserInterface: Boolean,
    /** True when the PendingIntent is immutable, so it cannot take reply text. */
    val isImmutable: Boolean,
)
