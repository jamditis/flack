package tech.amditis.flack

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import tech.amditis.flack.core.Conversation
import tech.amditis.flack.core.ApiRouteResolver
import tech.amditis.flack.core.Message
import tech.amditis.flack.core.ReplyActionFacts
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
        // This field now chooses the API conversation; a change expires the old reply.
        add(routeFields.shortcutId)
        add(ApiRouteResolver.workspaceIds(routeFields).sorted())
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
        val facts = SourceFacts(
            packageName = sbn.packageName,
            userId = sbn.user.hashCode(),
            notificationKey = sbn.key,
            isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            conversation = conversation(notification),
            replyActions = actions.map { replyFacts(it, sbn.uid) },
        )
        return ParsedNotification(facts, actions.map { it.actionIntent }, routeFields(sbn))
    }

    private fun routeFields(sbn: StatusBarNotification): RouteFields {
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
        val sender = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?.messages?.lastOrNull { it.person != null && !it.text.isNullOrBlank() }?.person
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

    private fun conversation(notification: Notification): Conversation? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
            ?: return plainConversation(notification)
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

    private fun plainConversation(notification: Notification): Conversation? {
        // A title and text can describe a call or upload, not a chat message.
        // Only standard message notifications qualify for this read-only fallback.
        if (notification.category != Notification.CATEGORY_MESSAGE ||
            notification.flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0
        ) return null
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
            title = action.title?.toString(),
            semanticAction = action.semanticAction,
            allowGeneratedReplies = action.allowGeneratedReplies,
            resultKeys = action.remoteInputs.orEmpty().map { it.resultKey },
        )
    }
}
