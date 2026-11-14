package tech.amditis.flack.core

enum class ConversationIdShape { MISSING, DM, CHANNEL, GROUP, UNKNOWN }
enum class FilterAction { FORWARD, FORWARD_DEDUPE_WITH_CHILD, DROP_EMPTY }
enum class FilterRow { EMPTY, SUMMARY_WITH_CHILD, ALREADY_FORWARDED, CHILD_REPLACES_SUMMARY, UNKNOWN_READ_ONLY, SUMMARY_MESSAGE, MESSAGE }

/** Observed structure only. No versions, identifiers or message content in diagnostics. */
data class NotificationFeatures(
    val messagingMessages: Boolean,
    val replyInput: Boolean,
    val groupKey: Boolean,
    val summary: Boolean,
    val activeChild: Boolean,
    val conversationId: ConversationIdShape,
    val conversationIsGroup: Boolean?,
    val readableText: Boolean,
    val replacesSummary: Boolean = false,
    val alreadyForwarded: Boolean = false,
) {
    fun report(): String =
        "messagingMessages=$messagingMessages; replyInput=$replyInput; groupKey=$groupKey; " +
            "summary=$summary; activeChild=$activeChild; conversationId=$conversationId; " +
            "conversationIsGroup=$conversationIsGroup; readableText=$readableText; replacesSummary=$replacesSummary; " +
            "alreadyForwarded=$alreadyForwarded"
}

data class FilterDecision(val row: FilterRow, val action: FilterAction, val readOnly: Boolean = false)

/** One decision table for old child-based and newer summary-only Slack notification shapes. */
object NotificationFilter {
    fun decide(f: NotificationFeatures): FilterDecision = when {
        !f.readableText -> FilterDecision(FilterRow.EMPTY, FilterAction.DROP_EMPTY)
        f.summary && f.activeChild -> FilterDecision(FilterRow.SUMMARY_WITH_CHILD, FilterAction.FORWARD_DEDUPE_WITH_CHILD)
        f.alreadyForwarded && !f.replacesSummary -> FilterDecision(FilterRow.ALREADY_FORWARDED, FilterAction.FORWARD_DEDUPE_WITH_CHILD)
        unknown(f) -> FilterDecision(FilterRow.UNKNOWN_READ_ONLY, FilterAction.FORWARD, readOnly = true)
        f.replacesSummary -> FilterDecision(FilterRow.CHILD_REPLACES_SUMMARY, FilterAction.FORWARD_DEDUPE_WITH_CHILD)
        f.summary -> FilterDecision(FilterRow.SUMMARY_MESSAGE, FilterAction.FORWARD)
        else -> FilterDecision(FilterRow.MESSAGE, FilterAction.FORWARD)
    }

    private fun unknown(f: NotificationFeatures): Boolean =
        (!f.messagingMessages && !f.replyInput) ||
            (f.summary && !f.groupKey) ||
            f.conversationId == ConversationIdShape.UNKNOWN ||
            (f.conversationId == ConversationIdShape.DM && f.conversationIsGroup != false) ||
            (f.conversationId in setOf(ConversationIdShape.CHANNEL, ConversationIdShape.GROUP) && f.conversationIsGroup != true)

    fun conversationIdShape(id: String?): ConversationIdShape = when {
        id == null -> ConversationIdShape.MISSING
        id.matches(Regex("D[A-Z0-9]{8,}")) -> ConversationIdShape.DM
        id.matches(Regex("C[A-Z0-9]{8,}")) -> ConversationIdShape.CHANNEL
        id.matches(Regex("G[A-Z0-9]{8,}")) -> ConversationIdShape.GROUP
        else -> ConversationIdShape.UNKNOWN
    }
}
