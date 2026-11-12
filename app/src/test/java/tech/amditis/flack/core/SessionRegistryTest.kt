package tech.amditis.flack.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Stand-in for a PendingIntent: equality means "same target operation".
private data class FakeAction(val target: String)

class SessionRegistryTest {

    private var counter = 0
    private fun registry(max: Int = DEFAULT_MAX_BINDINGS) = SessionRegistry<FakeAction>({ "token-${++counter}" }, max)

    private fun SessionRegistry<FakeAction>.publish(key: String, fingerprint: String, target: String = key, fromSelf: Boolean = false) =
        observe(key, fingerprint, FakeAction(target), "reply_text", fromSelf) as Observation.Publish

    @Test fun firstSightingPublishesWithATokenAndAlerts() {
        val result = registry().publish("dm-riley", "f1")
        assertEquals("token-1", result.binding.token)
        assertTrue(result.alert)
    }

    @Test fun sameContentAgainChangesNothing() {
        val reg = registry()
        reg.publish("dm-riley", "f1")
        assertTrue(reg.observe("dm-riley", "f1", FakeAction("dm-riley"), "reply_text", false) is Observation.Unchanged)
    }

    @Test fun newContentMakesANewGenerationAndKillsTheOldToken() {
        val reg = registry()
        val first = reg.publish("dm-riley", "f1").binding
        val second = reg.publish("dm-riley", "f2").binding
        assertNotEquals(first.token, second.token)
        assertTrue(second.generation > first.generation)
        assertEquals(first.carNotificationId, second.carNotificationId)
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(first.token, "Yes") as ConsumeResult.Rejected).reason)
    }

    @Test fun updateDuringDictationNeverRetargetsTheReply() {
        // Riley's token is held while Sam's message arrives. Riley's token must still
        // reach Riley's action, and Sam's arrival must not create a route for it.
        val reg = registry()
        val riley = reg.publish("dm-riley", "f1", target = "riley-op").binding
        reg.publish("dm-sam", "g1", target = "sam-op")
        val result = reg.consume(riley.token, "On my way") as ConsumeResult.Dispatch
        assertEquals(FakeAction("riley-op"), result.binding.action)
    }

    @Test fun tokenDispatchesOnlyOnce() {
        val reg = registry()
        val binding = reg.publish("dm-riley", "f1").binding
        assertTrue(reg.consume(binding.token, "Yes") is ConsumeResult.Dispatch)
        assertEquals(ConsumeRejection.ALREADY_CONSUMED, (reg.consume(binding.token, "Yes") as ConsumeResult.Rejected).reason)
    }

    @Test fun invalidTextDoesNotUseUpTheToken() {
        val reg = registry()
        val binding = reg.publish("dm-riley", "f1").binding
        assertEquals(ConsumeRejection.EMPTY, (reg.consume(binding.token, "  ") as ConsumeResult.Rejected).reason)
        assertEquals(ConsumeRejection.EMPTY, (reg.consume(binding.token, null) as ConsumeResult.Rejected).reason)
        val tooLong = "a".repeat(MAX_REPLY_LENGTH + 1)
        assertEquals(ConsumeRejection.TOO_LONG, (reg.consume(binding.token, tooLong) as ConsumeResult.Rejected).reason)
        val ok = reg.consume(binding.token, "a".repeat(MAX_REPLY_LENGTH)) as ConsumeResult.Dispatch
        assertEquals(MAX_REPLY_LENGTH, ok.text.length)
    }

    @Test fun unknownTokenIsRejected() {
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (registry().consume("made-up", "Hi") as ConsumeResult.Rejected).reason)
    }

    @Test fun sameActionOnTwoConversationsWithdrawsBoth() {
        val reg = registry()
        val riley = reg.publish("dm-riley", "f1", target = "shared-op").binding
        val result = reg.observe("dm-sam", "g1", FakeAction("shared-op"), "reply_text", false)
        assertEquals(listOf(riley), (result as Observation.Ambiguous).withdrawn)
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(riley.token, "Hi") as ConsumeResult.Rejected).reason)
        // Both stay withdrawn on later updates until the source notification goes away.
        assertTrue(reg.observe("dm-riley", "f2", FakeAction("riley-op"), "reply_text", false) is Observation.Ambiguous)
        reg.remove("dm-riley")
        assertTrue(reg.observe("dm-riley", "f3", FakeAction("riley-op"), "reply_text", false) is Observation.Publish)
    }

    @Test fun aWithdrawnSharedActionStaysBlockedAfterOneSideIsReposted() {
        // Riley and Sam share one action. Riley's notification goes away and comes back
        // with the same action while Sam's is still there: it must stay ambiguous.
        val reg = registry()
        reg.publish("dm-riley", "f1", target = "shared-op")
        reg.observe("dm-sam", "g1", FakeAction("shared-op"), "reply_text", false)
        reg.remove("dm-riley")
        assertTrue(reg.observe("dm-riley", "f2", FakeAction("shared-op"), "reply_text", false) is Observation.Ambiguous)
    }

    @Test fun sameContentWithANewActionIsANewGenerationAndIsChecked() {
        val reg = registry()
        val first = reg.publish("dm-riley", "f1", target = "op-1").binding
        reg.publish("dm-sam", "g1", target = "op-2")
        // Same text, but Slack attached Sam's action to Riley's notification.
        val result = reg.observe("dm-riley", "f1", FakeAction("op-2"), "reply_text", false)
        assertTrue(result is Observation.Ambiguous)
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(first.token, "Hi") as ConsumeResult.Rejected).reason)
    }

    @Test fun sameContentWithANewSafeActionGetsANewToken() {
        val reg = registry()
        val first = reg.publish("dm-riley", "f1", target = "op-1").binding
        val second = reg.publish("dm-riley", "f1", target = "op-1b").binding
        assertNotEquals(first.token, second.token)
    }

    @Test fun actionsOfNotificationsThatCannotBeForwardedStillBlockAClash() {
        // A channel notification is not forwarded, but if it carries the DM's action
        // the DM's reply could land in the channel.
        val reg = registry()
        reg.recordOther("channel-general", listOf(FakeAction("shared-op")))
        assertTrue(reg.observe("dm-riley", "f1", FakeAction("shared-op"), "reply_text", false) is Observation.Ambiguous)
    }

    @Test fun aLaterIneligibleNotificationWithTheSameActionWithdrawsThePublishedOne() {
        val reg = registry()
        val riley = reg.publish("dm-riley", "f1", target = "shared-op").binding
        assertEquals(listOf(riley), reg.recordOther("channel-general", listOf(FakeAction("shared-op"))))
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(riley.token, "Hi") as ConsumeResult.Rejected).reason)
    }

    @Test fun aForwardedConversationThatBecomesIneligibleIsWithdrawn() {
        val reg = registry()
        val riley = reg.publish("dm-riley", "f1").binding
        assertEquals(listOf(riley), reg.recordOther("dm-riley", listOf(FakeAction("dm-riley"))))
        assertEquals(0, reg.bindingCount())
    }

    @Test fun aThirdConversationWithAConflictedActionIsAlsoWithheld() {
        val reg = registry()
        reg.publish("dm-a", "f1", target = "shared-op")
        reg.observe("dm-b", "g1", FakeAction("shared-op"), "reply_text", false)
        assertTrue(reg.observe("dm-c", "h1", FakeAction("shared-op"), "reply_text", false) is Observation.Ambiguous)
    }

    @Test fun aChangedResultKeyIsANewGeneration() {
        val reg = registry()
        val first = reg.publish("dm-riley", "f1").binding
        val result = reg.observe("dm-riley", "f1", FakeAction("dm-riley"), "other_key", false) as Observation.Publish
        assertNotEquals(first.token, result.binding.token)
        assertEquals("other_key", result.binding.resultKey)
    }

    @Test fun reEnablingDoesNotReplayAConversationThatWasAlreadyForwarded() {
        val reg = registry()
        reg.publish("dm-riley", "f1")
        reg.withdrawAll()
        assertTrue(reg.observe("dm-riley", "f1", FakeAction("dm-riley"), "reply_text", false) is Observation.Baseline)
        assertTrue(reg.observe("dm-riley", "f2", FakeAction("dm-riley"), "reply_text", false) is Observation.Publish)
    }

    @Test fun findBindingMatchesOnlyTheRequestedSource() {
        val reg = registry()
        val riley = reg.publish("0|tech.amditis.flack.fixture|7|null|10", "f1").binding
        reg.publish("0|com.Slack|7|null|20", "g1")
        assertEquals(riley, reg.findBinding { it.notificationKey.split("|")[1] == "tech.amditis.flack.fixture" })
        assertEquals(null, reg.findBinding { it.notificationKey == "missing" })
    }

    @Test fun onlyTheNewestGenerationIsCurrent() {
        val reg = registry()
        val first = reg.publish("dm-riley", "f1").binding
        assertTrue(reg.isCurrent(first))
        val second = reg.publish("dm-riley", "f2").binding
        assertEquals(false, reg.isCurrent(first))
        assertTrue(reg.isCurrent(second))
    }

    @Test fun pausingWithdrawsCopiesButKeepsTheActionRecord() {
        val reg = registry()
        val riley = reg.publish("dm-riley", "f1", target = "shared-op").binding
        assertEquals(listOf(riley), reg.withdrawAll())
        assertEquals(0, reg.bindingCount())
        assertTrue(reg.observe("dm-sam", "g1", FakeAction("shared-op"), "reply_text", false) is Observation.Ambiguous)
    }

    @Test fun notificationsPresentAtConnectAreNotReplayed() {
        val reg = registry()
        reg.recordBaseline("dm-riley", "f1", FakeAction("op"))
        assertTrue(reg.observe("dm-riley", "f1", FakeAction("op"), "reply_text", false) is Observation.Baseline)
        assertTrue(reg.observe("dm-riley", "f2", FakeAction("op"), "reply_text", false) is Observation.Publish)
    }

    @Test fun ownReplyEchoUpdatesSilently() {
        val reg = registry()
        reg.publish("dm-riley", "f1")
        assertEquals(false, reg.publish("dm-riley", "f2", fromSelf = true).alert)
    }

    @Test fun removalAndClearDropTokens() {
        val reg = registry()
        val riley = reg.publish("dm-riley", "f1").binding
        val sam = reg.publish("dm-sam", "g1").binding
        assertEquals(riley, reg.remove("dm-riley"))
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(riley.token, "Hi") as ConsumeResult.Rejected).reason)
        assertEquals(listOf(sam), reg.clear())
        assertEquals(0, reg.bindingCount())
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(sam.token, "Hi") as ConsumeResult.Rejected).reason)
    }

    @Test fun oldestBindingIsEvictedPastTheLimit() {
        val reg = registry(max = 2)
        val a = reg.publish("a", "1").binding
        reg.publish("b", "1")
        val result = reg.publish("c", "1")
        assertEquals(listOf(a), result.evicted)
        assertEquals(2, reg.bindingCount())
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(a.token, "Hi") as ConsumeResult.Rejected).reason)
    }

    @Test fun anUpdateMovesABindingToNewest() {
        val reg = registry(max = 2)
        reg.publish("a", "1")
        val b = reg.publish("b", "1").binding
        reg.publish("a", "2")
        assertEquals(listOf(b), reg.publish("c", "1").evicted)
    }
}
