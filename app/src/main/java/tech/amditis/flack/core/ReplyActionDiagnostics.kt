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
            val keys = action.freeFormResultKeys.take(10).map {
                it.takeIf { key -> key in SAFE_KEYS } ?: "[key omitted]"
            }
            "  Action ${index + 1}: title=$title; semanticAction=${action.semanticAction}; " +
                "resultKeys=$keys; showsUserInterface=${action.showsUserInterface}; " +
                "allowGeneratedReplies=${action.allowGeneratedReplies}"
        }.joinToString("\n")
        if (cases.size == 20) cases.removeFirst()
        cases.addLast("Actions: ${actions.size}\n$description")
    }

    @Synchronized
    fun report(): String = if (cases.isEmpty()) "" else buildString {
        appendLine("Multiple reply action diagnostics (last 20 cases):")
        cases.forEachIndexed { index, value ->
            appendLine("Case ${index + 1}:")
            appendLine(value)
        }
        appendLine("Unknown labels and result keys are omitted to protect message content.")
    }

    companion object {
        private val SAFE_TITLES = setOf("Reply", "Reply in thread", "Reply to thread", "Smart reply", "Mark as read")
        private val SAFE_KEYS = setOf(
            "reply", "text", "message", "reply_text", "text_reply", "key_text_reply", "KEY_TEXT_REPLY",
            "slack_reply_text", "thread_text", "android.intent.extra.TEXT",
        )
    }
}
