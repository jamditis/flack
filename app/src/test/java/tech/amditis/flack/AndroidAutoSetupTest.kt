package tech.amditis.flack

import android.content.Context
import android.content.DialogInterface
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AndroidAutoSetupTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()

    @Test fun aVersionChangePromptsForAnotherCheckAndKeepsForwardingEnabled() {
        installAndroidAuto(100L)
        BridgeState.setEnabled(app, true)
        BridgeState.setCarOnly(app, false)
        val controller = Robolectric.buildActivity(StatusActivity::class.java).setup()
        val activity = controller.get()
        val steps = activity.findViewById<LinearLayout>(R.id.steps)
        val autoStep = steps.getChildAt(steps.childCount - 1)
        val body = autoStep.findViewById<TextView>(R.id.step_body)
        val action = autoStep.findViewById<TextView>(R.id.step_action)

        assertEquals(activity.getString(R.string.step_auto_body), body.text)
        confirm(action)
        assertEquals(activity.getString(R.string.step_auto_checked), body.text)
        assertEquals(View.VISIBLE, action.visibility)
        assertEquals(activity.getString(R.string.step_auto_recheck), action.text)
        assertTrue(TestReport.build(app).contains("Android Auto Unknown sources check: checked by the user"))

        controller.pause()
        installAndroidAuto(101L)
        controller.resume()
        assertEquals(activity.getString(R.string.step_auto_changed), body.text)
        assertTrue(BridgeState.shouldForward(app))
        assertTrue(TestReport.build(app).contains("Android Auto Unknown sources check: Android Auto version changed; recheck"))

        confirm(action)
        assertEquals(activity.getString(R.string.step_auto_checked), body.text)
        assertTrue(TestReport.build(app).contains("Android Auto Unknown sources check: checked by the user"))
    }

    @Test fun anOlderFlackConfirmationHasNoAndroidAutoVersion() {
        val prefs = app.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("android_auto_confirmed", true).apply()

        assertTrue(TestReport.build(app).contains("Android Auto Unknown sources check: Android Auto unavailable"))
        installAndroidAuto(100L)
        assertTrue(TestReport.build(app).contains("Android Auto Unknown sources check: not checked for this version"))
    }

    private fun confirm(action: View) {
        action.performClick()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun installAndroidAuto(version: Long) {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = ANDROID_AUTO_PACKAGE
            versionName = "test-$version"
            setLongVersionCode(version)
            applicationInfo = ApplicationInfo().apply { packageName = ANDROID_AUTO_PACKAGE }
        })
    }
}
