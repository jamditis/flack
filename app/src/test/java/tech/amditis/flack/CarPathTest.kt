package tech.amditis.flack

import android.Manifest
import android.app.Application
import android.app.Notification
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
import org.junit.Assert.assertNotSame
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
        app.getSharedPreferences("bridge", Application.MODE_PRIVATE).edit().clear().commit()
        BridgeState.registry.clear()
        BridgeState.setEnabled(app, true)
        BridgeState.setCarOnly(app, true)
        listener = Robolectric.buildService(SlackListenerService::class.java).create().get()
        listener.onCarConnectionChanged(true)
    }

    @After fun tearDown() {
        BridgeState.setEnabled(app, false)
        BridgeState.carConnected = false
        BridgeState.registry.clear()
    }

    @Test fun aRefusedReplyStillUpdatesTheCarCopy() {
        post(SyntheticSlack.dm(app))
        val before = carCopy()
        replyFromCar("   ")
        // Reposted so Android Auto stops waiting; the token was not used, so reply stays.
        assertNotSame(before, carCopy())
        assertNotNull(replyAction())
    }

    @Test fun aBlockedCarChannelIsNotCountedAsForwarded() {
        BridgeState.setHidePhoneCopies(app, false)
        CarNotificationPublisher.ensureChannel(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel("car_messages")
        channel.importance = NotificationManager.IMPORTANCE_NONE
        nm.createNotificationChannel(channel)
        val before = BridgeState.forwarded
        post(SyntheticSlack.dm(app))
        assertEquals(before, BridgeState.forwarded)
    }

    @Test fun aRedactedSensitiveNotificationIsRefused() {
        // Android 15 gives untrusted listeners a copy with one message from a nameless sender.
        post(SyntheticSlack.dm(app, sender = "", text = "Sensitive notification content hidden"))
        assertEquals(0, manager.size())
    }

    @Test fun theTestReportNeverContainsMessageTextOrNames() {
        post(SyntheticSlack.dm(app, sender = "Riley Example", text = "Meet at the blue door at six"))
        replyFromCar("Bringing snacks")
        val report = TestReport.build(app)
        assertFalse(report.contains("blue door"))
        assertFalse(report.contains("Riley"))
        assertFalse(report.contains("snacks"))
        assertFalse(report.contains("com.Slack|42"))
        assertTrue(report.contains("Forwarded to the car: "))
        assertTrue(report.contains("Notification access: "))
    }

    @Test fun theTestReportIncludesAmbiguousActionShapesWithoutConversationContent() {
        post(SyntheticSlack.dm(app, sender = "Secret Example", text = "Private meeting at six",
            extraReply = SyntheticSlack.replyIntent(app, "secret-thread")))
        val report = TestReport.build(app)
        assertTrue(report.contains("Multiple reply action diagnostics"))
        assertTrue(report.contains("title=Reply in thread"))
        assertTrue(report.contains("resultKeys=[thread_text]"))
        listOf("Secret Example", "Private meeting", "secret-thread", "com.Slack|42").forEach {
            assertFalse(report.contains(it))
        }
    }

    @Test fun nothingIsForwardedOutsideTheCar() {
        listener.onCarConnectionChanged(false)
        post(SyntheticSlack.dm(app))
        assertEquals(0, manager.size())
    }

    @Test fun leavingTheCarWithdrawsCopiesAndTheirTokens() {
        post(SyntheticSlack.dm(app))
        val held = replyAction()
        listener.onCarConnectionChanged(false)
        assertEquals(0, manager.size())
        fire(held, "Too late")
        assertTrue(slackReplies().isEmpty())
    }

    @Test fun reconnectingDoesNotReplayWhatWasAlreadyForwarded() {
        val sbn = SyntheticSlack.dm(app)
        post(sbn)
        listener.onCarConnectionChanged(false)
        listener.onCarConnectionChanged(true)
        post(sbn)
        assertEquals(0, manager.size())
        post(SyntheticSlack.dm(app, text = "A new message"))
        assertEquals(1, manager.size())
    }

    @Test fun aMessageThatArrivedOutsideTheCarIsNotReplayedOnConnect() {
        listener.onCarConnectionChanged(false)
        val sbn = SyntheticSlack.dm(app)
        post(sbn)
        listener.onCarConnectionChanged(true)
        post(sbn)
        assertEquals(0, manager.size())
    }

    @Test fun aListenerDisconnectForgetsTheCarUntilTheNewObserverReports() {
        // CarConnection answers asynchronously after a reconnect, so a stale "connected"
        // must not let a message through before it does.
        listener.onListenerDisconnected()
        post(SyntheticSlack.dm(app))
        assertEquals(0, manager.size())
    }

    @Test fun turningCarOnlyOffForwardsWithoutACar() {
        listener.onCarConnectionChanged(false)
        BridgeState.setCarOnly(app, false)
        post(SyntheticSlack.dm(app))
        assertEquals(1, manager.size())
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

    private fun reasonCount(reason: String) = Regex("  $reason: (\\d+)")
        .findAll(BridgeState.diagnostics()).sumOf { it.groupValues[1].toInt() }

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

    @Test fun aChannelWithOneSemanticReplyMeetsTheCarContractAndUsesThePhoneRoute() {
        post(SyntheticSlack.dm(app, key = "channel", title = "#paper-kites", isGroup = true,
            extraReply = SyntheticSlack.replyIntent(app, "thread"),
            replySemanticAction = NotificationCompat.Action.SEMANTIC_ACTION_REPLY,
            extraReplySemanticAction = NotificationCompat.Action.SEMANTIC_ACTION_NONE))
        val notification = carCopy()
        val reply = NotificationCompat.getAction(notification, notification.actions.indexOf(replyAction()))!!
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_REPLY, reply.semanticAction)
        assertFalse(reply.showsUserInterface)
        assertEquals(1, reply.remoteInputs!!.size)
        val read = NotificationCompat.getInvisibleActions(notification).single()
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, read.semanticAction)
        assertFalse(read.showsUserInterface)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals("#paper-kites", style.conversationTitle.toString())
        assertTrue(TestReport.build(app).contains("title=Reply in thread"))
        replyFromCar("Synthetic reply")
        assertEquals("channel", slackReplies().single().getStringExtra("conversation"))
    }

    @Test fun everyKnownConversationTypeCanUseItsUniquePhoneNotificationReply() {
        SyntheticSlack.Kind.entries.filter { it != SyntheticSlack.Kind.UNKNOWN }.forEach { kind ->
            val source = SyntheticSlack.dm(app, key = kind.name, title = kind.title, isGroup = kind.group,
                extraReply = SyntheticSlack.replyIntent(app, "${kind.name}-other"),
                extraReplySemanticAction = NotificationCompat.Action.SEMANTIC_ACTION_NONE)
            post(source)
            assertNotNull(replyAction())
            assertTrue(NotificationCompat.getInvisibleActions(carCopy()).any { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ })
            replyFromCar("Synthetic reply for ${kind.name}")
            assertEquals(kind.name, slackReplies().last().getStringExtra("conversation"))
            listener.onNotificationRemoved(source)
            assertEquals(0, manager.size())
        }
    }

    @Test fun aUniqueSemanticThreadReplyNamesTheThreadAndKeepsItsOriginalRoute() {
        post(SyntheticSlack.dm(app, key = "channel", title = "#paper-kites", isGroup = true,
            extraReply = SyntheticSlack.replyIntent(app, "selected-thread"),
            replySemanticAction = NotificationCompat.Action.SEMANTIC_ACTION_NONE))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(carCopy())!!
        assertEquals("#paper-kites · thread", style.conversationTitle.toString())
        replyFromCar("Synthetic thread reply")
        assertEquals("selected-thread", slackReplies().single().getStringExtra("conversation"))
        assertEquals("Synthetic thread reply", RemoteInput.getResultsFromIntent(slackReplies().single())!!.getCharSequence("thread_text").toString())
    }

    @Test fun disconnectClearsCopiesEvenWhenOutsideCarTestingIsEnabled() {
        BridgeState.setCarOnly(app, false)
        post(SyntheticSlack.dm(app))
        val held = replyAction()
        listener.onCarConnectionChanged(false)
        assertEquals(0, manager.size())
        fire(held, "Expired")
        assertTrue(slackReplies().isEmpty())
    }

    @Test fun readOnlyCopiesKeepMarkAsReadWithoutInventingAReply() {
        post(SyntheticSlack.dm(app, extraReply = SyntheticSlack.replyIntent(app, "ambiguous")))
        assertTrue(carCopy().actions.orEmpty().none { it.remoteInputs?.isNotEmpty() == true })
        val read = NotificationCompat.getInvisibleActions(carCopy()).single()
        assertEquals(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ, read.semanticAction)
        assertFalse(read.showsUserInterface)
    }

    @Test fun twoSemanticChannelThreadRepliesStayReadOnlyEvenWithAnExplicitReplyLabel() {
        post(SyntheticSlack.dm(app, key = "thread", title = "Paper room thread", isGroup = true,
            extraReply = SyntheticSlack.replyIntent(app, "thread-reply"),
            replySemanticAction = NotificationCompat.Action.SEMANTIC_ACTION_REPLY,
            extraReplySemanticAction = NotificationCompat.Action.SEMANTIC_ACTION_REPLY))
        assertTrue(carCopy().actions.orEmpty().none { it.remoteInputs?.isNotEmpty() == true })
        assertTrue(slackReplies().isEmpty())
    }

    @Test fun newIncomingMessagesCanAlertAndOwnReplyUpdatesStayQuietWithoutAlteringTheSource() {
        BridgeState.setHidePhoneCopies(app, false)
        val source = SyntheticSlack.dm(app)
        val sourceFlags = source.notification.flags
        post(source)
        val notification = carCopy()
        assertEquals(0, notification.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(Notification.GROUP_ALERT_ALL, notification.groupAlertBehavior)
        assertEquals("flack_car_messages", notification.group)
        assertEquals(sourceFlags, source.notification.flags)
        post(SyntheticSlack.dm(app, text = "Synthetic second incoming message"))
        assertEquals(1, manager.size())
        assertEquals(0, carCopy().flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(Notification.GROUP_ALERT_ALL, carCopy().groupAlertBehavior)
        replyFromCar("Synthetic reply")
        assertTrue(carCopy().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, carCopy().groupAlertBehavior)
        assertEquals(0, carCopy().defaults and (Notification.DEFAULT_SOUND or Notification.DEFAULT_VIBRATE))
        assertNull(carCopy().sound)
        assertNull(carCopy().vibrate)
    }

    @Test fun anOlderCurrentCopyStillCountsAsOpenedAfterDiagnosticHistoryEviction() {
        post(SyntheticSlack.dm(app, key = "oldest"))
        val read = NotificationCompat.getInvisibleActions(carCopy()).single()
        repeat(20) { index -> post(SyntheticSlack.dm(app, key = "new-$index")) }
        val before = BridgeState.acknowledged
        CarActionReceiver().onReceive(app, shadowOf(read.actionIntent).savedIntent)
        assertEquals(before + 1, BridgeState.acknowledged)
    }

    @Test fun quietPhoneCopiesAreTheDefaultAndTheReportDoesNotClaimCarDisplay() {
        assertTrue(BridgeState.hidesPhoneCopies(app))
        post(SyntheticSlack.dm(app))
        assertEquals("car_messages_quiet", carCopy().channelId)
        val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(carCopy().channelId)
        assertEquals(NotificationManager.IMPORTANCE_MIN, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
        assertTrue(TestReport.build(app).contains("channel=car_messages_quiet"))
        assertTrue(TestReport.build(app).contains("Android Auto display: Not reported by the host"))
        val read = NotificationCompat.getInvisibleActions(carCopy()).single()
        CarActionReceiver().onReceive(app, shadowOf(read.actionIntent).savedIntent)
        assertTrue(TestReport.build(app).contains("opened in Android Auto=true"))
    }

    @Test fun switchingPhoneCopyModeClearsOldTokensAndUsesTheAlertChannelForNewMessages() {
        post(SyntheticSlack.dm(app))
        val held = replyAction()
        val activity = Robolectric.buildActivity(StatusActivity::class.java).setup().get()
        val toggle = activity.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.hide_phone_copies)
        assertTrue(toggle.isChecked)
        toggle.performClick()
        assertFalse(BridgeState.hidesPhoneCopies(app))
        assertEquals(0, manager.size())
        fire(held, "Expired")
        assertTrue(slackReplies().isEmpty())
        post(SyntheticSlack.dm(app, text = "Synthetic new message"))
        assertEquals("car_messages", carCopy().channelId)
        assertEquals(Notification.GROUP_ALERT_ALL, carCopy().groupAlertBehavior)
        assertTrue(TestReport.build(app).contains("channel=car_messages"))
        activity.recreate()
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

    @Test fun twoConversationsSharingOneSlackActionAreBothReadOnly() {
        val shared = SyntheticSlack.replyIntent(app, "shared")
        post(SyntheticSlack.dm(app, key = "dm-riley", reply = shared))
        val held = replyAction()
        assertEquals(1, manager.size())
        post(SyntheticSlack.dm(app, key = "dm-sam", sender = "Sam Example", reply = shared))
        assertEquals(2, manager.size())
        assertTrue(manager.allNotifications.all { it.actions.orEmpty().none { action -> action.remoteInputs?.isNotEmpty() == true } })
        fire(held, "Cannot route this")
        assertTrue(slackReplies().isEmpty())
    }

    @Test fun aReplyActionNotCreatedBySlackIsRefused() {
        post(SyntheticSlack.dm(app, reply = SyntheticSlack.replyIntent(app, "x", creator = "com.example.other")))
        assertEquals(1, manager.size())
        assertTrue(carCopy().actions.orEmpty().none { it.remoteInputs?.isNotEmpty() == true })
    }

    @Test fun concurrentTypesAndTwoThreadsDispatchOnlyToTheirOriginalDestinationOnce() {
        val destinations = SyntheticSlack.Kind.entries.filter { it != SyntheticSlack.Kind.UNKNOWN }.map { it to it.name } +
            (SyntheticSlack.Kind.THREAD to "second-thread")
        val held = destinations.map { (kind, key) ->
            post(SyntheticSlack.message(app, kind, key))
            val id = BridgeState.registry.findBinding { it.notificationKey == key }!!.carNotificationId
            val copy = manager.getNotification(id)
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(copy)!!
            assertEquals(kind.group, style.isGroupConversation)
            key to copy.actions.single { it.remoteInputs?.isNotEmpty() == true }
        }
        held.reversed().forEach { (key, action) ->
            fire(action, "Reply for $key")
            fire(action, "Duplicate for $key")
        }
        assertEquals(destinations.size, slackReplies().size)
        slackReplies().forEach { sent ->
            val key = sent.getStringExtra("conversation")!!
            assertEquals("Reply for $key", RemoteInput.getResultsFromIntent(sent)!!.getCharSequence(SyntheticSlack.REPLY_KEY).toString())
        }
    }

    @Test fun unknownAndMissingRepliesStillReadAndTrackTheirLifecycle() {
        val sbn = SyntheticSlack.message(app, SyntheticSlack.Kind.UNKNOWN)
        post(sbn)
        val first = carCopy()
        post(sbn)
        assertEquals(1, manager.size())
        assertTrue(first === carCopy())
        assertTrue(carCopy().actions.orEmpty().none { it.remoteInputs?.isNotEmpty() == true })
        listener.onNotificationRemoved(sbn)
        assertEquals(0, manager.size())
        post(SyntheticSlack.dm(app, reply = null))
        assertEquals(1, manager.size())
        assertTrue(carCopy().actions.orEmpty().none { it.remoteInputs?.isNotEmpty() == true })
        BridgeState.setEnabled(app, false)
        assertEquals(0, manager.size())
    }

    @Test fun aChannelMessageWithoutAReplyActionStaysReadOnlyUntilAFieldMappingExists() {
        val before = reasonCount("NO_FIELD_MAPPING")
        // ID-shaped values in structured fields are not evidence of what they mean.
        val sbn = SyntheticSlack.dm(app, key = "no-action", title = "#paper-kites", isGroup = true, reply = null)
        sbn.notification.extras.putString("test.route", "T0PAPER001:C0KITES001:1700000000.000100")
        post(sbn)
        assertTrue(carCopy().actions.orEmpty().none { it.remoteInputs?.isNotEmpty() == true })
        assertEquals(before + 1, reasonCount("NO_FIELD_MAPPING"))
        assertTrue(BridgeState.diagnostics().contains("Reply offered through the Slack API: 0"))
    }

    @Test fun mixedChannelAndThreadActionsKeepReadoutAndInvalidateHeldReplies() {
        val beforeReasons = reasonCount("MULTIPLE_REPLY_ACTIONS")
        val channel = SyntheticSlack.replyIntent(app, "channel-paper")
        val thread = SyntheticSlack.replyIntent(app, "thread-paper")
        post(SyntheticSlack.dm(app, key = "channel", title = "Paper room", isGroup = true, reply = channel))
        val held = replyAction()
        val mixed = SyntheticSlack.dm(app, key = "mixed", title = "Paper room", isGroup = true, reply = channel, extraReply = thread)
        post(mixed)
        assertEquals(2, manager.size())
        assertTrue(manager.allNotifications.all { it.actions.orEmpty().none { action -> action.remoteInputs?.isNotEmpty() == true } })
        fire(held, "Do not send this")
        assertTrue(slackReplies().isEmpty())
        assertEquals(beforeReasons + 1, reasonCount("MULTIPLE_REPLY_ACTIONS"))
        val before = BridgeState.forwarded
        post(mixed)
        assertEquals(before, BridgeState.forwarded)
        assertEquals(2, manager.size())
    }

    @Test fun losingSafeReplyShapeExpiresTheHeldTokenAndKeepsText() {
        val beforeReasons = reasonCount("NO_CONTEXT")
        post(SyntheticSlack.dm(app, key = "changing"))
        val held = replyAction()
        post(SyntheticSlack.dm(app, key = "changing", isGroup = true))
        assertEquals(1, manager.size())
        assertTrue(carCopy().actions.orEmpty().none { it.remoteInputs?.isNotEmpty() == true })
        fire(held, "Late reply")
        assertTrue(slackReplies().isEmpty())
        assertTrue(BridgeState.diagnostics().contains("Slack notifications read-only (reply not offered):"))
        assertEquals(beforeReasons + 1, reasonCount("NO_CONTEXT"))
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
