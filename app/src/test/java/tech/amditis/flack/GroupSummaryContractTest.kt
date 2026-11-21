package tech.amditis.flack

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.amditis.flack.core.ApiDestination
import tech.amditis.flack.core.ApiRoute
import tech.amditis.flack.core.ConsumeResult
import java.util.concurrent.Executor

/** Invented versions of the content-free Android 16 and Android 17 field shapes in #29–#41. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GroupSummaryContractTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private lateinit var listener: SlackListenerService

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("bridge", Application.MODE_PRIVATE).edit().clear().commit()
        BridgeState.registry.clear()
        BridgeState.setEnabled(app, true)
        BridgeState.setHidePhoneCopies(app, false)
        listener = Robolectric.buildService(SlackListenerService::class.java).create().get()
        listener.activeSources = { emptyArray() }
        listener.apiWorker = Executor { it.run() }
        listener.resolveApi = { ApiRoute.Certain(ApiDestination("T0PAPER001", it.routeFields.shortcutId!!)) }
        listener.onCarConnectionChanged(true)
    }

    @After fun tearDown() {
        BridgeState.setEnabled(app, false)
        BridgeState.registry.clear()
        BridgeState.carConnected = false
    }

    private fun source(
        key: String, summary: Boolean, conversationId: String? = "D0PAPER001",
        group: String? = "invented-slack-group", text: String = "Paper boats at noon",
        messaging: Boolean = true, reply: Boolean = true, withReplyPair: Boolean = false,
    ): StatusBarNotification {
        val builder = NotificationCompat.Builder(app, "slack").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setGroup(group).setGroupSummary(summary).setShortcutId(conversationId).setCategory(Notification.CATEGORY_MESSAGE)
        if (messaging) {
            val groupConversation = conversationId?.startsWith("D") == false
            builder.setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
                .setGroupConversation(groupConversation)
                .setConversationTitle(if (groupConversation) "#paper-kites" else null)
                .addMessage(text, 1_000L, Person.Builder().setName("Riley Example").setKey("U0PAPER001").build()))
        } else builder.setContentTitle("Paper room").setContentText(text)
        if (reply) {
            builder.addAction(NotificationCompat.Action.Builder(0, "Mark read", SyntheticSlack.replyIntent(app, "$key-read"))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build())
            val titles = if (withReplyPair) listOf("Reply in thread", "Reply") else listOf("Reply")
            titles.forEachIndexed { index, title ->
                builder.addAction(NotificationCompat.Action.Builder(0, title, SyntheticSlack.replyIntent(app, "$key-$index"))
                    .addRemoteInput(RemoteInput.Builder("reply_$index").build())
                    .setSemanticAction(if (withReplyPair) NotificationCompat.Action.SEMANTIC_ACTION_NONE else NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(withReplyPair).build())
            }
        }
        val notification = builder.build()
        notification.extras.putString("test.workspace", "T0PAPER001")
        notification.extras.putString("test.timestamp", "1700000000.000100")
        return SyntheticSlack.statusBar(notification, key)
    }

    private fun post(sbn: StatusBarNotification) {
        listener.onNotificationPosted(sbn)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun binding(key: String) = BridgeState.registry.findBinding { it.notificationKey == key }

    private fun dictate(action: NotificationCompat.Action, text: String) {
        val inputs = requireNotNull(action.remoteInputs)
        val reply = Intent(shadowOf(requireNotNull(action.actionIntent)).savedIntent)
        RemoteInput.addResultsToIntent(inputs, reply, Bundle().apply { putCharSequence(inputs.single().resultKey, text) })
        CarActionReceiver().onReceive(app, reply)
    }

    private fun slackReplies() = shadowOf(app).broadcastIntents.filter { it.action == "test.slack.REPLY" }

    @Test fun android17SummaryOnlyForTwoDmsAndTwoChannelsForwardsAllFourOnce() {
        val before = BridgeState.forwarded
        val apiBefore = BridgeState.apiRouted
        val ids = listOf("D0PAPER001", "D0PAPER002", "C0PAPER001", "C0PAPER002")
        val replies = ids.mapIndexed { index, id ->
            val text = "Synthetic summary ${index + 1}"
            val summary = source("summary-$id", true, id, group = "group-$id", text = text, withReplyPair = true)
            post(summary)
            post(summary)
            val copy = manager.allNotifications.single {
                NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)?.messages?.single()?.text
                    ?.toString()?.endsWith(text) == true
            }
            val reply = requireNotNull(NotificationCompat.getAction(copy, 0))
            assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
            assertEquals(app.getString(R.string.reply_conversation_label), reply.title.toString())
            assertTrue(NotificationCompat.getInvisibleActions(copy).any {
                it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ
            })
            id to reply
        }
        assertEquals(4, manager.size())
        assertEquals(before + 4, BridgeState.forwarded)
        assertEquals(apiBefore, BridgeState.apiRouted)
        val report = TestReport.build(app)
        assertTrue(report.contains("row=SUMMARY_MESSAGE; action=FORWARD; messagingMessages=true; replyInput=true; groupKey=true; summary=true; activeChild=false"))
        assertTrue(report.contains("conversationId=DM"))
        assertTrue(report.contains("conversationId=CHANNEL"))
        listOf("Paper boats", "Riley Example", "D0PAPER001", "T0PAPER001", "U0PAPER001", "invented-slack-group").forEach {
            assertFalse(report.contains(it))
        }
        replies.asReversed().forEach { (id, reply) ->
            val text = "Synthetic response for $id"
            dictate(reply, text)
            val sent = slackReplies().last()
            assertEquals("summary-$id-1", sent.getStringExtra("conversation"))
            assertEquals(text, RemoteInput.getResultsFromIntent(sent)!!.getCharSequence("reply_1").toString())
        }
        assertEquals(4, slackReplies().size)
    }

    @Test fun aSummaryWithoutAValidatedRouteShowsLocalFeedbackAndSendsNothing() {
        post(source("unroutable-summary", true, "C0PAPER009", reply = false))
        val copy = manager.allNotifications.single()
        val actions = NotificationCompat.getInvisibleActions(copy)
        val reply = actions.single { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY }
        assertEquals(app.getString(R.string.reply_unavailable_label), reply.title.toString())
        assertTrue(actions.any { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ })
        dictate(reply, "Do not send")
        assertTrue(slackReplies().isEmpty())
        assertTrue(manager.allNotifications.any {
            it.extras.getCharSequence(Notification.EXTRA_TEXT) == app.getString(R.string.car_reply_status_unavailable)
        })
    }

    @Test fun android16ChildWinsEvenBeforeItsCallbackWhenItIsAlreadyActive() {
        val summary = source("summary", true, conversationId = null)
        summary.notification.extras.remove("test.workspace")
        val child = source("child", false, conversationId = "C0PAPER001")
        listener.activeSources = { arrayOf(summary, child) }
        val before = BridgeState.forwarded
        post(summary)
        assertEquals(0, manager.size())
        post(child)
        post(child)
        post(summary)
        assertEquals(1, manager.size())
        assertEquals(before + 1, BridgeState.forwarded)
        assertNotNull(binding("child"))
        assertNull(binding("summary"))
        assertTrue(TestReport.build(app).contains("row=SUMMARY_WITH_CHILD; action=FORWARD_DEDUPE_WITH_CHILD"))
    }

    @Test fun aLateChildReplacesTheSummaryInPlaceWithoutAnotherAlertOrReplyAttempt() {
        val summary = source("summary", true, conversationId = null)
        summary.notification.extras.remove("test.workspace")
        val child = source("child", false)
        val before = BridgeState.forwarded
        post(summary)
        val original = binding("summary")!!
        assertTrue(BridgeState.registry.consume(original.token, "Invented reply") is ConsumeResult.Dispatch)
        post(child)
        val replacement = binding("child")!!
        assertEquals(original.carNotificationId, replacement.carNotificationId)
        assertNotEquals(original.token, replacement.token)
        assertNull(binding("summary"))
        assertEquals(1, manager.size())
        assertEquals(before + 1, BridgeState.forwarded)
        assertTrue(BridgeState.registry.consume(original.token, "Late reply") is ConsumeResult.Rejected)
        assertTrue(BridgeState.registry.consume(replacement.token, "Duplicate reply") is ConsumeResult.Rejected)
        assertTrue(manager.allNotifications.single().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertTrue(TestReport.build(app).contains("row=CHILD_REPLACES_SUMMARY"))
        listener.onNotificationRemoved(summary)
        assertEquals(1, manager.size())
        listener.onNotificationRemoved(child)
        assertEquals(0, manager.size())
    }

    @Test fun removingTheChildDoesNotReplayItsMessageFromTheSummary() {
        val child = source("child", false)
        val summary = source("summary", true)
        val before = BridgeState.forwarded
        post(child)
        post(summary)
        listener.onNotificationRemoved(child)
        post(summary)
        assertEquals(0, manager.size())
        assertEquals(before + 1, BridgeState.forwarded)
        assertTrue(TestReport.build(app).contains("row=ALREADY_FORWARDED"))
    }

    @Test fun emptySummariesDropEvenWithReplyInputs() {
        listOf(false, true).forEach { reply ->
            post(source("empty-$reply", true, text = "", messaging = false, reply = reply))
        }
        assertEquals(0, manager.size())
        val report = TestReport.build(app)
        assertTrue(report.contains("row=EMPTY; action=DROP_EMPTY"))
        assertTrue(report.contains("EMPTY_SUMMARY:"))
    }

    @Test fun messagingOnlyAndPlainTextWithReplySummariesKeepTheirReadableContent() {
        post(source("messaging-only", true, reply = false, group = "style-group"))
        post(source("plain-reply", true, messaging = false, group = "plain-group"))
        assertEquals(2, manager.size())
        assertTrue(manager.allNotifications.all {
            NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)?.messages?.isNotEmpty() == true
        })
        assertNull(binding("plain-reply")!!.apiRoute)
        assertFalse(binding("plain-reply")!!.canReply)
    }

    @Test fun unknownReadableMixesStayReadOnlyWithoutApiOrOriginalActionDispatch() {
        listener.resolveApi = { error("unknown shapes must stay read-only") }
        val unknown = source("unknown-id", true, conversationId = "invented-new-id")
        val before = Regex("UNKNOWN_FORMAT: (\\d+)").find(BridgeState.diagnostics())?.groupValues?.get(1)?.toInt() ?: 0
        post(unknown)
        post(unknown)
        val after = Regex("UNKNOWN_FORMAT: (\\d+)").find(BridgeState.diagnostics())!!.groupValues[1].toInt()
        assertEquals(before + 1, after)
        post(source("plain", false, messaging = false, reply = false, group = "plain-group"))
        assertEquals(2, manager.size())
        assertFalse(binding("unknown-id")!!.canReply)
        assertFalse(binding("plain")!!.canReply)
        assertTrue(TestReport.build(app).contains("row=UNKNOWN_READ_ONLY; action=FORWARD"))
        assertTrue(TestReport.build(app).contains("conversationId=UNKNOWN"))
    }

    @Test fun changingToAnUnknownShapeInvalidatesAPendingApiLookup() {
        val queued = mutableListOf<Runnable>()
        listener.apiWorker = Executor { queued += it }
        val summary = source("summary", true, reply = false)
        post(summary)
        val pending = binding("summary")!!
        assertEquals(1, queued.size)
        listener.resolveApi = { error("the old lookup must expire when the filter changes") }
        post(source("summary", true, group = null, reply = false))
        val readOnly = binding("summary")!!
        assertNotEquals(pending.token, readOnly.token)
        queued.single().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(readOnly, binding("summary"))
        assertFalse(readOnly.canReply)
    }

    @Test fun regroupingTheSameMessageStaysQuietAndKeepsAConsumedReplyConsumed() {
        val before = BridgeState.forwarded
        post(source("summary", true, group = "first-group"))
        val original = binding("summary")!!
        assertTrue(BridgeState.registry.consume(original.token, "Invented reply") is ConsumeResult.Dispatch)
        post(source("summary", true, group = "second-group"))
        val regrouped = binding("summary")!!
        assertEquals(original.carNotificationId, regrouped.carNotificationId)
        assertEquals(before + 1, BridgeState.forwarded)
        assertTrue(BridgeState.registry.consume(regrouped.token, "Duplicate reply") is ConsumeResult.Rejected)
        assertTrue(manager.allNotifications.single().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        post(source("old-group-child", false, group = "first-group"))
        assertEquals(regrouped, binding("summary"))
        assertNull(binding("old-group-child"))
        assertEquals(1, manager.size())
    }

    @Test fun identicalTextInDifferentConversationsOrGroupsIsNotDeduplicated() {
        val before = BridgeState.forwarded
        post(source("summary", true))
        post(source("other-conversation", false, conversationId = "D0PAPER002"))
        post(source("other-group", false, group = "another-group"))
        assertEquals(3, manager.size())
        assertEquals(before + 3, BridgeState.forwarded)
    }

    @Test fun anUnfamiliarSummaryWithDifferentContentSurvivesAnActiveChildInItsGroup() {
        val child = source("child", false)
        val summary = source("summary", true, conversationId = "invented-new-id", text = "Another paper boat")
        listener.activeSources = { arrayOf(child, summary) }
        post(child)
        post(summary)
        assertEquals(2, manager.size())
        assertFalse(binding("summary")!!.canReply)
        assertTrue(TestReport.build(app).contains("row=UNKNOWN_READ_ONLY; action=FORWARD"))
    }

    @Test fun aPausedSummaryIsNotReplayedAsAChildOnResume() {
        BridgeState.setEnabled(app, false)
        post(source("paused-summary", true))
        BridgeState.setEnabled(app, true)
        post(source("child", false))
        assertEquals(0, manager.size())
    }

    @Test fun anEmptyChildCannotWithdrawAReadableSummary() {
        val summary = source("summary", true)
        post(summary)
        val original = binding("summary")!!
        post(source("empty-child", false, messaging = false, reply = false, text = ""))
        post(summary)
        assertEquals(1, manager.size())
        assertEquals(original, binding("summary"))
    }

    @Test fun anOlderChildCannotTakeTheCurrentSummarysReplyState() {
        val before = BridgeState.forwarded
        post(source("summary", true, text = "First paper boat"))
        val first = binding("summary")!!
        assertTrue(BridgeState.registry.consume(first.token, "Invented reply") is ConsumeResult.Dispatch)
        post(source("summary", true, text = "Second paper boat"))
        val current = binding("summary")!!
        post(source("older-child", false, text = "First paper boat"))
        assertEquals(current, binding("summary"))
        assertNull(binding("older-child"))
        assertEquals(before + 2, BridgeState.forwarded)
    }

    @Test fun aChildChangingGroupsDoesNotLeaveItsPreviousCopyBehind() {
        post(source("summary", true))
        val original = binding("summary")!!
        post(source("child", false, group = "other-group", conversationId = "D0PAPER002"))
        assertEquals(2, manager.size())
        post(source("child", false))
        assertEquals(1, manager.size())
        assertEquals(original.carNotificationId, binding("child")!!.carNotificationId)
    }

    @Test fun observedFeatureCasesStayCappedInTheContentFreeReport() {
        val summary = source("summary", true)
        repeat(25) { post(summary) }
        val report = TestReport.build(app)
        assertEquals(20, Regex("  Case \\d+: row=").findAll(report).count())
        assertTrue(report.length <= tech.amditis.flack.core.REPORT_LIMIT)
        assertFalse(report.contains("Paper boats"))
        assertFalse(BridgeState.diagnostics().contains("messagingMessages="))
    }
}
