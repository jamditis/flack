package tech.amditis.flack

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.amditis.flack.core.Capability
import tech.amditis.flack.core.CapabilityCheck
import tech.amditis.flack.core.RejectReason
import tech.amditis.flack.core.SLACK_PACKAGE

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SlackNotificationParserTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun readsADirectMessage() {
        val parsed = SlackNotificationParser.parse(SyntheticSlack.dm(context))
        val conversation = parsed.facts.conversation!!
        assertEquals(false, conversation.isGroup)
        assertEquals("Riley Example", conversation.messages.single().senderName)
        assertEquals("Lunch at noon?", conversation.messages.single().text)
        assertFalse(conversation.messages.single().fromSelf)
        assertEquals(listOf(SyntheticSlack.REPLY_KEY), parsed.facts.replyActions.single().freeFormResultKeys)
        assertEquals(Capability.Eligible(0, SyntheticSlack.REPLY_KEY), CapabilityCheck.evaluate(parsed.facts))
    }

    @Test fun messagesWithNoSenderAreTheUsersOwn() {
        val parsed = SlackNotificationParser.parse(SyntheticSlack.dm(context, sender = null, extraMessages = listOf("Riley Example" to "Hi")))
        val messages = parsed.facts.conversation!!.messages
        assertFalse(messages.first().fromSelf)
        assertTrue(messages.last().fromSelf)
    }

    @Test fun aMissingGroupFlagIsUnknownAndFailsClosed() {
        val parsed = SlackNotificationParser.parse(SyntheticSlack.dm(context, isGroup = null))
        assertNull(parsed.facts.conversation!!.isGroup)
        assertEquals(Capability.ReadOnly(RejectReason.UNKNOWN_CONVERSATION), CapabilityCheck.evaluate(parsed.facts))
    }

    @Test fun groupConversationsAreRecognized() {
        val parsed = SlackNotificationParser.parse(SyntheticSlack.dm(context, isGroup = true, title = "Project room"))
        assertEquals(true, parsed.facts.conversation!!.isGroup)
    }

    @Test fun choiceOnlyRepliesAreNotFreeForm() {
        val parsed = SlackNotificationParser.parse(SyntheticSlack.dm(context, freeForm = false))
        assertTrue(parsed.facts.replyActions.single().freeFormResultKeys.isEmpty())
        assertEquals(listOf(SyntheticSlack.REPLY_KEY), parsed.facts.replyActions.single().resultKeys)
    }

    @Test fun readsActionMetadataWithoutRemoteInputChoices() {
        val notification = Notification.Builder(context, "slack")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .addAction(Notification.Action.Builder(null, "Reply in thread", SyntheticSlack.replyIntent(context, "metadata"))
                .addRemoteInput(android.app.RemoteInput.Builder("thread_text").build())
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
                .setAllowGeneratedReplies(true)
                .build())
            .build()
        val action = SlackNotificationParser.parse(SyntheticSlack.statusBar(notification, "metadata")).facts.replyActions.single()
        assertEquals("Reply in thread", action.title)
        assertEquals(Notification.Action.SEMANTIC_ACTION_REPLY, action.semanticAction)
        assertTrue(action.allowGeneratedReplies)
        assertEquals(listOf("thread_text"), action.freeFormResultKeys)
    }

    @Test fun anActionThatOpensAnActivityShowsUserInterface() {
        val activityIntent = PendingIntent.getActivity(context, 7, Intent(Intent.ACTION_VIEW), PendingIntent.FLAG_MUTABLE)
        shadowOf(activityIntent).setCreatorPackage(SLACK_PACKAGE)
        shadowOf(activityIntent).setCreatorUid(SyntheticSlack.SLACK_UID)
        val parsed = SlackNotificationParser.parse(SyntheticSlack.dm(context, reply = activityIntent))
        assertTrue(parsed.facts.replyActions.single().showsUserInterface)
        assertEquals(Capability.ReadOnly(RejectReason.REPLY_OPENS_APP), CapabilityCheck.evaluate(parsed.facts))
    }

    @Test fun anImmutableActionIsMarked() {
        val immutable = PendingIntent.getBroadcast(context, 8, Intent("test.slack.REPLY").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        val parsed = SlackNotificationParser.parse(SyntheticSlack.dm(context, reply = immutable))
        assertTrue(parsed.facts.replyActions.single().isImmutable)
    }

    @Test fun creatorPackageAndUidAreReported() {
        val foreign = SyntheticSlack.replyIntent(context, "x", creator = "com.example.other", creatorUid = 999)
        val facts = SlackNotificationParser.parse(SyntheticSlack.dm(context, reply = foreign)).facts.replyActions.single()
        assertEquals("com.example.other", facts.creatorPackage)
        assertEquals(999, facts.creatorUid)
        assertEquals(SyntheticSlack.SLACK_UID, facts.sourceUid)
    }

    @Test fun groupSummariesAreFlagged() {
        val summary = NotificationCompat.Builder(context, "slack")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setGroup("g")
            .setGroupSummary(true)
            .build()
        val parsed = SlackNotificationParser.parse(SyntheticSlack.statusBar(summary, "summary"))
        assertTrue(parsed.facts.isGroupSummary)
        assertNull(parsed.facts.conversation)
    }

    @Test fun aPlainTextNotificationIsReadOnly() {
        val plain = Notification.Builder(context, "slack").setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Paper room").setContentText("Hi").setCategory(Notification.CATEGORY_MESSAGE).build()
        val parsed = SlackNotificationParser.parse(SyntheticSlack.statusBar(plain, "plain"))
        assertEquals("Hi", parsed.facts.conversation!!.messages.single().text)
        assertEquals(Capability.ReadOnly(RejectReason.UNKNOWN_CONVERSATION), CapabilityCheck.evaluate(parsed.facts))
    }

    @Test fun plainStatusCallAndProgressNotificationsAreNotMessages() {
        listOf(null, Notification.CATEGORY_CALL, Notification.CATEGORY_PROGRESS, Notification.CATEGORY_SERVICE).forEach { category ->
            val status = Notification.Builder(context, "slack").setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("Paper activity").setContentText("Still working").setCategory(category).build()
            assertNull(SlackNotificationParser.parse(SyntheticSlack.statusBar(status, "status")).facts.conversation)
        }
        listOf(Notification.FLAG_ONGOING_EVENT, Notification.FLAG_FOREGROUND_SERVICE).forEach { flag ->
            val status = Notification.Builder(context, "slack").setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setContentTitle("Paper activity").setContentText("Still working").setCategory(Notification.CATEGORY_MESSAGE).build()
            status.flags = status.flags or flag
            assertNull(SlackNotificationParser.parse(SyntheticSlack.statusBar(status, "ongoing")).facts.conversation)
        }
    }

    @Test fun allKnownConversationFixturesPreserveTheirContextAndReply() {
        SyntheticSlack.Kind.entries.filter { it != SyntheticSlack.Kind.UNKNOWN }.forEach { kind ->
            val parsed = SlackNotificationParser.parse(SyntheticSlack.message(context, kind))
            assertEquals(kind.title, parsed.facts.conversation!!.title)
            assertEquals(kind.group, parsed.facts.conversation!!.isGroup)
            assertEquals(Capability.Eligible(0, SyntheticSlack.REPLY_KEY), CapabilityCheck.evaluate(parsed.facts))
        }
    }

    @Test fun fingerprintTracksContentNotIdentity() {
        val a = SlackNotificationParser.parse(SyntheticSlack.dm(context, text = "One")).fingerprint
        val b = SlackNotificationParser.parse(SyntheticSlack.dm(context, text = "One")).fingerprint
        val c = SlackNotificationParser.parse(SyntheticSlack.dm(context, text = "Two")).fingerprint
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test fun collectsRouteFieldsButNotAndroidDisplayKeys() {
        val notification = NotificationCompat.Builder(context, "slack")
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("#paper-kites")
            .setContentText("Lunch at noon?")
            .setSubText("Paper Kites")
            .setShortcutId("T0INVENTED1:C0INVENTED2")
            .setLocusId(androidx.core.content.LocusIdCompat("C0INVENTED2"))
            .build()
        notification.extras.putString("invented.channel", "C0INVENTED2")
        notification.extras.putInt("invented.count", 3)
        @Suppress("DEPRECATION")
        val sbn = object : android.service.notification.StatusBarNotification(
            SLACK_PACKAGE, SLACK_PACKAGE, 7, "1700000000.000100", SyntheticSlack.SLACK_UID, 0, 0, notification,
            android.os.Process.myUserHandle(), 0L,
        ) {}
        val fields = SlackNotificationParser.parse(sbn).routeFields
        assertEquals("T0INVENTED1:C0INVENTED2", fields.shortcutId)
        assertEquals("C0INVENTED2", fields.locusId)
        assertEquals("1700000000.000100", fields.tag)
        assertEquals(listOf("invented.channel" to "C0INVENTED2"), fields.extras.filter { it.first.startsWith("invented.") })
        assertTrue(fields.extras.none { it.first.startsWith("android.") })
        assertTrue(fields.otherExtras >= 1)
        assertTrue(fields.hasSubText)
    }
}
