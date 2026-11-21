package tech.amditis.flack

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor

/** Synthetic equivalents of the content-free car reports; no device dump. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReportedSlackCarTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private val manager get() = shadowOf(nm)
    private lateinit var listener: SlackListenerService

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        app.getSharedPreferences("bridge", Application.MODE_PRIVATE).edit().clear().commit()
        BridgeState.registry.clear()
        BridgeState.setEnabled(app, true)
        listener = Robolectric.buildService(SlackListenerService::class.java).create().get()
        listener.onCarConnectionChanged(true)
        listener.apiWorker = Executor { it.run() }
        listener.resolveApi = { error("An ambiguous notification must not use the Slack API fallback") }
    }

    @After fun tearDown() {
        BridgeState.setEnabled(app, false)
        BridgeState.carConnected = false
        BridgeState.registry.clear()
    }

    private fun reportedMessage(
        key: String, shortcut: String = "C12345678", withMarkRead: Boolean = true, text: String = "Lunch at noon?",
        history: List<Pair<String?, String>> = emptyList(),
        timestampStartMillis: Long = 1_000L,
    ): StatusBarNotification {
        val group = !shortcut.startsWith("D")
        val source = SyntheticSlack.dm(app, key = key, title = if (group) "Paper room" else null,
            text = text, isGroup = group, shortcutId = shortcut, extraMessages = history,
            extraReply = SyntheticSlack.replyIntent(app, "$key-thread"),
            replySemanticAction = 0, extraReplySemanticAction = 0, replyShowsUserInterface = true,
            timestampStartMillis = timestampStartMillis)
        val actions = source.notification.actions
        val read = Notification.Action.Builder(null, "Mark as read", SyntheticSlack.replyIntent(app, "$key-read"))
            .setSemanticAction(2).build()
        val notification = Notification.Builder.recoverBuilder(app, source.notification).setGroup("synthetic-slack").build()
        notification.actions = if (withMarkRead) arrayOf(read, actions[1], actions[0]) else arrayOf(actions[1], actions[0])
        return SyntheticSlack.statusBar(notification, key)
    }

    private fun carReply(copy: Notification): NotificationCompat.Action {
        val visible = (0 until NotificationCompat.getActionCount(copy)).mapNotNull { NotificationCompat.getAction(copy, it) }
        val actions = visible + NotificationCompat.getInvisibleActions(copy)
        val read = actions.single { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ }
        assertFalse(read.showsUserInterface)
        val reply = actions.single { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY }
        assertFalse(reply.showsUserInterface)
        assertFalse(reply.actionIntent.isImmutable)
        assertTrue(reply.remoteInputs!!.single().allowFreeFormInput)
        return reply
    }

    private fun dictate(action: NotificationCompat.Action, text: String) {
        val inputs = action.remoteInputs!!
        val reply = Intent(shadowOf(action.actionIntent).savedIntent)
        RemoteInput.addResultsToIntent(inputs, reply, Bundle().apply { putCharSequence(inputs.single().resultKey, text) })
        CarActionReceiver().onReceive(app, reply)
    }

    private fun slackReplies() = shadowOf(app).broadcastIntents.filter { it.action == "test.slack.REPLY" }

    @Test fun bothReportedActionListsForwardAndReplyToTheOriginalConversation() {
        val before = BridgeState.forwarded
        listOf("D12345678", "C12345678", "G12345678").forEach { shortcut ->
            listOf(true, false).forEach { withMarkRead ->
                val key = "$shortcut-$withMarkRead"
                val text = "Synthetic message $key"
                val source = reportedMessage(key, shortcut, withMarkRead, text)
                listener.onNotificationPosted(source)
                val copy = manager.allNotifications.single()
                assertEquals("car_messages", copy.channelId)
                assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(copy.channelId).importance)
                val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(copy)!!
                val cue = app.getString(R.string.car_reply_conversation)
                assertEquals(app.getString(R.string.car_message_with_flack_route, cue, text), style.messages.single().text.toString())
                val reply = carReply(copy)
                assertEquals(app.getString(R.string.reply_conversation_label), reply.title.toString())
                dictate(reply, "Reply for $key")
                val sent = slackReplies().last()
                assertEquals(key, sent.getStringExtra("conversation"))
                assertEquals("Reply for $key", RemoteInput.getResultsFromIntent(sent)!!.getCharSequence(SyntheticSlack.REPLY_KEY).toString())
                listener.onNotificationRemoved(source)
            }
        }
        assertEquals(before + 6, BridgeState.forwarded)
        assertEquals(6, slackReplies().size)
        assertTrue(BridgeState.copyDiagnostics().contains("outcome=POSTED; channel=car_messages"))
    }

    @Test fun anEarlierThreadTimestampSelectsOnlySlacksThreadAction() {
        val source = reportedMessage("thread", timestampStartMillis = 1_700_000_001_000)
        source.notification.extras.putString("com.Slack.thread_ts", "1700000000.000000")
        listener.onNotificationPosted(source)
        val copy = manager.allNotifications.single()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(copy)!!
        assertEquals("Paper room · thread", style.conversationTitle.toString())
        dictate(carReply(copy), "Thread response")
        val sent = slackReplies().single()
        assertEquals("thread-thread", sent.getStringExtra("conversation"))
        assertEquals("Thread response", RemoteInput.getResultsFromIntent(sent)!!.getCharSequence("thread_text").toString())
    }

    @Test fun laterMessagesUnderTheSameSlackKeyPostFreshEntriesWhileTheirSummariesDeduplicate() {
        val before = BridgeState.forwarded
        val messages = listOf("First test message", "Second test message", "Third test message")
        val postedIds = messages.mapIndexed { index, text ->
            val child = reportedMessage("channel-child", text = text,
                history = messages.take(index).map { "Riley Example" to it })
            val matching = reportedMessage("channel-summary", text = text,
                history = messages.take(index).map { "Riley Example" to it })
            val summary = Notification.Builder.recoverBuilder(app, matching.notification)
                .setGroupSummary(true).setShortcutId(null).build()
            listener.onNotificationPosted(child)
            val copy = nm.activeNotifications.single()
            assertEquals(0, copy.notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(copy.notification)!!
            assertEquals(messages.take(index), style.messages.dropLast(1).map { it.text.toString() })
            assertTrue(style.messages.last().text.toString().endsWith(text))
            carReply(copy.notification)
            listener.onNotificationPosted(SyntheticSlack.statusBar(summary, "channel-summary"))
            listener.onNotificationPosted(child)
            assertEquals(copy.id, nm.activeNotifications.single().id)
            copy.id
        }
        assertEquals(messages.size, postedIds.toSet().size)
        assertEquals(before + messages.size, BridgeState.forwarded)
    }

    @Test fun anUnknownAndroidFormatStillForwardsReadableTextWithoutAnAction() {
        val source = Notification.Builder(app, "slack")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Paper room")
            .setContentText("Synthetic plain text with a new reply action")
            .addAction(Notification.Action.Builder(null, "New action", SyntheticSlack.replyIntent(app, "new-format"))
                .addRemoteInput(android.app.RemoteInput.Builder("new_input").build()).setSemanticAction(0).build())
            .build()
        listener.onNotificationPosted(SyntheticSlack.statusBar(source, "new-format"))
        shadowOf(Looper.getMainLooper()).idle()
        val copy = manager.allNotifications.single()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(copy)!!
        assertTrue(style.messages.single().text.toString().startsWith("Synthetic plain text with a new reply action"))
        assertTrue(copy.actions.orEmpty().isEmpty())
        assertEquals(app.getString(R.string.reply_unavailable_label), carReply(copy).title.toString())
        dictate(carReply(copy), "Do not send")
        assertTrue(slackReplies().isEmpty())
        val status = manager.allNotifications.single { it !== copy && it.extras.getCharSequence(Notification.EXTRA_TEXT) ==
            app.getString(R.string.car_reply_status_unavailable) }
        carReply(status)
    }

    @Test fun aForwardDecisionWithPostingDisabledReportsTheDropWithoutACopy() {
        manager.setNotificationsEnabled(false)
        val before = BridgeState.forwarded
        listener.onNotificationPosted(reportedMessage("disabled"))
        assertEquals(before, BridgeState.forwarded)
        assertEquals(0, manager.size())
        assertTrue(BridgeState.filterDiagnostics().contains("row=MESSAGE; action=FORWARD"))
        assertTrue(BridgeState.copyDiagnostics().contains("outcome=NOTIFICATIONS_DISABLED; channel=car_messages"))
        assertTrue(BridgeState.copyDiagnostics().contains("areNotificationsEnabled=false"))
        val activity = Robolectric.buildActivity(StatusActivity::class.java).setup()
        val line = activity.get().findViewById<TextView>(R.id.post_failure)
        assertEquals(View.VISIBLE, line.visibility)
        assertEquals(activity.get().getString(R.string.post_disabled), line.text.toString())
        manager.setNotificationsEnabled(true)
        listener.onNotificationPosted(reportedMessage("enabled-new-message"))
        assertEquals(before + 1, BridgeState.forwarded)
        assertEquals(View.GONE, line.visibility)
        activity.pause().stop().destroy()
    }

    @Test fun aForwardDecisionWithABlockedChannelReportsTheDropAndPreservesTheBlock() {
        nm.createNotificationChannel(NotificationChannel("car_messages", "Car copies", NotificationManager.IMPORTANCE_NONE))
        val before = BridgeState.forwarded
        listener.onNotificationPosted(reportedMessage("blocked"))
        assertEquals(before, BridgeState.forwarded)
        assertEquals(0, manager.size())
        assertEquals(NotificationManager.IMPORTANCE_NONE, nm.getNotificationChannel("car_messages").importance)
        assertTrue(BridgeState.copyDiagnostics().contains("outcome=CHANNEL_BLOCKED; channel=car_messages"))
        assertTrue(BridgeState.copyDiagnostics().contains("importance=0; channelBlocked=true"))
    }

    @Test fun aBlockedChannelGroupIsReportedAndNeverCountedAsForwarded() {
        val group = NotificationChannelGroup("synthetic-group", "Car copies")
        ReflectionHelpers.setField(group, "mBlocked", true)
        nm.createNotificationChannelGroup(group)
        nm.createNotificationChannel(NotificationChannel("car_messages", "Car copies", NotificationManager.IMPORTANCE_HIGH)
            .apply { this.group = group.id })
        val before = BridgeState.forwarded
        listener.onNotificationPosted(reportedMessage("group-blocked"))
        assertEquals(before, BridgeState.forwarded)
        assertEquals(0, manager.size())
        val report = BridgeState.copyDiagnostics()
        assertTrue(report.contains("outcome=CHANNEL_GROUP_BLOCKED; channel=car_messages"))
        assertTrue(report.contains("groupBlocked=true"))
    }

    @Test
    @Config(shadows = [RejectPost::class])
    fun aRejectedSystemPostIsReportedWithoutCountingACopyOrLeakingTheException() {
        val before = BridgeState.forwarded
        listener.onNotificationPosted(reportedMessage("rejected"))
        assertEquals(before, BridgeState.forwarded)
        assertEquals(0, manager.size())
        val report = TestReport.build(app)
        assertTrue(report.contains("outcome=SECURITY_EXCEPTION; channel=car_messages"))
        assertTrue(report.contains("exceptionClass=SecurityException"))
        assertFalse(report.contains("private exception detail"))
    }

    @Test fun failedPostDiagnosticsRetainOnlyTwentyContentFreeAttempts() {
        manager.setNotificationsEnabled(false)
        repeat(25) { listener.onNotificationPosted(reportedMessage("private-source-$it")) }
        val report = TestReport.build(app)
        val attempts = report.substringAfter("Copy post attempts (last 20):").substringBefore("Recent copies (last 20):")
        assertEquals(20, attempts.lines().count { it.trim().startsWith("outcome=") })
        assertTrue(report.length <= 20_000)
        listOf("private-source", "Lunch at noon", "Riley Example", "C12345678", "D12345678").forEach {
            assertFalse(report.contains(it))
        }
    }

    @Implements(NotificationManager::class)
    class RejectPost : ShadowNotificationManager() {
        @Implementation
        override fun notify(tag: String?, id: Int, notification: Notification) {
            throw SecurityException("private exception detail")
        }
    }
}
