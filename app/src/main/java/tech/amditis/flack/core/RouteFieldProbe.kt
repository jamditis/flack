package tech.amditis.flack.core

import java.security.MessageDigest
import kotlin.math.abs

/**
 * Structured fields of a Slack notification that might name its destination
 * (docs/SLACK_SIGN_IN.md). Memory only: values can be identifiers.
 */
data class RouteFields(
    val shortcutId: String? = null,
    val locusId: String? = null,
    val tag: String? = null,
    /** String extras outside Android's own `android.` keys, which hold display text. */
    val extras: List<Pair<String, String>> = emptyList(),
    /** Extras with other value types, counted only. */
    val otherExtras: Int = 0,
    /** Whether Android's subtext is set. Slack may put the workspace name there; only presence is recorded. */
    val hasSubText: Boolean = false,
    /** Person key and URI of the newest incoming message's sender. */
    val senderKey: String? = null,
    val senderUri: String? = null,
)

/** What the probe compares between notifications. Held in memory as hashes, never exported. */
data class ProbeContext(
    val conversationTitle: String?,
    val senderName: String?,
    val messageTimeMillis: Long?,
)

/**
 * Finds which notification fields could carry Slack's workspace, conversation,
 * sender and thread IDs. For each field it reports how many values have an ID or
 * timestamp shape, and yes/no relations between notifications: whether IDs agree
 * within one conversation and differ between conversations, and whether a
 * timestamp equals this message's time or an earlier message's time (a thread
 * parent). It never exports a value, title, name or message text, so the report
 * stays safe to paste into a public issue. The one name it shows is an extras
 * key that matches a fixed list of common ID key names (see [knownKeyName]), and
 * then only the list's own spelling, so a mapping can name the key.
 */
class RouteFieldProbe {
    /**
     * What one field looked like. Only counts, hashed IDs and timestamps: the raw
     * value is dropped at once, so no message content stays in memory here.
     */
    private class Shape(
        val empty: Boolean,
        val counts: String,
        /** Prefix letters of the IDs found, such as "C D T": Slack IDs are not secret, the letter is the kind. */
        val prefixes: String,
        val channels: Set<String>,
        val workspaces: Set<String>,
        val users: Set<String>,
        val times: List<Long>,
    )

    /**
     * [matchKey] is hashed for extras, whose key names can hold anything. [name] is
     * the matching entry of [KNOWN_KEYS], never the key itself.
     */
    private class Field(val label: String, val matchKey: String, val shape: Shape?, val name: String? = null)

    private class Case(
        val route: String,
        val isGroup: Boolean?,
        val fields: List<Field>,
        val stringExtras: Int,
        val otherExtras: Int,
        val hasSubText: Boolean,
        /** Null when the conversation cannot be told apart; such cases are left out of comparisons. */
        val conversation: String?,
        val sender: String?,
        val messageTimeMillis: Long?,
    )

    private val cases = ArrayDeque<Case>()

    // Each extras key gets a local number for this process, the same in every case.
    // Keyed by hash, so key names are not kept.
    private val keyLabels = LinkedHashMap<String, Int>()

    @Synchronized
    fun record(route: String, isGroup: Boolean?, fields: RouteFields, context: ProbeContext) {
        val list = buildList {
            add(Field("shortcutId", "shortcutId", shape(fields.shortcutId)))
            add(Field("locusId", "locusId", shape(fields.locusId)))
            add(Field("tag", "tag", shape(fields.tag)))
            add(Field("senderKey", "senderKey", shape(fields.senderKey)))
            add(Field("senderUri", "senderUri", shape(fields.senderUri)))
            fields.extras.take(MAX_EXTRAS).forEach { (key, value) ->
                val hashed = hash("extra|$key")
                add(Field(keyLabel(hashed), hashed, shape(value), knownKeyName(key)))
            }
        }
        // A titled conversation is one conversation. A one-to-one DM without a title
        // is its sender. Anything else is unknown, not "different".
        // Blank titles count as absent, as in CapabilityCheck.contextLabel.
        val title = context.conversationTitle?.takeIf { it.isNotBlank() }
        val sender = context.senderName?.takeIf { it.isNotBlank() }
        val conversation = when {
            title != null -> hash("$isGroup|$title")
            isGroup == false && sender != null -> hash("dm|$sender")
            else -> null
        }
        if (cases.size == MAX_CASES) cases.removeFirst()
        cases.addLast(
            Case(
                route, isGroup, list, fields.extras.size, fields.otherExtras, fields.hasSubText,
                conversation = conversation,
                sender = sender?.let { hash(it) },
                messageTimeMillis = context.messageTimeMillis,
            ),
        )
    }

    @Synchronized
    fun report(): String = if (cases.isEmpty()) "" else buildString {
        appendLine("Route field probe (last $MAX_CASES Slack notifications, shapes and yes/no relations only):")
        cases.forEachIndexed { index, case ->
            appendLine("Case ${index + 1}: route=${case.route}; isGroup=${case.isGroup ?: "unknown"}; subtext=${if (case.hasSubText) "present" else "absent"}")
            appendLine("  same conversation label as cases: ${if (case.conversation == null) "unknown" else caseList(index) { it.conversation == case.conversation }}")
            appendLine("  same sender name as cases: ${if (case.sender == null) "unknown" else caseList(index) { it.sender == case.sender }}")
            appendLine("  extras: ${case.stringExtras} string, ${case.otherExtras} other")
            case.fields.forEach { field -> appendLine("  ${field.label}: ${describe(index, case, field)}") }
        }
        appendLine("Shapes: channel (C, G, D), workspace (T, E) and user (U, W) IDs, and message timestamps.")
        appendLine("An extras key name is shown only when its value has one of these shapes and it is on Flack's list of common ID key names, in the list's spelling.")
        appendLine("Groups use the conversation title and sender name shown, which can repeat across workspaces or people; use distinct test names.")
        appendLine("Relations compare hashed values in memory only. No values are kept in this report.")
    }

    private fun keyLabel(hashedKey: String): String {
        val number = keyLabels[hashedKey] ?: if (keyLabels.size < MAX_KEYS) (keyLabels.size + 1).also { keyLabels[hashedKey] = it } else null
        return if (number != null) "extra key $number" else "extra key (over $MAX_KEYS)"
    }

    private fun caseList(index: Int, matches: (Case) -> Boolean): String =
        cases.withIndex().filter { it.index != index && matches(it.value) }.map { it.index + 1 }.ifEmpty { null }?.joinToString(", ") ?: "none"

    private fun describe(index: Int, case: Case, field: Field): String {
        val shape = field.shape ?: return "absent"
        if (shape.empty) return "empty"
        val parts = mutableListOf("present, ${shape.counts}")
        if (shape.prefixes.isNotEmpty()) parts += "ID prefixes: ${shape.prefixes}"
        val hasShape = shape.channels.isNotEmpty() || shape.workspaces.isNotEmpty() || shape.users.isNotEmpty() || shape.times.isNotEmpty()
        if (field.label.startsWith("extra key") && hasShape) parts += "key name=${field.name ?: "not on the list"}"
        // Compares this field's IDs with the same field in other cases. [group] is
        // null when either case's grouping is unknown; those cases are skipped.
        // [outside] is off where different groups can share an ID, as conversations share a workspace.
        fun compare(group: (Case) -> Boolean?, ids: (Shape) -> Set<String>, name: String, outside: Boolean = true) {
            val mine = ids(shape)
            if (mine.isEmpty()) return
            val compared = cases.withIndex().filter { it.index != index }.mapNotNull { (_, other) ->
                val same = group(other) ?: return@mapNotNull null
                val theirs = other.fields.firstOrNull { it.matchKey == field.matchKey }?.shape?.let(ids).orEmpty()
                if (theirs.isEmpty()) null else same to theirs
            }
            val inside = compared.filter { it.first }.map { it.second }
            val others = compared.filter { !it.first }.map { it.second }
            if (inside.isNotEmpty()) parts += "$name agrees within group=${yesNo(inside.all { it == mine })}"
            if (outside && others.isNotEmpty()) parts += "$name differs outside group=${yesNo(others.all { it.intersect(mine).isEmpty() })}"
        }
        compare(
            { other -> if (case.conversation == null || other.conversation == null) null else other.conversation == case.conversation },
            { it.channels }, "channel (group: same conversation label)",
        )
        compare(
            { other -> if (case.sender == null || other.sender == null) null else other.sender == case.sender },
            { it.users }, "user (group: same sender name)",
        )
        compare(
            { other -> if (case.conversation == null || other.conversation == null) null else other.conversation == case.conversation },
            { it.workspaces }, "workspace (group: same conversation label)", outside = false,
        )
        if (shape.workspaces.isNotEmpty()) {
            val seen = cases.mapNotNull { other -> other.fields.firstOrNull { it.matchKey == field.matchKey }?.shape?.workspaces?.ifEmpty { null } }
            parts += "workspace same wherever seen=${yesNo(seen.all { it == shape.workspaces })}, seen in ${seen.size} of ${cases.size} cases"
        }
        if (shape.times.isNotEmpty()) {
            case.messageTimeMillis?.let { own -> parts += "timestamp is this message=${yesNo(shape.times.any { near(it, own) })}" }
            val earlier = cases.withIndex().filter { (otherIndex, other) ->
                otherIndex < index && other.messageTimeMillis?.let { time -> shape.times.any { near(it, time) } } == true
            }
            parts += "timestamp is message of cases: ${earlier.map { it.index + 1 }.ifEmpty { null }?.joinToString(", ") ?: "none"}"
        }
        return parts.joinToString("; ")
    }

    companion object {
        private const val MAX_CASES = 20
        private const val MAX_EXTRAS = 30
        /** Slack timestamps and Android message times can differ slightly. */
        private const val TIME_TOLERANCE_MILLIS = 2_000L

        private const val MAX_KEYS = 100

        // A prefix letter, then 8 or more uppercase letters or digits (docs/SLACK_SIGN_IN.md).
        // Lowercase text may touch an ID, as in "channelC0123ABCD".
        private const val ID_BODY = "[A-Z0-9]{8,}"
        private val CHANNEL = Regex("(?<![A-Z0-9])[CGD]$ID_BODY(?![A-Z0-9])")
        private val WORKSPACE = Regex("(?<![A-Z0-9])[TE]$ID_BODY(?![A-Z0-9])")
        private val USER = Regex("(?<![A-Z0-9])[UW]$ID_BODY(?![A-Z0-9])")
        private val TIMESTAMP = Regex("(?<![0-9.])[0-9]{10}\\.[0-9]{6}(?![0-9])")

        private fun shape(value: String?): Shape? {
            value ?: return null
            fun ids(regex: Regex) = regex.findAll(value).map { hash(it.value) }.toSet()
            val prefixes = listOf(CHANNEL, WORKSPACE, USER).flatMap { regex -> regex.findAll(value).map { it.value.first() } }
                .toSortedSet().joinToString(" ")
            val counts = "channel=${CHANNEL.findAll(value).count()} workspace=${WORKSPACE.findAll(value).count()} " +
                "user=${USER.findAll(value).count()} timestamp=${TIMESTAMP.findAll(value).count()}"
            return Shape(
                empty = value.isEmpty(),
                counts = counts,
                prefixes = prefixes,
                channels = ids(CHANNEL),
                workspaces = ids(WORKSPACE),
                users = ids(USER),
                times = TIMESTAMP.findAll(value).mapNotNull { timestampMillis(it.value) }.toList(),
            )
        }
        /**
         * Common spellings of ID key names, lowercase without separators. A guess
         * list, not Slack's documented keys: Slack does not document its extras.
         * Only these fixed strings can appear in a report, so a key that holds a
         * name or other text never does.
         */
        private val KNOWN_KEYS = listOf(
            "team", "teamid", "workspace", "workspaceid", "enterprise", "enterpriseid", "org", "orgid",
            "channel", "channelid", "conversation", "conversationid", "dm", "dmid",
            "user", "userid", "sender", "senderid", "author", "authorid",
            "ts", "messagets", "msgts", "threadts", "parentts", "rootts", "thread", "threadid",
            "message", "messageid", "msgid",
        ).toSet()

        /**
         * The [KNOWN_KEYS] entry for an extras key, compared without case, `_` or `-`.
         * For a dotted key only the part after the last dot is compared, and the
         * prefix is never shown.
         */
        private fun knownKeyName(key: String): String? {
            val normalized = key.substringAfterLast('.').lowercase().replace("_", "").replace("-", "")
            return normalized.takeIf { it in KNOWN_KEYS }
        }

        private fun yesNo(value: Boolean) = if (value) "yes" else "no"
        private fun near(a: Long, b: Long) = abs(a - b) <= TIME_TOLERANCE_MILLIS

        /** Slack's "seconds.microseconds" timestamp in milliseconds. */
        fun timestampMillis(ts: String): Long? {
            val (seconds, micros) = ts.split('.').takeIf { it.size == 2 } ?: return null
            return (seconds.toLongOrNull() ?: return null) * 1000 + (micros.toLongOrNull() ?: return null) / 1000
        }

        private fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
