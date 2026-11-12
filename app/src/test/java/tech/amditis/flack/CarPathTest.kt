package tech.amditis.flack

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
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

/**
 * The Android path end to end, with a synthetic Slack: listener -> car copy ->
 * car reply -> Slack's action. Invented people and text only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CarPathTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val manager get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private lateinit var listener: SlackListenerService

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BridgeState.registry.clear()
        BridgeState.setEnabled(app, true)
        listener = Robolectric.buildService(SlackListenerService::class.java).create().get()
    }

    @After fun tearDown() {
        BridgeState.setEnabled(app, false)
        BridgeState.registry.clear()
    }

    private fun post(sbn: StatusBarNotification) = listener.onNotificationPosted(sbn)

    private fun carCopy() = manager.allNotifications.single()

    private fun replyAction() = carCopy().actions.single { it.remoteInputs?.isNotEmpty() == true }

    /** Simulates Android Auto filling in a dictated reply and firing the car copy's reply action. */
    private fun replyFromCar(text: String) = fire(replyAction(), text)

    /** What Android does when a host fills in a RemoteInput and sends the action. */
    private fun fire(action: android.app.Notification.Action, text: String) {
        val fill = Intent()
        RemoteInput.addResultsToIntent(action.remoteInputs, fill, Bundle().apply { putCharSequence(CarNotificationPublisher.REPLY_KEY, text) })
        val delivered = Intent(shadowOf(action.actionIntent).savedIntent)
        delivered.fillIn(fill, 0)
        CarActionReceiver().onReceive(app, delivered)
    }

    private fun slackReplies() = shadowOf(app).broadcastIntents.filter { it.action == "test.slack.REPLY" }

    @Test fun aDirectMessageBecomesACarMessage() {
        post(SyntheticSlack.dm(app))
        val notification = carCopy()
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals("Lunch at noon?", style.messages.single().text.toString())
        assertEquals(NotificationCompat.CATEGORY_MESSAGE, notification.category)
        val reply = NotificationCompat.getAction(notification, notification.actions.indexOf(replyAction()))!!
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
        assertFalse(reply.showsUserInterface)
        assertFalse(replyAction().actionIntent.isImmutable)
        assertTrue(NotificationCompat.getInvisibleActions(notification).any { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ })
    }

    @Test fun otherAppsAreIgnored() {
        post(SyntheticSlack.dm(app, packageName = "com.example.chat"))
        assertEquals(0, manager.size())
    }

    @Test fun nothingIsForwardedWhileTheSwitchIsOff() {
        BridgeState.setEnabled(app, false)
        post(SyntheticSlack.dm(app))
        assertEquals(0, manager.size())
    }

    @Test fun aCarReplyReachesSlacksActionOnceUnderSlacksKey() {
        post(SyntheticSlack.dm(app))
        replyFromCar("On my way")
        val sent = slackReplies().single()
        val results = RemoteInput.getResultsFromIntent(sent)!!
        assertEquals("On my way", results.getCharSequence(SyntheticSlack.REPLY_KEY).toString())
        assertEquals(HandoffStatus.HANDED_TO_SLACK, BridgeState.lastHandoff)
    }

    @Test fun theCarCopyLosesItsReplyActionAfterAReply() {
        post(SyntheticSlack.dm(app))
        val before = replyAction()
        replyFromCar("On my way")
        assertTrue(carCopy().actions.orEmpty().none { it.remoteInputs?.isNotEmpty() == true })
        // A repeat of the old interaction is refused and sends nothing.
        fire(before, "Again")
        assertEquals(1, slackReplies().size)
    }

    @Test fun anEmptyReplySendsNothingAndKeepsTheToken() {
        post(SyntheticSlack.dm(app))
        replyFromCar("   ")
        assertTrue(slackReplies().isEmpty())
        replyFromCar("Yes")
        assertEquals(1, slackReplies().size)
    }

    @Test fun anOldInteractionCannotSpendANewGenerationsToken() {
        post(SyntheticSlack.dm(app, text = "First"))
        val old = replyAction()
        post(SyntheticSlack.dm(app, text = "Second"))
        assertNotEquals(old.actionIntent, replyAction().actionIntent)
        fire(old, "Late")
        assertTrue(slackReplies().isEmpty())
        replyFromCar("Current")
        assertEquals(1, slackReplies().size)
    }

    @Test fun twoConversationsSharingOneSlackActionAreBothWithdrawn() {
        val shared = SyntheticSlack.replyIntent(app, "shared")
        post(SyntheticSlack.dm(app, key = "dm-riley", reply = shared))
        assertEquals(1, manager.size())
        post(SyntheticSlack.dm(app, key = "dm-sam", sender = "Sam Example", reply = shared))
        assertEquals(0, manager.size())
    }

    @Test fun aReplyActionNotCreatedBySlackIsRefused() {
        post(SyntheticSlack.dm(app, reply = SyntheticSlack.replyIntent(app, "x", creator = "com.example.other")))
        assertEquals(0, manager.size())
    }

    @Test fun removingTheSlackNotificationRemovesTheCarCopy() {
        val sbn = SyntheticSlack.dm(app)
        post(sbn)
        listener.onNotificationRemoved(sbn)
        assertEquals(0, manager.size())
    }

    @Test fun turningTheSwitchOffRemovesCarCopies() {
        post(SyntheticSlack.dm(app))
        BridgeState.setEnabled(app, false)
        assertEquals(0, manager.size())
    }

    @Test fun markAsReadOnlyCountsLocally() {
        post(SyntheticSlack.dm(app))
        val before = BridgeState.acknowledged
        val read = NotificationCompat.getInvisibleActions(carCopy()).single()
        CarActionReceiver().onReceive(app, shadowOf(read.actionIntent).savedIntent)
        assertEquals(before + 1, BridgeState.acknowledged)
        assertTrue(slackReplies().isEmpty())
        assertNotNull(carCopy())
    }

    @Test fun noCarCopyWithoutNotificationPermission() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager.setNotificationsEnabled(false)
        post(SyntheticSlack.dm(app))
        assertEquals(0, manager.size())
        assertNull(manager.allNotifications.firstOrNull())
    }
}
