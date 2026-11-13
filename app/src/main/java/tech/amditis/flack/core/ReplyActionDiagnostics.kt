package tech.amditis.flack.core

/** Bounded, memory-only action shapes for #23. Never retains a notification or its content. */
class ReplyActionDiagnostics {
    private val cases = ArrayDeque<String>()

    @Synchronized
    fun record(actions: List<ReplyActionFacts>) {
        // Apps can put arbitrary text in labels and input names. Export only static
        // values so the diagnostic report cannot become a message content dump.
        val description = actions.take(10).mapIndexed { index, action ->
            val title = action.title?.takeIf { it in SAFE_TITLES } ?: "[label omitted]"
            val keys = action.resultKeys.take(10).map {
                it.takeIf { key -> key in SAFE_KEYS } ?: "[key omitted]"
            }
            "  Action ${index + 1}: title=$title; semanticAction=${action.semanticAction}; " +
                "resultKeys=$keys; showsUserInterface=${action.showsUserInterface}; " +
                "allowGeneratedReplies=${action.allowGeneratedReplies}"
        }.joinToString("\n")
        if (cases.size == 20) cases.removeFirst()
        cases.addLast("Actions: ${actions.size}\n$description")
    }

    /**
     * The report, at most [maxChars] long. When every case does not fit, cases
     * with the same action shapes become one entry that lists their numbers, and
     * entries that still do not fit are left out with a count.
     */
    @Synchronized
    fun report(maxChars: Int = Int.MAX_VALUE): String {
        if (cases.isEmpty()) return ""
        val head = "Multiple reply action diagnostics (last 20 cases):\n" +
            "Unknown labels and result keys are omitted to protect message content.\n"
        val full = cases.mapIndexed { index, value -> ReportEntry("Case ${index + 1}:\n$value\n", 1) }
        if (head.length + full.sumOf { it.text.length } <= maxChars) return head + full.joinToString("") { it.text }
        val same = cases.withIndex().groupBy({ it.value }, { it.index + 1 })
        val entries = same.map { (value, numbers) -> ReportEntry("Cases ${numbers.joinToString(", ")} (${numbers.size} cases):\n$value\n", numbers.size) }
        return fitEntries(head, entries, maxChars, "action cases")
    }

    companion object {
        private val SAFE_TITLES = setOf("Reply", "Reply in thread", "Reply to thread", "Smart reply", "Mark as read")
        private val SAFE_KEYS = setOf(
            "reply", "text", "message", "reply_text", "text_reply", "key_text_reply", "KEY_TEXT_REPLY",
            "slack_reply_text", "thread_text", "android.intent.extra.TEXT",
        )
    }
}
