package tech.amditis.flack.core

/** Replies longer than this are refused, never shortened (SPEC.md section 8). */
const val MAX_REPLY_LENGTH = 4000

/** Provisional product limit from SPEC.md section 7, not an Android limit. */
const val DEFAULT_MAX_BINDINGS = 50

/** One forwarded conversation and its current single-use reply token. */
data class Binding<A : Any>(
    val notificationKey: String,
    val generation: Long,
    val token: String,
    /** Stable per source key, so updates replace the same car notification. */
    val carNotificationId: Int,
    /** Null for a read-only copy. Its token can never dispatch. */
    val action: A?,
    val resultKey: String?,
)

sealed interface Observation<A : Any> {
    /** A new generation to publish. [alert] is false when the newest message is the user's own. */
    data class Publish<A : Any>(
        val binding: Binding<A>,
        val alert: Boolean,
        val evicted: List<Binding<A>>,
        val replyDisabled: List<Binding<A>> = emptyList(),
    ) : Observation<A>

    /** The same content seen again. Nothing changes and no new token is made. */
    class Unchanged<A : Any> : Observation<A>

    /** Present when the listener connected. Not a new arrival, so not forwarded. */
    class Baseline<A : Any> : Observation<A>

}

enum class ConsumeRejection { UNKNOWN_OR_EXPIRED, ALREADY_CONSUMED, NO_REPLY_ACTION, EMPTY, TOO_LONG }

sealed interface ConsumeResult<A : Any> {
    data class Dispatch<A : Any>(val binding: Binding<A>, val text: String) : ConsumeResult<A>
    /** [binding] is the current binding when the token was known, so its copy can be refreshed. */
    data class Rejected<A : Any>(val reason: ConsumeRejection, val binding: Binding<A>? = null) : ConsumeResult<A>
}

/**
 * In-memory reply bindings, keyed by source notification.
 *
 * Rules (SPEC.md sections 6 to 8):
 * - Every content change makes a new generation and a new token. Old tokens die.
 * - A token dispatches at most once. Invalid text does not use it up.
 * - An action seen on two notifications disables replies on both; readout stays.
 * - Nothing here is written to disk.
 *
 * [A] is the platform's action handle. On Android it is the PendingIntent, whose
 * equals() compares the target operation.
 */
class SessionRegistry<A : Any>(
    private val newToken: () -> String,
    private val maxBindings: Int = DEFAULT_MAX_BINDINGS,
) {
    private class Entry<A : Any>(val binding: Binding<A>, val fingerprint: String, var consumed: Boolean)

    // Insertion order is update order, so the first entry is the oldest.
    private val entries = LinkedHashMap<String, Entry<A>>()
    private val baseline = HashMap<String, String>()
    private val conflicted = HashSet<String>()
    private val carIds = HashMap<String, Int>()

    /**
     * The actions last seen on every live Slack notification, forwarded or not.
     * A reply whose action also appears on any other notification is disabled
     * until that source notification goes away.
     */
    private val knownActions = HashMap<String, Set<A>>()
    private var nextGeneration = 1L
    private var nextCarId = 1

    /** An eligible notification already present when the listener connected. */
    @Synchronized
    fun recordBaseline(notificationKey: String, fingerprint: String, action: A?, actions: Collection<A> = listOfNotNull(action)) {
        if (notificationKey in entries) return
        baseline[notificationKey] = fingerprint
        knownActions[notificationKey] = actions.toSet()
    }

    /**
     * A Slack notification that cannot be forwarded. Its actions are remembered so
     * no forwarded conversation can share them. Returns this key's removed binding
     * and current read-only bindings whose reply actions were disabled.
     */
    @Synchronized
    fun recordOther(notificationKey: String, actions: Collection<A>): List<Binding<A>> {
        baseline.remove(notificationKey)
        knownActions[notificationKey] = actions.toSet()
        val withdrawn = mutableListOf<Binding<A>>()
        entries.remove(notificationKey)?.let { withdrawn.add(it.binding) }
        withdrawn.addAll(disableReplies(clashingKeys(notificationKey, actions)))
        return withdrawn
    }

    @Synchronized
    fun observe(
        notificationKey: String,
        fingerprint: String,
        action: A?,
        resultKey: String?,
        newestFromSelf: Boolean,
        actions: Collection<A> = listOfNotNull(action),
    ): Observation<A> {
        val previousActions = knownActions[notificationKey]
        knownActions[notificationKey] = actions.toSet()
        val clashes = clashingKeys(notificationKey, actions)
        val disabled = disableReplies(clashes)
        if (clashes.isNotEmpty()) conflicted.add(notificationKey)
        val safeAction = action.takeUnless { notificationKey in conflicted }
        val safeKey = resultKey.takeIf { safeAction != null }
        val existing = entries[notificationKey]
        // Same content and the same reply capability: a repeated callback.
        if (existing != null &&
            disabled.isEmpty() &&
            existing.fingerprint == fingerprint &&
            existing.binding.action == safeAction &&
            existing.binding.resultKey == safeKey &&
            previousActions == actions.toSet()
        ) {
            return Observation.Unchanged()
        }
        if (existing == null) {
            val baselineFingerprint = baseline.remove(notificationKey)
            if (baselineFingerprint == fingerprint && previousActions == actions.toSet()) {
                baseline[notificationKey] = fingerprint
                return Observation.Baseline()
            }
        }

        val binding = Binding(
            notificationKey = notificationKey,
            generation = nextGeneration++,
            token = newToken(),
            carNotificationId = carIds.getOrPut(notificationKey) { nextCarId++ },
            action = safeAction,
            resultKey = safeKey,
        )
        entries.remove(notificationKey)
        entries[notificationKey] = Entry(binding, fingerprint, consumed = false)

        val evicted = mutableListOf<Binding<A>>()
        while (entries.size > maxBindings) {
            val oldestKey = entries.keys.first()
            evicted.add(entries.remove(oldestKey)!!.binding)
            carIds.remove(oldestKey)
        }
        return Observation.Publish(binding, alert = !newestFromSelf && existing?.fingerprint != fingerprint, evicted = evicted, replyDisabled = disabled)
    }

    private fun disableReplies(keys: List<String>): List<Binding<A>> = keys.mapNotNull { key ->
        conflicted.add(key)
        val entry = entries[key] ?: return@mapNotNull null
        if (entry.binding.action == null) return@mapNotNull null
        val readOnly = entry.binding.copy(generation = nextGeneration++, token = newToken(), action = null, resultKey = null)
        entries[key] = Entry(readOnly, entry.fingerprint, consumed = false)
        readOnly
    }

    /** The source notification is gone. Returns the binding whose car notification must be removed. */
    @Synchronized
    fun remove(notificationKey: String): Binding<A>? {
        baseline.remove(notificationKey)
        conflicted.remove(notificationKey)
        carIds.remove(notificationKey)
        knownActions.remove(notificationKey)
        return entries.remove(notificationKey)?.binding
    }

    /**
     * The switch was turned off. Every binding is withdrawn, but the record of
     * live Slack notifications and their actions stays, so turning it back on
     * cannot miss a shared action.
     */
    @Synchronized
    fun withdrawAll(): List<Binding<A>> {
        val all = entries.values.map { it.binding }
        // What was already forwarded becomes the baseline, so it is not replayed
        // as new when the switch comes back on.
        entries.forEach { (key, entry) -> baseline[key] = entry.fingerprint }
        entries.clear()
        return all
    }

    /** The listener disconnected. Returns every binding so its car notification can be removed. */
    @Synchronized
    fun clear(): List<Binding<A>> {
        val all = entries.values.map { it.binding }
        entries.clear()
        baseline.clear()
        conflicted.clear()
        carIds.clear()
        knownActions.clear()
        return all
    }

    private fun clashingKeys(notificationKey: String, actions: Collection<A>): List<String> =
        knownActions.filter { (key, known) -> key != notificationKey && actions.any { it in known } }.keys.toList()

    @Synchronized
    fun consume(token: String, text: String?): ConsumeResult<A> {
        val entry = entries.values.firstOrNull { it.binding.token == token }
            ?: return ConsumeResult.Rejected(ConsumeRejection.UNKNOWN_OR_EXPIRED)
        if (entry.binding.action == null || entry.binding.resultKey == null) {
            return ConsumeResult.Rejected(ConsumeRejection.NO_REPLY_ACTION, entry.binding)
        }
        if (entry.consumed) return ConsumeResult.Rejected(ConsumeRejection.ALREADY_CONSUMED, entry.binding)
        if (text.isNullOrBlank()) return ConsumeResult.Rejected(ConsumeRejection.EMPTY, entry.binding)
        if (text.length > MAX_REPLY_LENGTH) return ConsumeResult.Rejected(ConsumeRejection.TOO_LONG, entry.binding)
        entry.consumed = true
        return ConsumeResult.Dispatch(entry.binding, text)
    }

    @Synchronized
    fun bindingCount(): Int = entries.size

    /** The current binding that [matches] picks, or null. Used by debug test hooks to target one source exactly. */
    @Synchronized
    fun findBinding(matches: (Binding<A>) -> Boolean): Binding<A>? = entries.values.map { it.binding }.firstOrNull(matches)

    /** True while [binding] is still the newest generation for its notification. */
    @Synchronized
    fun isCurrent(binding: Binding<A>): Boolean = entries[binding.notificationKey]?.binding == binding
}
