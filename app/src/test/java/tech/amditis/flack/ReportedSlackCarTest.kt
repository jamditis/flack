package tech.amditis.flack

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
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

/** Synthetic equivalents of the content-free 0.3.3 car report; no device dump. */
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
        listener.resolveApi = { throw AssertionError("The original Slack action must precede the API route") }
    }

    @After fun tearDown() {
        BridgeState.setEnabled(app, false)
        BridgeState.carConnected = false
        BridgeState.registry.clear()
    }

    private fun reportedMessage(key: String, group: Boolean = true): android.service.notification.StatusBarNotification {
        val source = SyntheticSlack.dm(app, key = key, title = if (group) "#paper-kites" else null,
            isGroup = group, shortcutId = if (group) "C12345678" else "D12345678",
            extraReply = SyntheticSlack.replyIntent(app, "$key-thread"),
            replySemanticAction = 0, extraReplySemanticAction = 0)
        val actions = source.notification.actions
        val read = Notification.Action.Builder(null, "Mark as read", SyntheticSlack.replyIntent(app, "$key-read"))
            .setSemanticAction(2).setShowsUserInterface(false).build()
        val notification = Notification.Builder.recoverBuilder(app, source.notification).setGroup("synthetic-slack").build()
        notification.actions = arrayOf(read, actions[1], actions[0])
        return SyntheticSlack.statusBar(notification, key)
    }

    @Test fun reportedChannelAndDmShapesPostAndDispatchOnlyTheirPlainReplyOnce() {
        val before = BridgeState.forwarded
        listOf(true, false).forEach { group ->
            val key = if (group) "channel" else "dm"
            val source = reportedMessage(key, group)
            listener.onNotificationPosted(source)
            val copy = manager.allNotifications.single()
            assertEquals("car_messages", copy.channelId)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(copy.channelId).importance)
            assertNotNull(NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(copy))
            assertTrue(NotificationCompat.getInvisibleActions(copy).any { it.semanticAction == 2 })
            val reply = copy.actions.single { it.semanticAction == 1 }
            val fill = Intent()
            RemoteInput.addResultsToIntent(reply.remoteInputs, fill,
                Bundle().apply { putCharSequence(CarNotificationPublisher.REPLY_KEY, "Synthetic car reply") })
            val delivered = Intent(shadowOf(reply.actionIntent).savedIntent).apply { fillIn(fill, 0) }
            CarActionReceiver().onReceive(app, delivered)
            CarActionReceiver().onReceive(app, delivered)
            val sent = shadowOf(app).broadcastIntents.filter { it.action == "test.slack.REPLY" }
            assertEquals(1, sent.count { it.getStringExtra("conversation") == key })
            assertFalse(sent.any { it.getStringExtra("conversation") == "$key-thread" })
            assertEquals("Synthetic car reply", RemoteInput.getResultsFromIntent(sent.last())
                .getCharSequence(SyntheticSlack.REPLY_KEY).toString())
            listener.onNotificationRemoved(source)
        }
        assertEquals(before + 2, BridgeState.forwarded)
        assertTrue(BridgeState.copyDiagnostics().contains("outcome=POSTED; channel=car_messages"))
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
