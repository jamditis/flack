package tech.amditis.flack.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationFilterTest {
    // Invented structures matching #29/#40's Android 16 child and #41's Android 17 summary.
    private val child = NotificationFeatures(true, true, true, false, false, ConversationIdShape.DM, false, true)
    private val summary = child.copy(summary = true)

    @Test fun everyDecisionTableRowHasAContract() {
        val cases = listOf(
            child to FilterDecision(FilterRow.MESSAGE, FilterAction.FORWARD),
            summary to FilterDecision(FilterRow.SUMMARY_MESSAGE, FilterAction.FORWARD),
            summary.copy(activeChild = true) to FilterDecision(FilterRow.SUMMARY_WITH_CHILD, FilterAction.FORWARD_DEDUPE_WITH_CHILD),
            child.copy(replacesSummary = true, alreadyForwarded = true) to
                FilterDecision(FilterRow.CHILD_REPLACES_SUMMARY, FilterAction.FORWARD_DEDUPE_WITH_CHILD),
            summary.copy(alreadyForwarded = true) to FilterDecision(FilterRow.ALREADY_FORWARDED, FilterAction.FORWARD_DEDUPE_WITH_CHILD),
            summary.copy(messagingMessages = false, replyInput = false, readableText = false) to
                FilterDecision(FilterRow.EMPTY, FilterAction.DROP_EMPTY),
            summary.copy(conversationId = ConversationIdShape.UNKNOWN) to
                FilterDecision(FilterRow.UNKNOWN_READ_ONLY, FilterAction.FORWARD, readOnly = true),
        )
        cases.forEach { (features, expected) -> assertEquals(features.report(), expected, NotificationFilter.decide(features)) }
    }

    @Test fun theReportShapesNeedNoVersionNumber() {
        listOf(ConversationIdShape.DM, ConversationIdShape.CHANNEL, ConversationIdShape.GROUP).forEach { id ->
            val features = summary.copy(conversationId = id, conversationIsGroup = id != ConversationIdShape.DM)
            assertEquals(FilterRow.SUMMARY_MESSAGE, NotificationFilter.decide(features).row)
            assertEquals(FilterRow.MESSAGE, NotificationFilter.decide(features.copy(summary = false)).row)
            assertEquals(FilterRow.SUMMARY_MESSAGE, NotificationFilter.decide(features.copy(replyInput = false)).row)
        }
    }

    @Test fun unfamiliarReadableFeaturesAreReadOnlyAndEmptyContentStillDrops() {
        listOf(
            summary.copy(groupKey = false),
            child.copy(messagingMessages = false, replyInput = false),
            child.copy(conversationId = ConversationIdShape.CHANNEL),
        ).forEach {
            assertEquals(FilterDecision(FilterRow.UNKNOWN_READ_ONLY, FilterAction.FORWARD, true), NotificationFilter.decide(it))
        }
        assertEquals(FilterRow.EMPTY, NotificationFilter.decide(summary.copy(readableText = false)).row)
    }
}
