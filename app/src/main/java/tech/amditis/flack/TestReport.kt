package tech.amditis.flack

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import tech.amditis.flack.core.SLACK_PACKAGE

/**
 * A plain-text report for a device test result: versions, setup state and
 * counts. It never contains message text, sender names or conversation keys,
 * so it is safe to paste into a public issue.
 */
object TestReport {
    private const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

    fun build(context: Context): String {
        val pm = context.packageManager
        val self = pm.getPackageInfo(context.packageName, 0)
        val buildType = if (BuildConfig.DEBUG) "debug" else "release"
        fun yesNo(value: Boolean) = if (value) "yes" else "no"
        return buildString {
            appendLine("Flack test report (contains no message text)")
            appendLine()
            appendLine("Flack: ${self.versionName} (${self.longVersionCode}, $buildType)")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Slack: ${versionOf(pm, SLACK_PACKAGE)}")
            appendLine("Android Auto: ${versionOf(pm, ANDROID_AUTO_PACKAGE)}")
            appendLine()
            appendLine("Notifications allowed: ${yesNo(SetupChecks.canPost(context))}")
            appendLine("Notification access: ${yesNo(SetupChecks.hasAccess(context))}")
            appendLine("Runs in background: ${yesNo(SetupChecks.runsInBackground(context))}")
            appendLine("Android Auto unknown sources confirmed: ${yesNo(BridgeState.isAndroidAutoConfirmed(context))}")
            appendLine("Forwarding: ${yesNo(BridgeState.isEnabled(context))}")
            appendLine("Only while Android Auto is connected: ${yesNo(BridgeState.isCarOnly(context))}")
            appendLine("Hide Flack copies on the phone: ${yesNo(BridgeState.hidesPhoneCopies(context))}")
            appendLine("Listener connected: ${yesNo(BridgeState.listenerConnected)}")
            appendLine("Android Auto connected: ${yesNo(BridgeState.carConnected)}")
            appendLine()
            append(BridgeState.diagnostics().trim())
            appendLine()
            append(BridgeState.copyDiagnostics())
            val actions = BridgeState.replyActionDiagnostics.report()
            if (actions.isNotEmpty()) {
                appendLine()
                append(actions)
            }
            val routes = BridgeState.routeFieldProbe.report()
            if (routes.isNotEmpty()) {
                appendLine()
                append(routes)
            }
        }
    }

    private fun versionOf(pm: PackageManager, pkg: String): String = try {
        pm.getPackageInfo(pkg, 0).versionName ?: "installed, version unknown"
    } catch (_: PackageManager.NameNotFoundException) {
        "not installed"
    }
}
