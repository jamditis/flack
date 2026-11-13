package tech.amditis.flack.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    @Test fun sameActionOnTwoConversationsDisablesBothRepliesAndKeepsReadout() {
        val reg = registry()
        val riley = reg.publish("dm-riley", "f1", target = "shared-op").binding
        val result = reg.observe("dm-sam", "g1", FakeAction("shared-op"), "reply_text", false)
        val published = result as Observation.Publish
        assertNull(published.binding.action)
        assertEquals(riley.carNotificationId, published.replyDisabled.single().carNotificationId)
        assertNull(published.replyDisabled.single().action)
        assertEquals(2, reg.bindingCount())
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(riley.token, "Hi") as ConsumeResult.Rejected).reason)
        // Both stay read-only on later updates until the source notification goes away.
        assertNull((reg.observe("dm-riley", "f2", FakeAction("riley-op"), "reply_text", false) as Observation.Publish).binding.action)
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
        assertNull((reg.observe("dm-riley", "f2", FakeAction("shared-op"), "reply_text", false) as Observation.Publish).binding.action)
    }

    @Test fun sameContentWithANewActionIsANewGenerationAndIsChecked() {
        val reg = registry()
        val first = reg.publish("dm-riley", "f1", target = "op-1").binding
        reg.publish("dm-sam", "g1", target = "op-2")
        // Same text, but Slack attached Sam's action to Riley's notification.
        val result = reg.observe("dm-riley", "f1", FakeAction("op-2"), "reply_text", false)
        assertNull((result as Observation.Publish).binding.action)
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(first.token, "Hi") as ConsumeResult.Rejected).reason)
    }

    @Test fun sameContentWithANewSafeActionGetsANewToken() {
        val reg = registry()
        val first = reg.publish("dm-riley", "f1", target = "op-1").binding
        val second = reg.publish("dm-riley", "f1", target = "op-1b").binding
        assertNotEquals(first.token, second.token)
    }

    @Test fun actionsOfNotificationsThatCannotBeForwardedStillBlockAClash() {
        // Even a suppressed summary must stop a DM from using its action.
        val reg = registry()
        reg.recordOther("channel-general", listOf(FakeAction("shared-op")))
        assertNull((reg.observe("dm-riley", "f1", FakeAction("shared-op"), "reply_text", false) as Observation.Publish).binding.action)
    }

    @Test fun aLaterIneligibleNotificationWithTheSameActionWithdrawsThePublishedOne() {
        val reg = registry()
        val riley = reg.publish("dm-riley", "f1", target = "shared-op").binding
        val disabled = reg.recordOther("channel-general", listOf(FakeAction("shared-op"))).single()
        assertEquals(riley.carNotificationId, disabled.carNotificationId)
        assertNull(disabled.action)
        assertEquals(ConsumeRejection.NO_REPLY_ACTION, (reg.consume(disabled.token, "Hi") as ConsumeResult.Rejected).reason)
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
        assertNull((reg.observe("dm-c", "h1", FakeAction("shared-op"), "reply_text", false) as Observation.Publish).binding.action)
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
        assertNull((reg.observe("dm-sam", "g1", FakeAction("shared-op"), "reply_text", false) as Observation.Publish).binding.action)
    }

    @Test fun readOnlyCopiesDeduplicateExpireAndCannotConsumeTheirToken() {
        val reg = registry()
        val binding = (reg.observe("unknown", "f1", null, null, false) as Observation.Publish).binding
        assertEquals(ConsumeRejection.NO_REPLY_ACTION, (reg.consume(binding.token, "Hi") as ConsumeResult.Rejected).reason)
        assertTrue(reg.observe("unknown", "f1", null, null, false) is Observation.Unchanged)
        reg.withdrawAll()
        assertTrue(reg.observe("unknown", "f1", null, null, false) is Observation.Baseline)
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(binding.token, "Hi") as ConsumeResult.Rejected).reason)
    }

    @Test fun readOnlyMultipleActionsStillDisableEveryConflictingReply() {
        val reg = registry()
        val channel = reg.publish("channel", "c1", "channel-op").binding
        val thread = reg.publish("thread", "t1", "thread-op").binding
        val mixed = reg.observe("mixed", "m1", null, null, false, listOf(FakeAction("channel-op"), FakeAction("thread-op"))) as Observation.Publish
        assertEquals(2, mixed.replyDisabled.size)
        assertNull(mixed.binding.action)
        listOf(channel, thread).forEach {
            assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(it.token, "Hi") as ConsumeResult.Rejected).reason)
        }
        assertEquals(3, reg.bindingCount())
    }

    @Test fun sharingOnlyANonReplyActionPreservesDistinctReplies() {
        val reg = registry()
        val open = FakeAction("open-app")
        val channelReply = FakeAction("channel-reply")
        val threadReply = FakeAction("thread-reply")
        val channel = (reg.observe("channel", "c1", channelReply, "reply_text", false, listOf(channelReply, open)) as Observation.Publish).binding
        val thread = reg.observe("thread", "t1", threadReply, "reply_text", false, listOf(threadReply, open)) as Observation.Publish
        assertTrue(thread.replyDisabled.isEmpty())
        assertEquals(threadReply, thread.binding.action)
        assertTrue(reg.recordOther("summary", listOf(open)).isEmpty())
        assertEquals(channelReply, (reg.consume(channel.token, "Channel response") as ConsumeResult.Dispatch).binding.action)
        assertEquals(threadReply, (reg.consume(thread.binding.token, "Thread response") as ConsumeResult.Dispatch).binding.action)
        assertEquals(ConsumeRejection.ALREADY_CONSUMED, (reg.consume(channel.token, "Duplicate") as ConsumeResult.Rejected).reason)
    }

    @Test fun aReplySharedWithAnotherSourcesNonReplyActionStillFailsClosed() {
        val reg = registry()
        val channelReply = FakeAction("channel-reply")
        val other = FakeAction("other-action")
        val channel = (reg.observe("channel", "c1", channelReply, "reply_text", false, listOf(channelReply, other)) as Observation.Publish).binding
        val thread = reg.observe("thread", "t1", other, "reply_text", false) as Observation.Publish
        assertNull(thread.binding.action)
        assertTrue(thread.replyDisabled.isEmpty())
        assertEquals(channelReply, (reg.consume(channel.token, "Channel response") as ConsumeResult.Dispatch).binding.action)
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

    private val kites = ApiDestination("T0PAPER001", "D0KITES001")

    @Test fun anApiRouteDispatchesOnceWithoutAnOriginalAction() {
        val reg = registry()
        val binding = (reg.observe("channel", "f1", null, null, false, apiRoute = kites) as Observation.Publish).binding
        assertTrue(binding.canReply)
        val dispatch = reg.consume(binding.token, "On my way") as ConsumeResult.Dispatch
        assertEquals(kites, dispatch.binding.apiRoute)
        assertEquals(ConsumeRejection.ALREADY_CONSUMED, (reg.consume(binding.token, "On my way") as ConsumeResult.Rejected).reason)
    }

    @Test fun aChangedApiRouteIsANewGeneration() {
        val reg = registry()
        val first = (reg.observe("channel", "f1", null, null, false, apiRoute = kites) as Observation.Publish).binding
        val second = (reg.observe("channel", "f1", null, null, false) as Observation.Publish).binding
        assertNull(second.apiRoute)
        assertEquals(ConsumeRejection.UNKNOWN_OR_EXPIRED, (reg.consume(first.token, "On my way") as ConsumeResult.Rejected).reason)
        assertEquals(ConsumeRejection.NO_REPLY_ACTION, (reg.consume(second.token, "On my way") as ConsumeResult.Rejected).reason)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aBindingNeverHasBothRoutes() {
        registry().observe("channel", "f1", FakeAction("channel"), "reply_text", false, apiRoute = kites)
    }
}
