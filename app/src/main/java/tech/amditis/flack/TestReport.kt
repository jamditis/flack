package tech.amditis.flack

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import tech.amditis.flack.core.REPORT_LIMIT
import tech.amditis.flack.core.SLACK_PACKAGE

/**
 * A plain-text report for a device test result: versions, setup state and
 * counts. It never contains message text, sender names or conversation keys,
 * so it is safe to paste into a public issue.
 */
object TestReport {
    fun build(context: Context): String {
        val pm = context.packageManager
        val self = pm.getPackageInfo(context.packageName, 0)
        val buildType = if (BuildConfig.DEBUG) "debug" else "release"
        val androidAuto = SetupChecks.androidAutoPackage(context)
        val autoSetup = BridgeState.androidAutoSetup(context, androidAuto?.longVersionCode)
        fun yesNo(value: Boolean) = if (value) "yes" else "no"
        return buildString {
            appendLine("Flack test report (contains no message text)")
            appendLine()
            appendLine("Flack: ${self.versionName} (${self.longVersionCode}, $buildType)")
            appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}")
            appendLine("Slack: ${versionOf(pm, SLACK_PACKAGE)}")
            appendLine("Android Auto: ${if (androidAuto == null) "not installed" else androidAuto.versionName ?: "installed, version unknown"}")
            appendLine()
            appendLine("Notifications allowed: ${yesNo(SetupChecks.canPost(context))}")
            appendLine("Notification access: ${yesNo(SetupChecks.hasAccess(context))}")
            appendLine("Runs in background: ${yesNo(SetupChecks.runsInBackground(context))}")
            appendLine("Android Auto Unknown sources check: ${autoSetup.label}")
            appendLine("Forwarding: ${yesNo(BridgeState.isEnabled(context))}")
            appendLine("Only while Android Auto is connected: ${yesNo(BridgeState.isCarOnly(context))}")
            appendLine("Quiet Flack copies on the phone: ${yesNo(BridgeState.hidesPhoneCopies(context))}")
            appendLine("Listener connected: ${yesNo(BridgeState.listenerConnected)}")
            appendLine("Android Auto connected: ${yesNo(BridgeState.carConnected)}")
            appendLine()
            append(BridgeState.diagnostics().trim())
            appendLine()
            append(BridgeState.filterDiagnostics())
            append(BridgeState.copyDiagnostics())
        }.let { head ->
            // Keep the bounded action evidence when route history is full. Reserve
            // its actual size, up to half the report, then give routes the remainder.
            val actionReserve = BridgeState.replyActionDiagnostics.report(REPORT_LIMIT / 2).length
            val routes = BridgeState.routeFieldProbe.report(REPORT_LIMIT - head.length - 2 - actionReserve)
            val actions = BridgeState.replyActionDiagnostics.report(REPORT_LIMIT - head.length - routes.length - 2)
            listOf(head, routes, actions).filter { it.isNotEmpty() }.joinToString("\n")
        }
    }

    private fun versionOf(pm: PackageManager, pkg: String): String = try {
        pm.getPackageInfo(pkg, 0).versionName ?: "installed, version unknown"
    } catch (_: PackageManager.NameNotFoundException) {
        "not installed"
    }
}
