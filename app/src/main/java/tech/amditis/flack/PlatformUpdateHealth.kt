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

    internal class Attempt internal constructor(internal val generation: Long) {
        internal var posted = false
        internal var replyOffered: Boolean? = null
    }

    private class Check(val generation: Long, val components: String) {
        val firstShapes = linkedSetOf<String>()
        val summaryAttempts = HashMap<Int, Attempt>()
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

    fun recordAttempt(context: Context, posted: Boolean, replyOffered: Boolean? = null, summaryCopyId: Int? = null): Attempt? {
        val active = check ?: return null
        active.attempts++
        val attempt = Attempt(active.generation)
        if (summaryCopyId != null) active.summaryAttempts[summaryCopyId] = attempt
        recordOutcome(context, active, attempt, posted, replyOffered)
        onChange()
        return attempt.takeIf { posted && replyOffered == null }
    }

    /** A child can improve the outcome of an already counted summary. */
    fun recordReplacement(context: Context, copyId: Int, posted: Boolean, replyOffered: Boolean?): Attempt? {
        val active = check ?: return null
        val attempt = active.summaryAttempts.remove(copyId) ?: return null
        recordOutcome(context, active, attempt, posted, replyOffered)
        onChange()
        return attempt.takeIf { posted && replyOffered == null }
    }

    fun forgetCopy(copyId: Int) {
        check?.summaryAttempts?.remove(copyId)
    }

    fun recordApiReply(context: Context, attempt: Attempt?, offered: Boolean) {
        if (attempt == null) return
        val active = check?.takeIf { it.generation == attempt.generation } ?: return
        recordOutcome(context, active, attempt, posted = true, replyOffered = offered)
        onChange()
    }

    private fun recordOutcome(context: Context, active: Check, attempt: Attempt, posted: Boolean, replyOffered: Boolean?) {
        if (!posted) return
        if (!attempt.posted) {
            active.posted++
            attempt.posted = true
        }
        // Count whether this message ever offered a reply during the check.
        if (replyOffered == null || attempt.replyOffered == true) return
        if (attempt.replyOffered == null) active.repliesChecked++
        attempt.replyOffered = replyOffered
        if (!replyOffered) return
        active.repliesOffered++
        if (active.repliesOffered == 1) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(PENDING) }
        }
    }

    fun phoneMessage(context: Context): String? {
        val active = check ?: return null
        return when (warning(active)) {
            Warning.CHECK_SETUP -> context.getString(R.string.update_check_setup, active.components)
            Warning.NO_COPIES -> context.resources.getQuantityString(
                R.plurals.update_no_copies, active.attempts, active.components, active.attempts,
            )
            Warning.NO_REPLIES -> context.resources.getQuantityString(
                R.plurals.update_no_replies, active.repliesChecked, active.components, active.repliesChecked,
            )
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
