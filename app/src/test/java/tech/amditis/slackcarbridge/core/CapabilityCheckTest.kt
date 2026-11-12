package tech.amditis.slackcarbridge.core

import org.junit.Assert.assertEquals
import org.junit.Test

// All people, text and keys here are invented. Do not paste real Slack data into tests.
class CapabilityCheckTest {

    private val slackUid = 10123

    private fun replyAction(
        keys: List<String> = listOf("reply_text"),
        creatorPackage: String? = SLACK_PACKAGE,
        creatorUid: Int? = slackUid,
        showsUi: Boolean = false,
        immutable: Boolean = false,
    ) = ReplyActionFacts(creatorPackage, creatorUid, slackUid, keys, showsUi, immutable)

    private fun dm(
        title: String? = null,
        isGroup: Boolean? = false,
        messages: List<Message> = listOf(Message("Riley Example", false, "Lunch at noon?", 1_000L)),
        actions: List<ReplyActionFacts> = listOf(replyAction()),
        packageName: String = SLACK_PACKAGE,
        summary: Boolean = false,
    ) = SourceFacts(packageName, 0, "0|com.Slack|42|null|10123", summary, Conversation(title, isGroup, messages), actions)

    private fun rejected(reason: RejectReason) = Capability.Rejected(reason)

    @Test fun directMessageWithOneBackgroundReplyIsEligible() {
        assertEquals(Capability.Eligible(0, "reply_text"), CapabilityCheck.evaluate(dm()))
    }

    @Test fun eligibleActionIndexSkipsNonTextActions() {
        val markRead = replyAction(keys = emptyList())
        assertEquals(Capability.Eligible(1, "reply_text"), CapabilityCheck.evaluate(dm(actions = listOf(markRead, replyAction()))))
    }

    @Test fun otherPackagesAreRejectedFirst() {
        assertEquals(rejected(RejectReason.NOT_SLACK), CapabilityCheck.evaluate(dm(packageName = "com.example.chat")))
    }

    @Test fun groupSummariesAreNotForwarded() {
        assertEquals(rejected(RejectReason.GROUP_SUMMARY), CapabilityCheck.evaluate(dm(summary = true)))
    }

    @Test fun notificationWithoutMessagingStyleIsRejected() {
        val facts = dm().copy(conversation = null)
        assertEquals(rejected(RejectReason.NO_CONVERSATION), CapabilityCheck.evaluate(facts))
    }

    @Test fun groupConversationsAndUnknownKindsFailClosed() {
        assertEquals(rejected(RejectReason.NOT_A_DIRECT_MESSAGE), CapabilityCheck.evaluate(dm(isGroup = true)))
        assertEquals(rejected(RejectReason.NOT_A_DIRECT_MESSAGE), CapabilityCheck.evaluate(dm(isGroup = null)))
    }

    @Test fun emptyMessageListIsRejected() {
        assertEquals(rejected(RejectReason.NO_MESSAGES), CapabilityCheck.evaluate(dm(messages = emptyList())))
    }

    @Test fun noNameAndNoTitleMeansTheDriverCannotTellWhoItIs() {
        val anonymous = listOf(Message(null, false, "Hi", 1L))
        assertEquals(rejected(RejectReason.NO_CONTEXT), CapabilityCheck.evaluate(dm(messages = anonymous)))
    }

    @Test fun ownMessagesDoNotCountAsContext() {
        val onlyMine = listOf(Message("Me", true, "Hi", 1L))
        assertEquals(rejected(RejectReason.NO_CONTEXT), CapabilityCheck.evaluate(dm(messages = onlyMine)))
    }

    @Test fun missingReplyActionIsRejected() {
        assertEquals(rejected(RejectReason.NO_REPLY_ACTION), CapabilityCheck.evaluate(dm(actions = emptyList())))
    }

    @Test fun twoTextActionsAreAmbiguous() {
        val thread = replyAction(keys = listOf("thread_text"))
        assertEquals(rejected(RejectReason.MULTIPLE_REPLY_ACTIONS), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(), thread))))
    }

    @Test fun oneActionWithTwoTextKeysIsAmbiguous() {
        val action = replyAction(keys = listOf("a", "b"))
        assertEquals(rejected(RejectReason.MULTIPLE_RESULT_KEYS), CapabilityCheck.evaluate(dm(actions = listOf(action))))
    }

    @Test fun replyThatOpensTheAppIsRejected() {
        assertEquals(rejected(RejectReason.REPLY_OPENS_APP), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(showsUi = true)))))
    }

    @Test fun immutableReplyActionCannotTakeText() {
        assertEquals(rejected(RejectReason.REPLY_ACTION_IMMUTABLE), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(immutable = true)))))
    }

    @Test fun actionCreatedByAnotherAppIsRejected() {
        val foreign = replyAction(creatorPackage = "com.example.other")
        assertEquals(rejected(RejectReason.CREATOR_MISMATCH), CapabilityCheck.evaluate(dm(actions = listOf(foreign))))
    }

    @Test fun actionFromAnotherUidOrUnknownUidIsRejected() {
        assertEquals(rejected(RejectReason.CREATOR_MISMATCH), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(creatorUid = 999)))))
        assertEquals(rejected(RejectReason.CREATOR_MISMATCH), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(creatorUid = null)))))
    }

    @Test fun titleWinsOverSenderForContext() {
        val conversation = Conversation("Project room", false, listOf(Message("Riley Example", false, "Hi", 1L)))
        assertEquals("Project room", CapabilityCheck.contextLabel(conversation))
        assertEquals("Riley Example", CapabilityCheck.contextLabel(conversation.copy(title = " ")))
    }
}
