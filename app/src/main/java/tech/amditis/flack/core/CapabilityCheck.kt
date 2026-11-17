package tech.amditis.flack.core

/** The official Slack Android app. Other packages are ignored before any content is read. */
const val SLACK_PACKAGE = "com.Slack"

/** Content-free reasons a notification or its reply is unavailable. */
enum class RejectReason {
    NOT_SLACK,
    EMPTY_SUMMARY,
    SUMMARY_WITH_CHILD,
    UNKNOWN_FORMAT,
    NO_CONVERSATION,
    NO_MESSAGES,
    UNKNOWN_CONVERSATION,
    NO_CONTEXT,
    NO_REPLY_ACTION,
    MULTIPLE_REPLY_ACTIONS,
    MULTIPLE_RESULT_KEYS,
    REPLY_OPENS_APP,
    REPLY_ACTION_IMMUTABLE,
    CREATOR_MISMATCH,
}

sealed interface Capability {
    data class Eligible(val actionIndex: Int, val resultKey: String) : Capability
    data class ReadOnly(val reason: RejectReason) : Capability
    data class Rejected(val reason: RejectReason) : Capability
}

/**
 * Decides whether a Slack notification can take a car reply.
 *
 * Text can be read even when routing is unknown. A button labelled Reply is not enough: the action
 * must take free-form text in the background and come from Slack itself.
 */
object CapabilityCheck {

    /**
     * [allowedSources] is Slack only in release builds. Debug builds add the
     * test-only fake Slack app, which has its own package and never ships.
     */
    fun evaluate(facts: SourceFacts, allowedSources: Set<String> = setOf(SLACK_PACKAGE)): Capability {
        if (facts.packageName !in allowedSources) return reject(RejectReason.NOT_SLACK)
        val conversation = facts.conversation ?: return reject(RejectReason.NO_CONVERSATION)
        if (conversation.messages.isEmpty()) return reject(RejectReason.NO_MESSAGES)
        if (contextLabel(conversation) == null) return reject(RejectReason.NO_CONTEXT)
        // A group sender alone does not identify the destination. Unknown formats
        // (including plain text notifications) are readable but cannot take a reply.
        if (conversation.isGroup == null) return readOnly(RejectReason.UNKNOWN_CONVERSATION)
        if (conversation.isGroup && conversation.title.isNullOrBlank()) return readOnly(RejectReason.NO_CONTEXT)

        // Prefer Android's explicit reply semantic. Slack also ships actions with
        // no semantic: accept the observed plain Reply label. A thread action needs
        // an explicit reply semantic; the probe has not verified a thread marker.
        // The original PendingIntent supplies the destination in either case.
        val inputs = facts.replyActions.withIndex().filter { it.value.freeFormResultKeys.isNotEmpty() }
        val semanticReplies = inputs.filter { it.value.semanticAction == SEMANTIC_ACTION_REPLY }
        val candidates = semanticReplies.ifEmpty {
            inputs.filter {
                facts.packageName == SLACK_PACKAGE && isSlackPlainReply(it.value)
            }
        }
        if (candidates.isEmpty()) return readOnly(RejectReason.NO_REPLY_ACTION)
        if (candidates.size > 1) return readOnly(RejectReason.MULTIPLE_REPLY_ACTIONS)
        val (index, action) = candidates.single()
        if (action.freeFormResultKeys.size > 1) return readOnly(RejectReason.MULTIPLE_RESULT_KEYS)
        // Slack's observed Reply has RemoteInput and the default-true UI hint.
        // That hint alone does not turn a broadcast/service action into an activity.
        val slackPlainReply = facts.packageName == SLACK_PACKAGE && isSlackPlainReply(action)
        if (action.launchesActivity || (action.showsUserInterface && !slackPlainReply)) {
            return readOnly(RejectReason.REPLY_OPENS_APP)
        }
        if (action.isImmutable) return readOnly(RejectReason.REPLY_ACTION_IMMUTABLE)
        if (action.creatorPackage != facts.packageName ||
            action.creatorUid == null ||
            action.creatorUid != action.sourceUid
        ) {
            return readOnly(RejectReason.CREATOR_MISMATCH)
        }
        return Capability.Eligible(index, action.freeFormResultKeys.single())
    }

    /**
     * What the driver hears to identify the conversation: the title, or else the
     * newest incoming sender. Null when neither exists.
     */
    fun contextLabel(conversation: Conversation): String? =
        conversation.title?.takeIf { it.isNotBlank() }
            ?: conversation.messages.lastOrNull { !it.fromSelf }?.senderName?.takeIf { it.isNotBlank() }

    private fun reject(reason: RejectReason) = Capability.Rejected(reason)
    private fun readOnly(reason: RejectReason) = Capability.ReadOnly(reason)

    private fun isSlackPlainReply(action: ReplyActionFacts): Boolean =
        action.semanticAction == SEMANTIC_ACTION_NONE && action.title?.trim().equals("Reply", ignoreCase = true)

    // Android's Notification.Action.SEMANTIC_ACTION_REPLY. Core has no Android dependency.
    private const val SEMANTIC_ACTION_REPLY = 1
    private const val SEMANTIC_ACTION_NONE = 0
}
