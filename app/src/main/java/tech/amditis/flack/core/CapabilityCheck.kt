package tech.amditis.flack.core

/** The official Slack Android app. Other packages are ignored before any content is read. */
const val SLACK_PACKAGE = "com.Slack"

/** Content-free reasons a notification or its reply is unavailable. */
enum class RejectReason {
    NOT_SLACK,
    GROUP_SUMMARY,
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
        if (facts.isGroupSummary) return reject(RejectReason.GROUP_SUMMARY)
        val conversation = facts.conversation ?: return reject(RejectReason.NO_CONVERSATION)
        if (conversation.messages.isEmpty()) return reject(RejectReason.NO_MESSAGES)
        if (contextLabel(conversation) == null) return reject(RejectReason.NO_CONTEXT)
        // A group sender alone does not identify the destination. Unknown formats
        // (including plain text notifications) are readable but cannot take a reply.
        if (conversation.isGroup == null) return readOnly(RejectReason.UNKNOWN_CONVERSATION)
        if (conversation.isGroup && conversation.title.isNullOrBlank()) return readOnly(RejectReason.NO_CONTEXT)

        val candidates = facts.replyActions.withIndex().filter { it.value.freeFormResultKeys.isNotEmpty() }
        if (candidates.isEmpty()) return readOnly(RejectReason.NO_REPLY_ACTION)
        // Two text actions (for example "reply" and "reply in thread") are ambiguous.
        if (candidates.size > 1) return readOnly(RejectReason.MULTIPLE_REPLY_ACTIONS)

        val (index, action) = candidates.single()
        if (action.freeFormResultKeys.size > 1) return readOnly(RejectReason.MULTIPLE_RESULT_KEYS)
        if (action.showsUserInterface) return readOnly(RejectReason.REPLY_OPENS_APP)
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
}
