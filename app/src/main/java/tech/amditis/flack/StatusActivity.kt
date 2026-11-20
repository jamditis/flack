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
import tech.amditis.flack.core.WorkspaceToken

/**
 * Phone-side setup guide and status, used while parked. Five steps, each with
 * its live state and one action, then the forwarding switch and content-free
 * status counts.
 */
class StatusActivity : Activity() {

    private data class StepState(val done: Boolean, val body: Int)

    private class Step(
        val title: Int,
        val action: Int,
        val state: () -> StepState,
        val recheckAction: Int? = null,
        val help: Int? = null,
        val onHelp: () -> Unit = {},
        val onAction: () -> Unit,
    )

    private lateinit var steps: List<Step>
    private lateinit var stepViews: List<View>
    private lateinit var forwarding: MaterialSwitch
    private lateinit var carOnly: MaterialSwitch
    private lateinit var hidePhoneCopies: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_status)
        applyInsets()

        steps = listOf(
            Step(R.string.step_post_title, R.string.step_post_action, { StepState(canPost(), R.string.step_post_body) }) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_POST)
                }
            },
            Step(
                R.string.step_access_title, R.string.step_access_action, { StepState(hasAccess(), R.string.step_access_body) },
                help = R.string.step_access_help, onHelp = ::showRestrictedSettingsHelp,
            ) {
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            Step(R.string.step_battery_title, R.string.step_battery_action, { StepState(runsInBackground(), R.string.step_battery_body) }) {
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            },
            Step(R.string.step_auto_title, R.string.step_auto_action, ::androidAutoStep, recheckAction = R.string.step_auto_recheck) {
                showAndroidAutoHelp()
            },
        )

        val container = findViewById<LinearLayout>(R.id.steps)
        val inflater = LayoutInflater.from(this)
        stepViews = steps.map { step ->
            inflater.inflate(R.layout.item_setup_step, container, false).also { view ->
                view.findViewById<TextView>(R.id.step_title).setText(step.title)
                view.findViewById<MaterialButton>(R.id.step_action).apply {
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

        findViewById<MaterialButton>(R.id.signin_add).setOnClickListener {
            if (!SlackSession.startSignIn(this)) Toast.makeText(this, R.string.signin_no_browser, Toast.LENGTH_LONG).show()
        }
        findViewById<MaterialButton>(R.id.copy_report).setOnClickListener { copyTestReport() }
        forwarding = findViewById(R.id.forwarding)
        carOnly = findViewById(R.id.car_only)
        hidePhoneCopies = findViewById(R.id.hide_phone_copies)
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        findViewById<TextView>(R.id.footer).text = getString(R.string.footer, version)
    }

    override fun onResume() {
        super.onResume()
        SlackSession.onChange = ::refreshSignIn
        BridgeState.onStatusChange = { runOnUiThread { refreshWarnings() } }
        BridgeState.updateHealth.refresh(this)
        refresh()
        refreshSignIn()
    }

    override fun onPause() {
        SlackSession.onChange = null
        BridgeState.onStatusChange = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        var done = 0
        steps.zip(stepViews).forEach { (step, view) ->
            val (isDone, body) = step.state()
            if (isDone) done++
            view.findViewById<ImageView>(R.id.step_state).apply {
                setImageResource(if (isDone) R.drawable.ic_step_done else R.drawable.ic_step_todo)
            }
            view.findViewById<TextView>(R.id.step_body).setText(body)
            view.findViewById<MaterialButton>(R.id.step_action).apply {
                setText(if (isDone) step.recheckAction ?: step.action else step.action)
                visibility = if (isDone && step.recheckAction == null) View.GONE else View.VISIBLE
            }
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
        hidePhoneCopies.setOnCheckedChangeListener(null)
        hidePhoneCopies.isChecked = BridgeState.hidesPhoneCopies(this)
        hidePhoneCopies.setOnCheckedChangeListener { _, on -> BridgeState.setHidePhoneCopies(this, on); refresh() }

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
        refreshWarnings()
    }

    private fun refreshWarnings() {
        val message = when (BridgeState.lastPostOutcome) {
            CopyPostOutcome.NOTIFICATIONS_DISABLED -> R.string.post_disabled
            CopyPostOutcome.CHANNEL_BLOCKED -> R.string.post_channel_blocked
            CopyPostOutcome.CHANNEL_GROUP_BLOCKED -> R.string.post_group_blocked
            CopyPostOutcome.CHANNEL_MISSING -> R.string.post_channel_missing
            CopyPostOutcome.SECURITY_EXCEPTION -> R.string.post_rejected
            CopyPostOutcome.POSTED, null -> null
        }
        findViewById<TextView>(R.id.post_failure).apply {
            text = message?.let { getString(it) }.orEmpty()
            visibility = if (message == null) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.platform_update).apply {
            val warning = BridgeState.updateHealth.phoneMessage(this@StatusActivity)
            text = warning.orEmpty()
            visibility = if (warning == null) View.GONE else View.VISIBLE
        }
    }

    /** Lists signed-in workspaces. Names show on the phone only, never in the test report. */
    private fun refreshSignIn() {
        val configured = SlackSession.isConfigured
        renderSignInState(configured, null)
        val list = findViewById<LinearLayout>(R.id.workspaces)
        SlackSession.loadWorkspaces(this) { result ->
            renderSignInState(configured, result)
            list.removeAllViews()
            result.getOrNull().orEmpty().sortedBy { it.teamName }.forEach { workspace ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                row.addView(
                    TextView(this).apply {
                        text = if (workspace.needsSignIn) {
                            getString(R.string.signin_workspace_expired, workspace.teamName)
                        } else {
                            getString(R.string.signin_workspace, workspace.teamName, workspace.userName)
                        }
                        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                row.addView(
                    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        setText(R.string.signin_sign_out)
                        contentDescription = getString(R.string.signin_sign_out_label, workspace.teamName)
                        minHeight = (48 * resources.displayMetrics.density).toInt()
                        setOnClickListener { confirmSignOut(workspace.teamId, workspace.teamName) }
                    },
                )
                list.addView(row)
            }
        }
    }

    /** Null is still loading; a failed read must never claim the person signed out. */
    internal fun renderSignInState(configured: Boolean, result: Result<List<WorkspaceToken>>?) {
        val body = getString(if (configured) R.string.signin_body else R.string.signin_not_configured)
        findViewById<TextView>(R.id.signin_body).text =
            if (configured && result?.getOrNull()?.isEmpty() == true) {
                getString(R.string.signin_required) + "\n\n" + body
            } else body
        findViewById<View>(R.id.signin_add).visibility = if (configured) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.signin_message).apply {
            text = if (result?.isFailure == true) getString(R.string.signin_read_error) else SlackSession.lastMessage
            visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun confirmSignOut(teamId: String, teamName: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.signin_sign_out_confirm, teamName))
            .setPositiveButton(R.string.signin_sign_out) { _, _ -> SlackSession.signOut(this, teamId, teamName) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun androidAutoStep(): StepState {
        val setup = BridgeState.androidAutoSetup(this, SetupChecks.androidAutoPackage(this)?.longVersionCode)
        val body = when (setup) {
            AndroidAutoSetup.UNAVAILABLE -> R.string.step_auto_unavailable
            AndroidAutoSetup.UNCONFIRMED -> R.string.step_auto_body
            AndroidAutoSetup.CONFIRMED -> R.string.step_auto_checked
            AndroidAutoSetup.VERSION_CHANGED -> R.string.step_auto_changed
        }
        return StepState(setup == AndroidAutoSetup.CONFIRMED, body)
    }

    private fun showAndroidAutoHelp() {
        val version = SetupChecks.androidAutoPackage(this)?.longVersionCode
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.step_auto_dialog_title)
            .setMessage(R.string.step_auto_dialog_body)
            .setNeutralButton(R.string.step_auto_open) { _, _ -> openAndroidAuto() }
        if (version != null) {
            dialog.setPositiveButton(R.string.step_auto_confirm) { _, _ ->
                BridgeState.confirmAndroidAuto(this, version)
                refresh()
            }
        }
        dialog.show()
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
    }
}
