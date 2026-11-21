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
    THREAD_ROUTE_UNKNOWN,
    SHARED_REPLY_ACTION,
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

        // Slack's two semantic-0 buttons name their placements. Use the original
        // conversation action by default and the original thread action with evidence.
        val inputs = facts.replyActions.withIndex().filter { it.value.freeFormResultKeys.isNotEmpty() }
        val semanticReplies = inputs.filter { it.value.semanticAction == SEMANTIC_ACTION_REPLY }
        val slack = facts.packageName == SLACK_PACKAGE
        val plainReplies = inputs.filter { isSlackPlainReply(it.value) }
        val slackThreadReplies = inputs.filter { isSlackUnspecifiedThreadReply(it.value) }
        val observedPair = inputs.size == 2 && plainReplies.size == 1 && slackThreadReplies.size == 1
        val candidates = when {
            slack && facts.hasThreadEvidence -> inputs.filter {
                isThreadReply(it.value) && it.value.semanticAction in setOf(SEMANTIC_ACTION_NONE, SEMANTIC_ACTION_REPLY)
            }
            semanticReplies.isNotEmpty() -> semanticReplies
            slack && !facts.hasThreadMarker && (inputs.size == 1 || observedPair) -> plainReplies
            else -> emptyList()
        }
        if (candidates.isEmpty()) {
            val unknownPlacement = slack && (facts.hasThreadMarker || inputs.size > 1 || facts.replyActions.any(::isThreadReply))
            return readOnly(if (unknownPlacement) RejectReason.THREAD_ROUTE_UNKNOWN else RejectReason.NO_REPLY_ACTION)
        }
        if (candidates.size > 1) return readOnly(RejectReason.MULTIPLE_REPLY_ACTIONS)
        val (index, action) = candidates.single()
        if (facts.hasThreadMarker && !isThreadReply(action)) return readOnly(RejectReason.THREAD_ROUTE_UNKNOWN)
        if (action.freeFormResultKeys.size > 1) return readOnly(RejectReason.MULTIPLE_RESULT_KEYS)
        // Slack's observed Reply has RemoteInput and the default-true UI hint.
        // That hint alone does not turn a broadcast/service action into an activity.
        val slackBackgroundReply = slack && (isSlackPlainReply(action) ||
            (facts.hasThreadEvidence && isSlackUnspecifiedThreadReply(action)))
        if (action.launchesActivity || (action.showsUserInterface && !slackBackgroundReply)) {
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

    private fun isSlackUnspecifiedThreadReply(action: ReplyActionFacts): Boolean =
        action.semanticAction == SEMANTIC_ACTION_NONE && isThreadReply(action)

    fun isThreadReply(action: ReplyActionFacts): Boolean {
        val title = action.title?.trim()
        return title.equals("Reply in thread", ignoreCase = true) || title.equals("Reply to thread", ignoreCase = true)
    }

    // Android's Notification.Action.SEMANTIC_ACTION_REPLY. Core has no Android dependency.
    private const val SEMANTIC_ACTION_REPLY = 1
    private const val SEMANTIC_ACTION_NONE = 0
}
