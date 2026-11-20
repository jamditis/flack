package tech.amditis.flack

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.amditis.flack.core.SLACK_PACKAGE

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlatformUpdateHealthTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val initial = PlatformVersions(10L, 20L, "example-build")

    @Before fun clearSetup() {
        app.getSharedPreferences("platform_versions", Application.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun firstRunAndUnchangedVersionsAreQuietAndEachRealChangeAsksForARecheck() {
        listOf(
            initial.copy(slack = 11L) to "Slack",
            initial.copy(androidAuto = 21L) to "Android Auto",
            initial.copy(androidBuild = "later-build") to "Android",
        ).forEach { (changed, component) ->
            clearSetup()
            val health = PlatformUpdateHealth()
            health.refresh(app, initial)
            health.refresh(app, initial)
            assertNull(health.phoneMessage(app))
            assertTrue(health.report().isEmpty())

            health.refresh(app, changed)
            assertEquals(app.getString(R.string.update_check_setup, component), health.phoneMessage(app))
            assertTrue(health.report().contains("Post-update local copies: 0 of 0"))
        }
    }

    @Test fun anUnresolvedCheckSurvivesRestartAndARealReplyClearsIt() {
        val health = PlatformUpdateHealth()
        val updated = initial.copy(slack = 11L)
        health.refresh(app, initial)
        health.refresh(app, updated)
        repeat(3) { health.recordAttempt(app, posted = false) }
        health.refresh(app, updated)
        assertEquals(app.resources.getQuantityString(R.plurals.update_no_copies, 3, "Slack", 3), health.phoneMessage(app))

        val restarted = PlatformUpdateHealth()
        restarted.refresh(app, updated)
        assertEquals(app.getString(R.string.update_check_setup, "Slack"), restarted.phoneMessage(app))
        restarted.recordAttempt(app, posted = true, replyOffered = true)
        assertNull(restarted.phoneMessage(app))
        assertTrue(restarted.report().contains("Post-update replies offered: 1 of 1"))

        val recovered = PlatformUpdateHealth()
        recovered.refresh(app, updated)
        assertNull(recovered.phoneMessage(app))
    }

    @Test fun replyChecksWaitForApiResultsAndIgnoreAnOlderUpdateGeneration() {
        val health = PlatformUpdateHealth()
        health.refresh(app, initial)
        health.refresh(app, initial.copy(slack = 11L))
        val pending = List(4) { health.recordAttempt(app, posted = true) }
        assertEquals(app.getString(R.string.update_check_setup, "Slack"), health.phoneMessage(app))
        pending.take(3).forEach { health.recordApiReply(app, it, offered = false) }
        assertEquals(app.resources.getQuantityString(R.plurals.update_no_replies, 3, "Slack", 3), health.phoneMessage(app))
        health.recordApiReply(app, pending.last(), offered = true)
        assertNull(health.phoneMessage(app))

        health.refresh(app, initial.copy(slack = 11L, androidAuto = 21L))
        health.recordApiReply(app, pending.last(), offered = true)
        assertEquals(app.getString(R.string.update_check_setup, "Android Auto"), health.phoneMessage(app))
        assertTrue(health.report().contains("Post-update replies offered: 0 of 0"))
    }

    @Test fun aSlackReplacementUpdatesThePhoneAndDistinguishesNoCopiesFromNoReplies() {
        installSlack(10)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        BridgeState.registry.clear()
        BridgeState.setEnabled(app, true)
        BridgeState.setCarOnly(app, true)
        val listener = Robolectric.buildService(SlackListenerService::class.java).create()
        listener.get().activeSources = { emptyArray() }
        listener.get().onCarConnectionChanged(false)
        val status = Robolectric.buildActivity(StatusActivity::class.java).setup()
        val notice = status.get().findViewById<TextView>(R.id.platform_update)
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
        try {
            assertEquals(View.GONE, notice.visibility)
            installSlack(11)
            app.sendBroadcast(Intent(Intent.ACTION_PACKAGE_REPLACED, Uri.fromParts("package", SLACK_PACKAGE, null)))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(app.getString(R.string.update_check_setup, "Slack"), notice.text)

            // The first shapes can be observed before a projection session. These callbacks are not attempts.
            listener.get().onNotificationPosted(SyntheticSlack.dm(app, key = "outside-car"))
            assertTrue(TestReport.build(app).contains("Post-update local copies: 0 of 0"))
            listener.get().onCarConnectionChanged(true)
            assertTrue(BridgeState.shouldForward(app))

            notifications.setNotificationsEnabled(false)
            val blocked = SyntheticSlack.dm(app, key = "blocked-1")
            listener.get().onNotificationPosted(blocked)
            listener.get().onNotificationPosted(blocked)
            listener.get().onNotificationPosted(SyntheticSlack.dm(app, key = "blocked-2"))
            val unreadable = Notification.Builder(app, "synthetic").setCategory(Notification.CATEGORY_MESSAGE).build()
            listener.get().onNotificationPosted(SyntheticSlack.statusBar(unreadable, "unreadable"))
            assertEquals(app.resources.getQuantityString(R.plurals.update_no_copies, 3, "Slack", 3), notice.text)

            notifications.setNotificationsEnabled(true)
            repeat(3) { index ->
                listener.get().onNotificationPosted(SyntheticSlack.dm(app, key = "mixed-$index", text = "Synthetic paper message",
                    extraReply = SyntheticSlack.replyIntent(app, "thread-$index")))
            }
            assertEquals(app.resources.getQuantityString(R.plurals.update_no_replies, 3, "Slack", 3), notice.text)
            val report = TestReport.build(app)
            assertTrue(report.contains("First distinct Slack notification shapes after the update"))
            assertTrue(report.contains("textActions=2"))
            assertTrue(report.contains("semantic/input counts=[1/1,1/1]"))
            assertTrue(report.contains("Post-update local copies: 3 of 6"))
            assertFalse(report.contains("Synthetic paper message"))
            assertFalse(report.contains("mixed-0"))

            val summarySource = SyntheticSlack.dm(app, key = "summary", text = "Later paper update", reply = null)
            val summary = Notification.Builder.recoverBuilder(app, summarySource.notification)
                .setGroup("paper-updates").setGroupSummary(true).build()
            listener.get().onNotificationPosted(SyntheticSlack.statusBar(summary, "summary"))
            assertEquals(app.resources.getQuantityString(R.plurals.update_no_replies, 4, "Slack", 4), notice.text)
            val childSource = SyntheticSlack.dm(app, key = "child", text = "Later paper update")
            val child = Notification.Builder.recoverBuilder(app, childSource.notification).setGroup("paper-updates").build()
            listener.get().onNotificationPosted(SyntheticSlack.statusBar(child, "child"))
            assertEquals(View.GONE, notice.visibility)
            val recovered = TestReport.build(app)
            assertTrue(recovered.contains("Post-update local copies: 4 of 7"))
            assertTrue(recovered.contains("Post-update replies offered: 1 of 4"))
        } finally {
            status.pause().stop().destroy()
            listener.destroy()
            BridgeState.setEnabled(app, false)
            BridgeState.carConnected = false
            BridgeState.registry.clear()
        }
    }

    private fun installSlack(version: Long) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = SLACK_PACKAGE
            versionName = "example-$version"
            setLongVersionCode(version)
            applicationInfo = ApplicationInfo().apply { packageName = SLACK_PACKAGE }
        })
    }
}
