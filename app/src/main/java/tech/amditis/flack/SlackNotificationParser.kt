package tech.amditis.flack

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import tech.amditis.flack.core.Conversation
import tech.amditis.flack.core.ApiRouteResolver
import tech.amditis.flack.core.Message
import tech.amditis.flack.core.ReplyActionFacts
import tech.amditis.flack.core.RouteFieldProbe
import tech.amditis.flack.core.RouteFields
import tech.amditis.flack.core.SourceFacts
import java.security.MessageDigest

/** Facts for the core rules, plus the Android handles they refer to. */
class ParsedNotification(
    val facts: SourceFacts,
    /** Same order as [SourceFacts.replyActions]. */
    val actionIntents: List<PendingIntent?>,
    /** Identifier-bearing fields for the route field probe. Memory only. */
    val routeFields: RouteFields = RouteFields(),
    val hasMessagingMessages: Boolean = false,
    /** Android grouping affects the filter. It is separate from message content identity. */
    val groupKey: String? = null,
    /** Android gave a message signal, even if its payload was unreadable. */
    val hasMessageSignal: Boolean = false,
) {
    /** Cross-key identity for a summary and child. Exact observed fields, memory only. */
    data class MessageIdentity(
        val conversationId: String?, val workspaceIds: Set<String>, val title: String?,
        val isGroup: Boolean?, val senderKey: String?, val message: Message,
    )

    val newestMessageIdentity: MessageIdentity?
        get() = facts.conversation?.let { conversation ->
            conversation.messages.lastOrNull()?.let {
                MessageIdentity(routeFields.shortcutId, ApiRouteResolver.workspaceIds(routeFields), conversation.title,
                    conversation.isGroup, routeFields.senderKey, it)
            }
        }

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
        // This field now chooses the API conversation; a change expires the old reply.
        add(routeFields.shortcutId)
        add(ApiRouteResolver.workspaceIds(routeFields).sorted())
        add(facts.hasThreadMarker)
        conversation?.messages?.forEach {
            add(it.senderName); add(it.fromSelf); add(it.text); add(it.timestampMillis)
        }
        facts.replyActions.forEach { add(it) }
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
        val replyActions = actions.map { replyFacts(it, sbn.uid) }
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
        val messaging = style?.let { conversation(notification, it) }
        val hasMessages = messaging?.messages?.isNotEmpty() == true
        val plainMessage = !hasMessages && canReadPlainText(notification,
            style != null || replyActions.any { it.freeFormResultKeys.isNotEmpty() })
        val facts = SourceFacts(
            packageName = sbn.packageName,
            userId = sbn.user.hashCode(),
            notificationKey = sbn.key,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            conversation = if (plainMessage) plainConversation(notification) else messaging?.takeIf { hasMessages },
            replyActions = replyActions,
            hasThreadMarker = notification.extras.keySet().any { RouteFieldProbe.isThreadMarkerKey(it) },
        )
        val group = sbn.groupKey.takeIf { notification.group != null || sbn.overrideGroupKey != null }
        return ParsedNotification(facts, actions.map { it.actionIntent }, routeFields(sbn, style), hasMessages, group,
            hasMessageSignal = hasMessages || plainMessage)
    }

    private fun routeFields(sbn: StatusBarNotification, style: NotificationCompat.MessagingStyle?): RouteFields {
        val notification = sbn.notification
        val extras = notification.extras
        val strings = mutableListOf<Pair<String, String>>()
        var other = 0
        // Android's own keys hold the title and message text, not identifiers.
        extras.keySet().filterNot { it.startsWith("android.") }.sorted().forEach { key ->
            @Suppress("DEPRECATION")
            when (val value = extras.get(key)) {
                is CharSequence -> strings += key to value.toString()
                null -> Unit
                else -> other++
            }
        }
        // The newest incoming sender's Person key and URI may carry a Slack user ID. Same
        // message as the probe's context: the newest incoming one with text, as conversation() keeps.
        val sender = style?.messages?.lastOrNull { it.person != null && !it.text.isNullOrBlank() }?.person
        return RouteFields(
            senderKey = sender?.key,
            senderUri = sender?.uri,
            shortcutId = notification.shortcutId,
            locusId = notification.locusId?.id,
            tag = sbn.tag,
            extras = strings,
            otherExtras = other,
            hasSubText = !extras.getCharSequence(Notification.EXTRA_SUB_TEXT).isNullOrEmpty(),
        )
    }

    private fun conversation(notification: Notification, style: NotificationCompat.MessagingStyle): Conversation {
        val title = style.conversationTitle?.toString()
        val extras = notification.extras
        // Without the flag the destination shape is unknown: read-only.
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

    private fun canReadPlainText(notification: Notification, hasMessageSignal: Boolean): Boolean {
        // A title and text can describe a call or upload, not a chat message.
        // Trust Android's message category/style or a free-form input, then keep the copy read-only.
        if (!hasMessageSignal && notification.category != Notification.CATEGORY_MESSAGE) return false
        if (notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        return when (notification.category) {
            Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL, Notification.CATEGORY_PROGRESS,
            Notification.CATEGORY_SERVICE -> false
            else -> true
        }
    }

    private fun plainConversation(notification: Notification): Conversation? {
        val extras = notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }
            ?: return null
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        return Conversation(title, null, listOf(Message(title, false, text, notification.`when`)))
    }

    private fun replyFacts(action: Notification.Action, sourceUid: Int): ReplyActionFacts {
        val intent = action.actionIntent
        val freeFormKeys = action.remoteInputs.orEmpty()
            .filter { it.allowFreeFormInput }
            .map { it.resultKey }
        // AndroidX defaults this hint to true, even on a RemoteInput broadcast.
        // Keep the actual PendingIntent kind separate so an activity is always refused.
        val explicitUi = action.extras.containsKey(EXTRA_SHOWS_USER_INTERFACE) &&
            action.extras.getBoolean(EXTRA_SHOWS_USER_INTERFACE)
        return ReplyActionFacts(
            creatorPackage = intent?.creatorPackage,
            creatorUid = intent?.creatorUid,
            sourceUid = sourceUid,
            freeFormResultKeys = freeFormKeys,
            showsUserInterface = explicitUi,
            isImmutable = intent == null || intent.isImmutable,
            title = action.title?.toString(),
            semanticAction = action.semanticAction,
            allowGeneratedReplies = action.allowGeneratedReplies,
            resultKeys = action.remoteInputs.orEmpty().map { it.resultKey },
            launchesActivity = intent == null || intent.isActivity,
        )
    }
}
