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
    val action: A,
    val resultKey: String,
)

sealed interface Observation<A : Any> {
    /** A new generation to publish. [alert] is false when the newest message is the user's own. */
    data class Publish<A : Any>(
        val binding: Binding<A>,
        val alert: Boolean,
        val evicted: List<Binding<A>>,
    ) : Observation<A>

    /** The same content seen again. Nothing changes and no new token is made. */
    class Unchanged<A : Any> : Observation<A>

    /** Present when the listener connected. Not a new arrival, so not forwarded. */
    class Baseline<A : Any> : Observation<A>

    /**
     * The reply action is also bound to another conversation. Both are withdrawn,
     * because the bridge cannot prove where a reply would go.
     */
    data class Ambiguous<A : Any>(val withdrawn: List<Binding<A>>) : Observation<A>
}

enum class ConsumeRejection { UNKNOWN_OR_EXPIRED, ALREADY_CONSUMED, EMPTY, TOO_LONG }

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
 * - A reply action seen on two notifications, forwarded or not, is withdrawn.
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
     * A DM whose action also appears on any other notification is withheld, even
     * after one side is withdrawn, until a source notification goes away.
     */
    private val knownActions = HashMap<String, Set<A>>()
    private var nextGeneration = 1L
    private var nextCarId = 1

    /** An eligible notification already present when the listener connected. */
    @Synchronized
    fun recordBaseline(notificationKey: String, fingerprint: String, action: A) {
        if (notificationKey in entries) return
        baseline[notificationKey] = fingerprint
        knownActions[notificationKey] = setOf(action)
    }

    /**
     * A Slack notification that cannot be forwarded. Its actions are remembered so
     * no forwarded DM can share them. Returns bindings that must be withdrawn: this
     * key's own, and any that share one of [actions].
     */
    @Synchronized
    fun recordOther(notificationKey: String, actions: Collection<A>): List<Binding<A>> {
        baseline.remove(notificationKey)
        knownActions[notificationKey] = actions.toSet()
        val withdrawn = mutableListOf<Binding<A>>()
        entries.remove(notificationKey)?.let { withdrawn.add(it.binding) }
        for (key in clashingKeys(notificationKey, actions)) {
            conflicted.add(key)
            entries.remove(key)?.let { withdrawn.add(it.binding) }
        }
        return withdrawn
    }

    @Synchronized
    fun observe(
        notificationKey: String,
        fingerprint: String,
        action: A,
        resultKey: String,
        newestFromSelf: Boolean,
    ): Observation<A> {
        val existing = entries[notificationKey]
        // Same content and the same reply capability: a repeated callback.
        if (existing != null &&
            existing.fingerprint == fingerprint &&
            existing.binding.action == action &&
            existing.binding.resultKey == resultKey
        ) {
            return Observation.Unchanged()
        }
        if (existing == null) {
            val baselineFingerprint = baseline.remove(notificationKey)
            if (baselineFingerprint == fingerprint && knownActions[notificationKey] == setOf(action)) {
                baseline[notificationKey] = fingerprint
                return Observation.Baseline()
            }
        }

        knownActions[notificationKey] = setOf(action)
        val clashes = clashingKeys(notificationKey, listOf(action))
        if (notificationKey in conflicted || clashes.isNotEmpty()) {
            val withdrawn = mutableListOf<Binding<A>>()
            for (key in clashes + notificationKey) {
                conflicted.add(key)
                entries.remove(key)?.let { withdrawn.add(it.binding) }
            }
            return Observation.Ambiguous(withdrawn)
        }

        val binding = Binding(
            notificationKey = notificationKey,
            generation = nextGeneration++,
            token = newToken(),
            carNotificationId = carIds.getOrPut(notificationKey) { nextCarId++ },
            action = action,
            resultKey = resultKey,
        )
        entries.remove(notificationKey)
        entries[notificationKey] = Entry(binding, fingerprint, consumed = false)

        val evicted = mutableListOf<Binding<A>>()
        while (entries.size > maxBindings) {
            val oldestKey = entries.keys.first()
            evicted.add(entries.remove(oldestKey)!!.binding)
            carIds.remove(oldestKey)
        }
        return Observation.Publish(binding, alert = !newestFromSelf, evicted = evicted)
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
        if (entry.consumed) return ConsumeResult.Rejected(ConsumeRejection.ALREADY_CONSUMED, entry.binding)
        if (text.isNullOrBlank()) return ConsumeResult.Rejected(ConsumeRejection.EMPTY, entry.binding)
        if (text.length > MAX_REPLY_LENGTH) return ConsumeResult.Rejected(ConsumeRejection.TOO_LONG, entry.binding)
        entry.consumed = true
        return ConsumeResult.Dispatch(entry.binding, text)
    }

    @Synchronized
    fun bindingCount(): Int = entries.size

    /** True while [binding] is still the newest generation for its notification. */
    @Synchronized
    fun isCurrent(binding: Binding<A>): Boolean = entries[binding.notificationKey]?.binding == binding
}
