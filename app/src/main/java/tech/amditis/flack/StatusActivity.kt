package tech.amditis.flack

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator

/**
 * Phone-side setup guide and status, used while parked. Five steps, each with
 * its live state and one action, then the forwarding switch and content-free
 * status counts.
 */
class StatusActivity : Activity() {

    private class Step(
        val title: Int,
        val body: Int,
        val action: Int,
        val isDone: () -> Boolean,
        val help: Int? = null,
        val onHelp: () -> Unit = {},
        val onAction: () -> Unit,
    )

    private lateinit var steps: List<Step>
    private lateinit var stepViews: List<View>
    private lateinit var forwarding: MaterialSwitch
    private lateinit var carOnly: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_status)
        applyInsets()

        steps = listOf(
            Step(R.string.step_post_title, R.string.step_post_body, R.string.step_post_action, ::canPost) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST)
                }
            },
            Step(
                R.string.step_access_title, R.string.step_access_body, R.string.step_access_action, ::hasAccess,
                help = R.string.step_access_help, onHelp = ::showRestrictedSettingsHelp,
            ) {
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            Step(R.string.step_battery_title, R.string.step_battery_body, R.string.step_battery_action, ::runsInBackground) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
            Step(R.string.step_auto_title, R.string.step_auto_body, R.string.step_auto_action, { BridgeState.isAndroidAutoConfirmed(this) }) {
                showAndroidAutoHelp()
            },
        )

        val container = findViewById<LinearLayout>(R.id.steps)
        val inflater = LayoutInflater.from(this)
        stepViews = steps.map { step ->
            inflater.inflate(R.layout.item_setup_step, container, false).also { view ->
                view.findViewById<TextView>(R.id.step_title).setText(step.title)
                view.findViewById<TextView>(R.id.step_body).setText(step.body)
                view.findViewById<MaterialButton>(R.id.step_action).apply {
                    setText(step.action)
                    setOnClickListener { step.onAction() }
                }
                step.help?.let { help ->
                    view.findViewById<MaterialButton>(R.id.step_help).apply {
                        setText(help)
                        setOnClickListener { step.onHelp() }
                    }
                }
                container.addView(view)
            }
        }

        findViewById<MaterialButton>(R.id.copy_report).setOnClickListener { copyTestReport() }
        forwarding = findViewById(R.id.forwarding)
        carOnly = findViewById(R.id.car_only)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        findViewById<TextView>(R.id.footer).text = getString(R.string.footer, version)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        var done = 0
        steps.zip(stepViews).forEach { (step, view) ->
            val isDone = step.isDone()
            if (isDone) done++
            view.findViewById<ImageView>(R.id.step_state).apply {
                setImageResource(if (isDone) R.drawable.ic_step_done else R.drawable.ic_step_todo)
            }
            view.findViewById<View>(R.id.step_action).visibility = if (isDone) View.GONE else View.VISIBLE
            view.findViewById<View>(R.id.step_help).visibility = if (isDone || step.help == null) View.GONE else View.VISIBLE
            val state = getString(if (isDone) R.string.step_state_done else R.string.step_state_todo)
            view.contentDescription = "${getString(step.title)}. $state."
        }

        val forwardingOn = BridgeState.isEnabled(this)
        forwarding.setOnCheckedChangeListener(null)
        forwarding.isChecked = forwardingOn
        forwarding.setOnCheckedChangeListener { _, on -> BridgeState.setEnabled(this, on); refresh() }
        if (forwardingOn) done++
        carOnly.setOnCheckedChangeListener(null)
        carOnly.isChecked = BridgeState.isCarOnly(this)
        carOnly.setOnCheckedChangeListener { _, on -> BridgeState.setCarOnly(this, on); refresh() }

        val total = steps.size + 1
        findViewById<TextView>(R.id.setup_title).text =
            if (done == total) getString(R.string.setup_title_done) else getString(R.string.setup_title_progress, done, total)
        findViewById<LinearProgressIndicator>(R.id.setup_progress).apply {
            max = total
            setProgressCompat(done, true)
        }

        findViewById<TextView>(R.id.status_summary).text = getString(
            R.string.status_summary,
            getString(if (forwardingOn) R.string.state_on else R.string.state_off),
            getString(if (BridgeState.carConnected) R.string.listener_connected else R.string.listener_not_connected),
            getString(if (BridgeState.listenerConnected) R.string.listener_connected else R.string.listener_not_connected),
            BridgeState.lastHandoff.label,
        )
        findViewById<TextView>(R.id.diagnostics).text = BridgeState.diagnostics().trim()
    }

    // Android 12 grants notification posting by default; 13 and later ask.
    private fun canPost() = SetupChecks.canPost(this)

    private fun hasAccess() = SetupChecks.hasAccess(this)

    private fun runsInBackground() = SetupChecks.runsInBackground(this)

    private fun copyTestReport() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.test_report_label), TestReport.build(this)))
        Toast.makeText(this, R.string.test_report_copied, Toast.LENGTH_LONG).show()
    }

    /** Android 13+ blocks notification access for sideloaded apps until this is allowed. */
    private fun showRestrictedSettingsHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.step_access_help_title)
            .setMessage(R.string.step_access_help_body)
            .setPositiveButton(R.string.step_access_help_open) { _, _ ->
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
            .show()
    }

    private fun showAndroidAutoHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.step_auto_dialog_title)
            .setMessage(R.string.step_auto_dialog_body)
            .setPositiveButton(R.string.step_auto_confirm) { _, _ ->
                BridgeState.confirmAndroidAuto(this)
                refresh()
            }
            .setNeutralButton(R.string.step_auto_open) { _, _ -> openAndroidAuto() }
            .show()
    }

    private fun openAndroidAuto() {
        val launch = packageManager.getLaunchIntentForPackage(ANDROID_AUTO_PACKAGE)
            ?: Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ANDROID_AUTO_PACKAGE, null))
        if (!open(launch)) Toast.makeText(this, R.string.step_auto_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun open(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    /** Draws edge to edge, padded clear of the status and navigation bars. */
    private fun applyInsets() {
        val content = findViewById<View>(R.id.content)
        val start = content.paddingStart
        val end = content.paddingEnd
        val bottom = content.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updatePadding(left = start + bars.left, right = end + bars.right, top = bars.top, bottom = bottom + bars.bottom)
            insets
        }
    }

    private companion object {
        const val REQUEST_POST = 1
        const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"
    }
}
