package tech.amditis.flack

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import tech.amditis.flack.core.FilterDecision
import tech.amditis.flack.core.NotificationFeatures
import tech.amditis.flack.core.ReplyActionFacts
import tech.amditis.flack.core.SLACK_PACKAGE

internal data class PlatformVersions(val slack: Long?, val androidAuto: Long?, val androidBuild: String) {
    companion object {
        fun read(context: Context) = PlatformVersions(
            SetupChecks.packageInfo(context, SLACK_PACKAGE)?.longVersionCode,
            SetupChecks.androidAutoPackage(context)?.longVersionCode,
            "${Build.FINGERPRINT}\n${Build.VERSION.SECURITY_PATCH}",
        )
    }
}

/** Main-thread checks after a platform change. Version markers persist. Samples stay in memory. */
internal class PlatformUpdateHealth(private val onChange: () -> Unit = {}) {
    private enum class Warning { CHECK_SETUP, NO_COPIES, NO_REPLIES }

    private class Check(val generation: Long, val components: String) {
        val firstShapes = linkedSetOf<String>()
        var attempts = 0
        var posted = 0
        var repliesChecked = 0
        var repliesOffered = 0
    }

    private var generation = 0L
    private var check: Check? = null

    fun refresh(context: Context, versions: PlatformVersions = PlatformVersions.read(context)) {
        val settings = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = mapOf(
            "Slack" to (versions.slack?.toString() ?: "absent"),
            "Android Auto" to (versions.androidAuto?.toString() ?: "absent"),
            "Android" to versions.androidBuild,
        )
        val saved = current.keys.associateWith { settings.getString(it, null) }
        val changed = current.keys.filter { saved[it] != null && saved[it] != current[it] }
        val pending = settings.getStringSet(PENDING, emptySet()).orEmpty()
        if (changed.isNotEmpty() || saved.containsValue(null)) {
            settings.edit {
                current.forEach { (component, version) -> putString(component, version) }
                if (changed.isNotEmpty()) putStringSet(PENDING, pending + changed)
            }
        }
        if (changed.isNotEmpty() || (check == null && pending.isNotEmpty())) {
            check = Check(++generation, current.keys.filter { it in pending || it in changed }.joinToString(", "))
            onChange()
        }
    }

    fun recordShape(features: NotificationFeatures, decision: FilterDecision, actions: List<ReplyActionFacts>, textActions: Int) {
        val active = check ?: return
        if (active.firstShapes.size < SAMPLE_LIMIT) {
            val inputs = actions.take(ACTION_LIMIT).joinToString(",") { "${it.semanticAction}/${it.freeFormResultKeys.size}" }
            active.firstShapes.add(
                "row=${decision.row}; action=${decision.action}; actions=${actions.size}; textActions=$textActions; " +
                    "semantic/input counts=[$inputs]; ${features.report()}",
            )
        }
    }

    /** Returns a generation only for a posted copy whose API confirmation is still pending. */
    fun recordAttempt(context: Context, posted: Boolean, replyOffered: Boolean? = null): Long? {
        val active = check ?: return null
        active.attempts++
        if (posted) active.posted++
        if (posted && replyOffered != null) recordReply(context, active, replyOffered)
        onChange()
        return active.generation.takeIf { posted && replyOffered == null }
    }

    fun recordApiReply(context: Context, generation: Long?, offered: Boolean) {
        val active = check?.takeIf { it.generation == generation } ?: return
        recordReply(context, active, offered)
        onChange()
    }

    private fun recordReply(context: Context, active: Check, offered: Boolean) {
        active.repliesChecked++
        if (!offered) return
        active.repliesOffered++
        if (active.repliesOffered == 1) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(PENDING) }
        }
    }

    fun phoneMessage(context: Context): String? {
        val active = check ?: return null
        return when (warning(active)) {
            Warning.CHECK_SETUP -> context.getString(R.string.update_check_setup, active.components)
            Warning.NO_COPIES -> context.getString(R.string.update_no_copies, active.components, active.attempts)
            Warning.NO_REPLIES -> context.getString(R.string.update_no_replies, active.components, active.repliesChecked)
            null -> null
        }
    }

    private fun warning(active: Check): Warning? = when {
        active.repliesOffered > 0 -> null
        active.attempts >= WARNING_MINIMUM && active.posted == 0 -> Warning.NO_COPIES
        active.repliesChecked >= WARNING_MINIMUM -> Warning.NO_REPLIES
        else -> Warning.CHECK_SETUP
    }

    fun report(): String = buildString {
        val active = check ?: return@buildString
        appendLine("Platform changes: ${active.components}")
        appendLine("Post-update local copies: ${active.posted} of ${active.attempts} new message notifications")
        appendLine("Post-update replies offered: ${active.repliesOffered} of ${active.repliesChecked} evaluated copies")
        appendLine("First distinct Slack notification shapes after the update (up to $SAMPLE_LIMIT):")
        active.firstShapes.forEach { appendLine("  $it") }
    }

    private companion object {
        const val PREFS = "platform_versions"
        const val PENDING = "recheck_pending"
        const val SAMPLE_LIMIT = 5
        const val ACTION_LIMIT = 6
        // Wait for a small batch before treating zero observed results as a pattern.
        const val WARNING_MINIMUM = 3
    }
}
