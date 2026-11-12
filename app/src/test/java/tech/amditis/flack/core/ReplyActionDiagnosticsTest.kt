package tech.amditis.flack.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplyActionDiagnosticsTest {
    private fun action(title: String, key: String) = ReplyActionFacts(
        SLACK_PACKAGE, 10123, 10123, listOf(key), false, false,
        title = title, semanticAction = 1, allowGeneratedReplies = true,
    )

    @Test fun recordsOnlyActionMetadata() {
        val diagnostics = ReplyActionDiagnostics()
        diagnostics.record(listOf(action("Reply", "reply_text"), action("Reply in thread", "thread_text")))
        val report = diagnostics.report()
        assertTrue(report.contains("Action 1: title=Reply; semanticAction=1; resultKeys=[reply_text]; showsUserInterface=false; allowGeneratedReplies=true"))
        assertTrue(report.contains("Action 2: title=Reply in thread; semanticAction=1; resultKeys=[thread_text]"))
        assertFalse(report.contains(SLACK_PACKAGE))
        assertFalse(report.contains("10123"))
    }

    @Test fun unknownLabelsAndKeysCannotExportMessageContent() {
        val diagnostics = ReplyActionDiagnostics()
        diagnostics.record(listOf(action("Riley Example: Meet at six", "private-room-identifier")))
        val report = diagnostics.report()
        assertFalse(report.contains("Riley"))
        assertFalse(report.contains("Meet"))
        assertFalse(report.contains("private-room"))
        assertTrue(report.contains("title=[label omitted]"))
        assertTrue(report.contains("resultKeys=[[key omitted]]"))
    }

    @Test fun retainsOnlyTheLastTwentyCases() {
        val diagnostics = ReplyActionDiagnostics()
        diagnostics.record(listOf(action("Mark as read", "reply")))
        repeat(20) { diagnostics.record(listOf(action("Reply", "text"))) }
        val report = diagnostics.report()
        assertFalse(report.contains("title=Mark as read"))
        assertEquals(20, Regex("Case ").findAll(report).count())
    }
}
