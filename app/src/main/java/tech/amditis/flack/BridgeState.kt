package tech.amditis.flack

import android.app.PendingIntent
import android.content.Context
import androidx.core.content.edit
import tech.amditis.flack.core.ConsumeRejection
import tech.amditis.flack.core.Conversation
import tech.amditis.flack.core.RejectReason
import tech.amditis.flack.core.SLACK_PACKAGE
import tech.amditis.flack.core.SessionRegistry
import java.security.SecureRandom
import java.util.EnumMap

/** Result of the last reply, in the words of SPEC.md section 8. Never "delivered". */
enum class HandoffStatus(val label: String) {
    NONE("No reply yet"),
    HANDED_TO_SLACK("Handed to Slack (delivery not confirmed)"),
    HANDOFF_FAILED("Handoff failed: Slack's reply action was cancelled"),
    REJECTED("Reply refused by the bridge"),
}

/**
 * Process-wide state. Everything here is in memory and content-free, except the
 * registry's action handles. Only the on/off switch is saved.
 */
object BridgeState {
    private const val PREFS = "bridge"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_CONFIRMED = "android_auto_confirmed"
    private const val KEY_CAR_ONLY = "car_only"

    private val random = SecureRandom()

    /** Apps whose notifications Flack reads: Slack, plus the fake Slack in debug builds. */
    val sourcePackages: Set<String> = buildSet {
        add(SLACK_PACKAGE)
        if (BuildConfig.FIXTURE_PACKAGE.isNotEmpty()) add(BuildConfig.FIXTURE_PACKAGE)
    }

    val registry = SessionRegistry<PendingIntent>({
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it) }
    })

    // The conversation each car copy shows, so it can be reposted after a reply. Memory only.
    private val conversations = HashMap<Int, Conversation>()

    private val rejections = EnumMap<RejectReason, Int>(RejectReason::class.java)
    private val replyRejections = EnumMap<ConsumeRejection, Int>(ConsumeRejection::class.java)

    @Volatile var listenerConnected = false

    /** True while Android Auto is projecting from this phone. Set by [CarSession]. */
    @Volatile var carConnected = false
    @Volatile var lastHandoff = HandoffStatus.NONE
        private set
    @Volatile var forwarded = 0
        private set
    @Volatile var ambiguous = 0
        private set
    @Volatile var acknowledged = 0
        private set

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
        if (!enabled) CarNotificationPublisher.cancel(context, registry.withdrawAll())
    }

    /** On by default: copies exist only during an Android Auto session (SPEC.md section 5). */
    fun isCarOnly(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_CAR_ONLY, true)

    fun setCarOnly(context: Context, carOnly: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_CAR_ONLY, carOnly) }
        if (!shouldForward(context)) CarNotificationPublisher.cancel(context, registry.withdrawAll())
    }

    /** Forward new messages only when switched on and, unless the user opted out, in the car. */
    fun shouldForward(context: Context): Boolean =
        isEnabled(context) && (carConnected || !isCarOnly(context))

    /** Android Auto's Unknown sources setting cannot be read, so the user confirms it. */
    fun isAndroidAutoConfirmed(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_CONFIRMED, false)

    fun confirmAndroidAuto(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_AUTO_CONFIRMED, true) }
    }

    @Synchronized fun countRejection(reason: RejectReason) {
        rejections[reason] = (rejections[reason] ?: 0) + 1
    }

    @Synchronized fun countReplyRejection(reason: ConsumeRejection) {
        replyRejections[reason] = (replyRejections[reason] ?: 0) + 1
        lastHandoff = HandoffStatus.REJECTED
    }

    @Synchronized fun rememberConversation(carId: Int, conversation: Conversation) { conversations[carId] = conversation }
    @Synchronized fun forgetConversation(carId: Int) { conversations.remove(carId) }
    @Synchronized fun conversation(carId: Int): Conversation? = conversations[carId]

    @Synchronized fun countForwarded() { forwarded++ }
    @Synchronized fun countAmbiguous() { ambiguous++ }
    @Synchronized fun countAcknowledged() { acknowledged++ }
    @Synchronized fun recordHandoff(status: HandoffStatus) { lastHandoff = status }

    /** Text for the status screen. Counts and labels only, never message content. */
    @Synchronized fun diagnostics(): String = buildString {
        appendLine("Forwarded to the car: $forwarded")
        appendLine("Replies disabled as ambiguous: $ambiguous")
        appendLine("Opened in the car: $acknowledged")
        appendLine("Last reply: ${lastHandoff.label}")
        if (rejections.isNotEmpty()) {
            appendLine()
            appendLine("Slack notifications not forwarded:")
            rejections.forEach { (reason, count) -> appendLine("  ${reason.name}: $count") }
        }
        if (replyRejections.isNotEmpty()) {
            appendLine()
            appendLine("Replies refused:")
            replyRejections.forEach { (reason, count) -> appendLine("  ${reason.name}: $count") }
        }
    }
}
