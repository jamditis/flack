package tech.amditis.flack.core

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
    private fun readOnly(reason: RejectReason) = Capability.ReadOnly(reason)

    @Test fun directMessageWithOneBackgroundReplyIsEligible() {
        assertEquals(Capability.Eligible(0, "reply_text"), CapabilityCheck.evaluate(dm()))
    }

    @Test fun eligibleActionIndexSkipsNonTextActions() {
        val markRead = replyAction(keys = emptyList())
        assertEquals(Capability.Eligible(1, "reply_text"), CapabilityCheck.evaluate(dm(actions = listOf(markRead, replyAction()))))
    }

    @Test fun aTestSourceIsAcceptedOnlyWhenAllowed() {
        val fixture = "tech.amditis.flack.fixture"
        val facts = dm(packageName = fixture, actions = listOf(replyAction(creatorPackage = fixture)))
        assertEquals(rejected(RejectReason.NOT_SLACK), CapabilityCheck.evaluate(facts))
        assertEquals(Capability.Eligible(0, "reply_text"), CapabilityCheck.evaluate(facts, setOf(SLACK_PACKAGE, fixture)))
    }

    @Test fun anActionMustComeFromTheSameAppAsTheNotification() {
        val fixture = "tech.amditis.flack.fixture"
        val facts = dm(packageName = fixture, actions = listOf(replyAction(creatorPackage = SLACK_PACKAGE)))
        assertEquals(readOnly(RejectReason.CREATOR_MISMATCH), CapabilityCheck.evaluate(facts, setOf(SLACK_PACKAGE, fixture)))
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

    @Test fun groupsNeedDestinationContextAndUnknownKindsAreReadOnly() {
        assertEquals(Capability.Eligible(0, "reply_text"), CapabilityCheck.evaluate(dm(isGroup = true, title = "Paper room")))
        assertEquals(readOnly(RejectReason.NO_CONTEXT), CapabilityCheck.evaluate(dm(isGroup = true)))
        assertEquals(readOnly(RejectReason.UNKNOWN_CONVERSATION), CapabilityCheck.evaluate(dm(isGroup = null)))
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
        assertEquals(readOnly(RejectReason.NO_REPLY_ACTION), CapabilityCheck.evaluate(dm(actions = emptyList())))
    }

    @Test fun twoTextActionsAreAmbiguous() {
        val thread = replyAction(keys = listOf("thread_text"))
        assertEquals(readOnly(RejectReason.MULTIPLE_REPLY_ACTIONS), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(), thread))))
    }

    @Test fun anExplicitDirectReplyWinsOverAThreadOrSmartReply() {
        val direct = replyAction().copy(title = "Reply", semanticAction = 1)
        listOf("Reply in thread", "Smart reply").forEach { title ->
            val other = replyAction(keys = listOf("thread_text")).copy(title = title, semanticAction = 1)
            assertEquals(Capability.Eligible(1, "reply_text"), CapabilityCheck.evaluate(dm(actions = listOf(other, direct))))
            assertEquals(Capability.Eligible(0, "reply_text"), CapabilityCheck.evaluate(dm(actions = listOf(direct, other))))
        }
    }

    @Test fun ambiguousUnknownAndForegroundChoicesStayReadOnly() {
        val direct = replyAction().copy(title = "Reply", semanticAction = 1)
        listOf(
            listOf(direct, direct),
            listOf(direct.copy(semanticAction = 0), replyAction()),
            listOf(direct.copy(title = "Unknown label"), replyAction()),
            listOf(direct.copy(showsUserInterface = true), replyAction()),
        ).forEach { actions ->
            assertEquals(readOnly(RejectReason.MULTIPLE_REPLY_ACTIONS), CapabilityCheck.evaluate(dm(actions = actions)))
        }
    }

    @Test fun selectionStillValidatesCreatorMutabilityAndResultKeys() {
        val other = replyAction().copy(title = "Reply in thread", semanticAction = 1)
        val direct = replyAction().copy(title = "Reply", semanticAction = 1)
        listOf(
            direct.copy(creatorUid = 999) to RejectReason.CREATOR_MISMATCH,
            direct.copy(isImmutable = true) to RejectReason.REPLY_ACTION_IMMUTABLE,
            direct.copy(freeFormResultKeys = listOf("a", "b")) to RejectReason.MULTIPLE_RESULT_KEYS,
        ).forEach { (action, reason) ->
            assertEquals(readOnly(reason), CapabilityCheck.evaluate(dm(actions = listOf(other, action))))
        }
    }

    @Test fun oneActionWithTwoTextKeysIsAmbiguous() {
        val action = replyAction(keys = listOf("a", "b"))
        assertEquals(readOnly(RejectReason.MULTIPLE_RESULT_KEYS), CapabilityCheck.evaluate(dm(actions = listOf(action))))
    }

    @Test fun replyThatOpensTheAppIsRejected() {
        assertEquals(readOnly(RejectReason.REPLY_OPENS_APP), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(showsUi = true)))))
    }

    @Test fun immutableReplyActionCannotTakeText() {
        assertEquals(readOnly(RejectReason.REPLY_ACTION_IMMUTABLE), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(immutable = true)))))
    }

    @Test fun actionCreatedByAnotherAppIsRejected() {
        val foreign = replyAction(creatorPackage = "com.example.other")
        assertEquals(readOnly(RejectReason.CREATOR_MISMATCH), CapabilityCheck.evaluate(dm(actions = listOf(foreign))))
    }

    @Test fun actionFromAnotherUidOrUnknownUidIsRejected() {
        assertEquals(readOnly(RejectReason.CREATOR_MISMATCH), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(creatorUid = 999)))))
        assertEquals(readOnly(RejectReason.CREATOR_MISMATCH), CapabilityCheck.evaluate(dm(actions = listOf(replyAction(creatorUid = null)))))
    }

    @Test fun titleWinsOverSenderForContext() {
        val conversation = Conversation("Project room", false, listOf(Message("Riley Example", false, "Hi", 1L)))
        assertEquals("Project room", CapabilityCheck.contextLabel(conversation))
        assertEquals("Riley Example", CapabilityCheck.contextLabel(conversation.copy(title = " ")))
    }
}
