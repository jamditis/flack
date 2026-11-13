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
 * key that exactly matches a fixed list of common ID key names (see
 * [knownKeyName]), so a mapping can name the key.
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
     * the key only when it is on the fixed list in [knownKeyName].
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

    /**
     * The report, at most [maxChars] long. A field summary over all cases comes
     * first. When every case in full does not fit, a case is shown in full only
     * when it is the first with its route or holds a timestamp, and the others
     * become one short line each. Cases that still do not fit are left out, and
     * the report says how many.
     */
    @Synchronized
    fun report(maxChars: Int = Int.MAX_VALUE): String {
        if (cases.isEmpty()) return ""
        val head = buildString {
            appendLine("Route field probe (last $MAX_CASES Slack notifications, shapes and yes/no relations only):")
            appendLine("Shapes: channel (C, G, D), workspace (T, E) and user (U, W) IDs, and message timestamps.")
            appendLine("An extras key name is shown only when its value has one of these shapes and it is exactly on Flack's list of common ID key names.")
            appendLine("Groups use the conversation title and sender name shown, which can repeat across workspaces or people; use distinct test names.")
            appendLine("Relations compare hashed values in memory only. No values are kept in this report.")
            appendLine("Field summary (${cases.size} cases; yes and no count the cases where a relation was checked):")
            summaryLines().forEach { appendLine("  $it") }
        }
        val full = cases.indices.map { ReportEntry(fullCase(it), 1) }
        if (head.length + full.sumOf { it.text.length } <= maxChars) return head + full.joinToString("") { it.text }
        return fitEntries(head, compactEntries(), maxChars, "cases")
    }

    private fun fullCase(index: Int): String = buildString {
        val case = cases[index]
        appendLine("Case ${index + 1}: route=${case.route}; isGroup=${case.isGroup ?: "unknown"}; subtext=${if (case.hasSubText) "present" else "absent"}")
        appendLine("  same conversation label as cases: ${sameConversation(index)}")
        appendLine("  same sender name as cases: ${sameSender(index)}")
        appendLine("  extras: ${case.stringExtras} string, ${case.otherExtras} other")
        case.fields.forEach { field -> appendLine("  ${field.label}: ${describe(index, case, field)}") }
    }

    /** Full cases where they add evidence, and one entry per route for the rest. */
    private fun compactEntries(): List<ReportEntry> {
        val firstOfRoute = cases.indices.distinctBy { cases[it].route }.toSet()
        val full = cases.indices.filter { it in firstOfRoute || cases[it].fields.any { field -> field.shape?.times?.isNotEmpty() == true } }
        val short = (cases.indices - full.toSet()).groupBy { cases[it].route }
        return full.map { ReportEntry(fullCase(it), 1) } + short.map { (route, indices) ->
            val text = buildString {
                appendLine("Cases ${indices.joinToString(", ") { "${it + 1}" }} (${indices.size} cases): route=$route, fields as in the summary")
                indices.forEach { index ->
                    val case = cases[index]
                    val prefixes = case.fields.mapNotNull { field -> field.shape?.prefixes?.ifEmpty { null }?.let { "${field.label} $it" } }
                    appendLine(
                        "  Case ${index + 1}: isGroup=${case.isGroup ?: "unknown"}; same conversation label as cases: ${sameConversation(index)}; " +
                            "same sender name as cases: ${sameSender(index)}; ID prefixes: ${prefixes.ifEmpty { null }?.joinToString(", ") ?: "none"}",
                    )
                }
            }
            ReportEntry(text, indices.size)
        }
    }

    /** One line per field: how often it was present, its ID kinds, and each relation's yes and no counts. */
    private fun summaryLines(): List<String> {
        val labels = cases.flatMap { case -> case.fields.map { it.matchKey to it.label } }.distinctBy { it.first }
        return labels.map { (matchKey, label) ->
            val seen = cases.mapIndexedNotNull { index, case -> case.fields.firstOrNull { it.matchKey == matchKey }?.let { index to it } }
            val present = seen.filter { (_, field) -> field.shape?.empty == false }
            if (present.isEmpty()) return@map "$label: present in 0 of ${cases.size} cases"
            val out = mutableListOf("present in ${present.size} of ${cases.size} cases")
            val prefixes = present.flatMap { (_, field) -> field.shape!!.prefixes.split(' ').filter(String::isNotEmpty) }.toSortedSet()
            if (prefixes.isNotEmpty()) out += "ID prefixes: ${prefixes.joinToString(" ")}"
            present.flatMap { (index, field) -> parts(index, cases[index], field) }
                .firstOrNull { it.text.startsWith("key name=") }?.let { out += it.text }
            val relations = LinkedHashMap<String, IntArray>()
            present.forEach { (index, field) ->
                parts(index, cases[index], field).forEach { part ->
                    val relation = part.relation ?: return@forEach
                    val counts = relations.getOrPut(relation) { IntArray(2) }
                    counts[if (part.yes == true) 0 else 1]++
                }
            }
            relations.forEach { (relation, counts) -> out += "$relation: yes ${counts[0]}, no ${counts[1]}" }
            "$label: ${out.joinToString("; ")}"
        }
    }

    private fun sameConversation(index: Int): String =
        if (cases[index].conversation == null) "unknown" else caseList(index) { it.conversation == cases[index].conversation }

    private fun sameSender(index: Int): String =
        if (cases[index].sender == null) "unknown" else caseList(index) { it.sender == cases[index].sender }

    private fun keyLabel(hashedKey: String): String {
        val number = keyLabels[hashedKey] ?: if (keyLabels.size < MAX_KEYS) (keyLabels.size + 1).also { keyLabels[hashedKey] = it } else null
        return if (number != null) "extra key $number" else "extra key (over $MAX_KEYS)"
    }

    private fun caseList(index: Int, matches: (Case) -> Boolean): String =
        cases.withIndex().filter { it.index != index && matches(it.value) }.map { it.index + 1 }.ifEmpty { null }?.joinToString(", ") ?: "none"

    /** One reported fact. [relation] and [yes] are set for a yes/no relation, so the summary can count it. */
    private class Part(val text: String, val relation: String? = null, val yes: Boolean? = null)

    private fun describe(index: Int, case: Case, field: Field): String {
        val shape = field.shape ?: return "absent"
        if (shape.empty) return "empty"
        return parts(index, case, field).joinToString("; ") { it.text }
    }

    /** What one present field shows in one case. Empty for an absent or empty field. */
    private fun parts(index: Int, case: Case, field: Field): List<Part> {
        val shape = field.shape?.takeIf { !it.empty } ?: return emptyList()
        val parts = mutableListOf(Part("present, ${shape.counts}"))
        fun relation(name: String, yes: Boolean, suffix: String = "") { parts += Part("$name=${yesNo(yes)}$suffix", name, yes) }
        if (shape.prefixes.isNotEmpty()) parts += Part("ID prefixes: ${shape.prefixes}")
        val hasShape = shape.channels.isNotEmpty() || shape.workspaces.isNotEmpty() || shape.users.isNotEmpty() || shape.times.isNotEmpty()
        if (field.label.startsWith("extra key") && hasShape) parts += Part("key name=${field.name ?: "not on the list"}")
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
            if (inside.isNotEmpty()) relation("$name agrees within group", inside.all { it == mine })
            if (outside && others.isNotEmpty()) relation("$name differs outside group", others.all { it.intersect(mine).isEmpty() })
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
            relation("workspace same wherever seen", seen.all { it == shape.workspaces }, ", seen in ${seen.size} of ${cases.size} cases")
        }
        if (shape.times.isNotEmpty()) {
            case.messageTimeMillis?.let { own -> relation("timestamp is this message", shape.times.any { near(it, own) }) }
            val earlier = cases.withIndex().filter { (otherIndex, other) ->
                otherIndex < index && other.messageTimeMillis?.let { time -> shape.times.any { near(it, time) } } == true
            }
            parts += Part(
                "timestamp is message of cases: ${earlier.map { it.index + 1 }.ifEmpty { null }?.joinToString(", ") ?: "none"}",
                "timestamp is an earlier case's message", earlier.isNotEmpty(),
            )
        }
        return parts
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
         * Exact spellings of common ID key names. A guess list, not Slack's
         * documented keys: Slack does not document its extras. A key is shown only
         * when it equals an entry, alone or after Slack's own package prefix, so the
         * report holds only fixed strings and a mapping can use the key as shown.
         */
        private val KNOWN_KEYS = listOf(
            "team", "team_id", "teamId", "workspace", "workspace_id", "workspaceId",
            "enterprise_id", "enterpriseId", "org_id", "orgId",
            "channel", "channel_id", "channelId", "conversation_id", "conversationId", "dm_id", "dmId",
            "user", "user_id", "userId", "sender_id", "senderId", "author_id", "authorId",
            "ts", "message_ts", "messageTs", "msg_ts", "msgTs", "thread_ts", "threadTs",
            "parent_ts", "parentTs", "root_ts", "rootTs", "thread_id", "threadId",
            "message_id", "messageId", "msg_id", "msgId",
        ).toSet()
        private val KNOWN_PREFIXES = listOf("", "$SLACK_PACKAGE.", "$SLACK_PACKAGE.extra.", "slack.", "slack_")

        /** The key itself when it is a [KNOWN_KEYS] entry, alone or after a [KNOWN_PREFIXES] entry. */
        private fun knownKeyName(key: String): String? =
            key.takeIf { KNOWN_PREFIXES.any { prefix -> key.startsWith(prefix) && key.removePrefix(prefix) in KNOWN_KEYS } }

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
