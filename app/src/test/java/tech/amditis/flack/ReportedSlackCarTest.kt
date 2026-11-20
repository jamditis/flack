package tech.amditis.flack

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.view.View
import android.widget.TextView
import androidx.core.app.NotificationCompat
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
    ): StatusBarNotification {
        val group = !shortcut.startsWith("D")
        val source = SyntheticSlack.dm(app, key = key, title = if (group) "Paper room" else null,
            text = text, isGroup = group, shortcutId = shortcut,
            extraReply = SyntheticSlack.replyIntent(app, "$key-thread"),
            replySemanticAction = 0, extraReplySemanticAction = 0, replyShowsUserInterface = true)
        val actions = source.notification.actions
        val read = Notification.Action.Builder(null, "Mark as read", SyntheticSlack.replyIntent(app, "$key-read"))
            .setSemanticAction(2).build()
        val notification = Notification.Builder.recoverBuilder(app, source.notification).setGroup("synthetic-slack").build()
        notification.actions = if (withMarkRead) arrayOf(read, actions[1], actions[0]) else arrayOf(actions[1], actions[0])
        return SyntheticSlack.statusBar(notification, key)
    }

    @Test fun bothReportedActionListsForwardDmsChannelsAndGroupDmsWithoutGuessingAReply() {
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
                assertTrue(style.messages.single().text.toString().startsWith(text))
                assertTrue(style.messages.single().text.toString().contains(app.getString(R.string.car_reply_thread_unknown)))
                assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ,
                    NotificationCompat.getInvisibleActions(copy).single().semanticAction)
                assertTrue(copy.actions.orEmpty().isEmpty())
                assertFalse(BridgeState.registry.findBinding { it.notificationKey == source.key }!!.canReply)
                listener.onNotificationRemoved(source)
            }
        }
        assertEquals(before + 6, BridgeState.forwarded)
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == "test.slack.REPLY" })
        assertTrue(BridgeState.copyDiagnostics().contains("outcome=POSTED; channel=car_messages"))
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
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ,
            NotificationCompat.getInvisibleActions(copy).single().semanticAction)
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
