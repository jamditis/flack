package tech.amditis.flack

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import tech.amditis.flack.core.ApiReadOnlyReason
import tech.amditis.flack.core.ApiRoute
import tech.amditis.flack.core.ApiRouteResolver
import tech.amditis.flack.core.Capability
import tech.amditis.flack.core.Binding
import tech.amditis.flack.core.CapabilityCheck
import tech.amditis.flack.core.Observation
import tech.amditis.flack.core.ProbeContext
import tech.amditis.flack.core.Conversation
import tech.amditis.flack.core.FilterRow
import tech.amditis.flack.core.Message
import tech.amditis.flack.core.NotificationFeatures
import tech.amditis.flack.core.NotificationFilter
import tech.amditis.flack.core.RejectReason
import tech.amditis.flack.core.SLACK_PACKAGE
import tech.amditis.flack.core.DEFAULT_MAX_BINDINGS
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Watches Slack notifications and keeps the car copies in step with them.
 *
 * Other apps' notifications are dropped before anything is read. This app's own
 * notifications come from another package, so they can never loop back.
 */
class SlackListenerService : NotificationListenerService() {

    private var carSession: CarSession? = null
    internal var apiWorker: Executor = API_LOOKUP_WORKER
    internal var resolveApi: (ParsedNotification) -> ApiRoute = ::apiRoute
    internal var activeSources: () -> Array<out StatusBarNotification> = { activeNotifications.orEmpty() }
    private val main = Handler(Looper.getMainLooper())
    private data class Source(
        val group: String?, val summary: Boolean, val actions: List<PendingIntent>,
        val readable: Boolean, val identity: ParsedNotification.MessageIdentity?, val messaging: Boolean,
        val latestIncoming: Message?,
    )
    private data class GroupMessage(val group: String, val identity: ParsedNotification.MessageIdentity)
    private val sources = HashMap<String, Source>()
    // Bounded session history also prevents a removed child being replayed as a summary.
    private val seenMessages = LinkedHashMap<GroupMessage, String>()

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            BridgeState.updateHealth.refresh(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply {
            addDataScheme("package")
            addDataSchemeSpecificPart(SLACK_PACKAGE, PatternMatcher.PATTERN_LITERAL)
            addDataSchemeSpecificPart(ANDROID_AUTO_PACKAGE, PatternMatcher.PATTERN_LITERAL)
        }
        // This protected system broadcast is observed only while the existing listener runs.
        ContextCompat.registerReceiver(this, packageChanges, filter, ContextCompat.RECEIVER_EXPORTED)
        BridgeState.updateHealth.refresh(this)
    }

    override fun onDestroy() {
        unregisterReceiver(packageChanges)
        super.onDestroy()
    }

    private fun describeSource(parsed: ParsedNotification) = Source(
        parsed.groupKey,
        parsed.facts.isGroupSummary, parsed.actionIntents.filterNotNull(),
        CapabilityCheck.evaluate(parsed.facts, BridgeState.sourcePackages) !is Capability.Rejected,
        parsed.newestMessageIdentity, parsed.hasMessagingMessages,
        parsed.facts.conversation?.messages?.lastOrNull { !it.fromSelf },
    )

    private fun groupMessage(source: Source, parsed: ParsedNotification): GroupMessage? =
        source.group?.let { group -> parsed.newestMessageIdentity?.let { GroupMessage(group, it) } }

    /**
     * Live notifications that show [source]'s exact message and may share its reply
     * action: its group summary, or the child a summary represents (0.3.8 report, #73).
     * The display match in [coversSummary] lets missing IDs pass, so this also needs
     * positive evidence of one destination: the same conversation title and type, and
     * the same set of actions. Without it, a shared action stays a clash.
     */
    private fun sameMessageKeys(key: String, source: Source): Set<String> = sources.filter { (otherKey, other) ->
        otherKey != key && sameDestination(source, other) &&
            ((source.summary && coversSummary(other, source)) || (other.summary && coversSummary(source, other)))
    }.keys

    private fun sameDestination(a: Source, b: Source): Boolean {
        val first = a.identity ?: return false
        val second = b.identity ?: return false
        return !first.title.isNullOrBlank() && first.title == second.title &&
            first.isGroup != null && first.isGroup == second.isGroup &&
            a.actions.isNotEmpty() && a.actions.toSet() == b.actions.toSet()
    }

    /** Group membership alone cannot establish that the child carries the summary's message. */
    private fun coversSummary(child: Source, summary: Source): Boolean {
        if (child.summary || !child.readable || child.group == null || child.group != summary.group) return false
        val childMessage = child.identity ?: return false
        val summaryMessage = summary.identity ?: return false
        // Slack summaries may have different headers or less metadata. Require the same
        // text and timestamp and preserve both copies when known IDs disagree.
        if (childMessage.message.text != summaryMessage.message.text ||
            childMessage.message.timestampMillis != summaryMessage.message.timestampMillis
        ) return false
        if (childMessage.conversationId != null && summaryMessage.conversationId != null &&
            childMessage.conversationId != summaryMessage.conversationId
        ) return false
        if (childMessage.senderKey != null && summaryMessage.senderKey != null &&
            childMessage.senderKey != summaryMessage.senderKey
        ) return false
        return childMessage.workspaceIds.isEmpty() || summaryMessage.workspaceIds.isEmpty() ||
            childMessage.workspaceIds == summaryMessage.workspaceIds
    }

    private fun rememberMessage(message: GroupMessage?, key: String) {
        if (message == null) return
        seenMessages.remove(message)
        seenMessages[message] = key
        if (seenMessages.size > DEFAULT_MAX_BINDINGS) seenMessages.remove(seenMessages.keys.first())
    }

    override fun onListenerConnected() {
        BridgeState.listenerConnected = true
        carSession = CarSession(this, ::onCarConnectionChanged).also { it.start() }
        // After a restart the registry is empty, so any car copy still showing holds a
        // dead token. Remove them rather than leave replies that can only be refused.
        if (BridgeState.registry.bindingCount() == 0) NotificationManagerCompat.from(this).cancelAll()
        // Messages already in the shade are a baseline, not new arrivals. Every Slack
        // notification's actions are recorded so no forwarded reply can share them.
        activeSources().filter(::isOwnProfileSource).forEach { sbn ->
            val parsed = SlackNotificationParser.parse(sbn)
            val source = describeSource(parsed)
            sources[sbn.key] = source
            rememberMessage(groupMessage(source, parsed), sbn.key)
            recordWithoutForwarding(sbn, parsed)
        }
    }

    private fun recordWithoutForwarding(sbn: StatusBarNotification, parsed: ParsedNotification) {
        val capability = CapabilityCheck.evaluate(parsed.facts, BridgeState.sourcePackages)
        val action = (capability as? Capability.Eligible)?.let { parsed.actionIntents[it.actionIndex] }
        if (capability !is Capability.Rejected) {
            BridgeState.registry.recordBaseline(sbn.key, parsed.fingerprint, action, parsed.actionIntents.filterNotNull())
        } else {
            refreshBindings(BridgeState.registry.recordOther(sbn.key, parsed.actionIntents.filterNotNull()))
        }
    }

    override fun onListenerDisconnected() {
        BridgeState.listenerConnected = false
        carSession?.stop()
        carSession = null
        // Without an observer the state is unknown; wait for the next one to report it.
        BridgeState.carConnected = false
        // The one call the docs allow here. Without it the system may leave Flack
        // unbound after an update or a kill until notification access is toggled.
        requestRebind(ComponentName(this, SlackListenerService::class.java))
        CarNotificationPublisher.cancel(this, BridgeState.registry.clear())
        sources.clear()
        seenMessages.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isOwnProfileSource(sbn)) return
        val parsed = SlackNotificationParser.parse(sbn)
        val source = describeSource(parsed)
        val previousSource = sources.put(sbn.key, source)
        val structureChanged = previousSource != null && (previousSource.group != source.group ||
            previousSource.summary != source.summary || previousSource.messaging != source.messaging)
        val sameNewestMessage = previousSource?.latestIncoming == source.latestIncoming
        val message = groupMessage(source, parsed)
        val previousKey = message?.let { seenMessages[it] }?.takeIf { it != sbn.key }
        val replacesKey = if (source.summary || source.group == null) null else sources.entries.firstOrNull { (key, summary) ->
            summary.summary && coversSummary(source, summary) &&
                BridgeState.registry.findBinding { it.notificationKey == key } != null
        }?.key
        val activeChild = source.summary && source.group != null && (
            sources.any { (key, other) -> key != sbn.key && coversSummary(other, source) } ||
                activeSources().any { other ->
                    isOwnProfileSource(other) && other.key != sbn.key && other.groupKey == source.group &&
                        other.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0 &&
                        coversSummary(describeSource(SlackNotificationParser.parse(other)), source)
                }
            )
        val textActions = parsed.facts.replyActions.count { it.freeFormResultKeys.isNotEmpty() }
        val features = NotificationFeatures(
            parsed.hasMessagingMessages, textActions > 0,
            source.group != null, source.summary, activeChild,
            NotificationFilter.conversationIdShape(parsed.routeFields.shortcutId),
            parsed.facts.conversation?.isGroup, parsed.facts.conversation?.messages?.isNotEmpty() == true,
            replacesSummary = replacesKey != null, alreadyForwarded = previousKey != null,
        )
        val decision = NotificationFilter.decide(features)
        BridgeState.recordFilter(decision, features, parsed.threadEvidence)
        if (sbn.packageName == SLACK_PACKAGE) {
            BridgeState.updateHealth.recordShape(features, decision, parsed.facts.replyActions, textActions, parsed.threadEvidence)
        }
        // Capture multi-action facts even when the selector can safely choose one,
        // so the driveway test still reveals Slack's actual action shape.
        if (textActions > 1) {
            BridgeState.replyActionDiagnostics.record(parsed.facts.replyActions)
        }
        if (!BridgeState.shouldForward(this)) {
            // Forward nothing, but keep the baseline and every action on record, so turning
            // the switch on or reaching the car neither replays old messages nor hides a
            // shared action.
            recordRouteFields(parsed, CapabilityCheck.evaluate(parsed.facts, BridgeState.sourcePackages))
            recordWithoutForwarding(sbn, parsed)
            rememberMessage(message, sbn.key)
            return
        }
        if (decision.row == FilterRow.ALREADY_FORWARDED) {
            refreshBindings(BridgeState.registry.recordOther(sbn.key, source.actions))
            BridgeState.registry.recordBaseline(sbn.key, parsed.fingerprint, null, source.actions)
            return
        }
        val checked = CapabilityCheck.evaluate(parsed.facts, BridgeState.sourcePackages)
        val capability = when {
            decision.row == FilterRow.SUMMARY_WITH_CHILD -> Capability.Rejected(RejectReason.SUMMARY_WITH_CHILD)
            decision.row == FilterRow.EMPTY && source.summary -> Capability.Rejected(RejectReason.EMPTY_SUMMARY)
            decision.readOnly && checked !is Capability.Rejected -> Capability.ReadOnly(RejectReason.UNKNOWN_FORMAT)
            else -> checked
        }
        recordRouteFields(parsed, capability)
        when (capability) {
            is Capability.Rejected -> {
                BridgeState.countRejection(capability.reason)
                if (sbn.packageName == SLACK_PACKAGE && !source.summary && parsed.hasMessageSignal && previousSource != source) {
                    BridgeState.updateHealth.recordAttempt(this, posted = false)
                }
                refreshBindings(BridgeState.registry.recordOther(sbn.key, parsed.actionIntents.filterNotNull(),
                    sameMessageKeys(sbn.key, source)))
            }
            // Route order: Slack's original action, then the Slack API, then read-only.
            is Capability.ReadOnly -> {
                val noReplyAction = capability.reason == RejectReason.NO_REPLY_ACTION && parsed.facts.replyActions.none {
                    it.resultKeys.isNotEmpty() || it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY
                }
                val allowApiLookup = noReplyAction && parsed.facts.conversation?.isGroup == false &&
                    parsed.routeFields.shortcutId != null
                forward(sbn, parsed, null, capability, replacesKey, allowApiLookup, structureChanged, sameNewestMessage)
            }
            is Capability.Eligible -> forward(sbn, parsed, capability, replacesKey = replacesKey,
                structureChanged = structureChanged, sameNewestMessage = sameNewestMessage)
        }
    }

    /**
     * Leaving the car withdraws every copy and its token (SPEC.md section 7). What
     * was forwarded becomes the baseline, so reconnecting does not replay it.
     */
    fun onCarConnectionChanged(connected: Boolean) {
        BridgeState.carConnected = connected
        if (connected) BridgeState.updateHealth.refresh(this)
        if (!connected || !BridgeState.shouldForward(this)) CarNotificationPublisher.cancel(this, BridgeState.registry.withdrawAll())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isOwnProfileSource(sbn)) return
        sources.remove(sbn.key)
        BridgeState.registry.remove(sbn.key)?.let { CarNotificationPublisher.cancel(this, listOf(it)) }
    }

    /** The Slack API destination for a message without a usable original action. */
    private fun apiRoute(parsed: ParsedNotification): ApiRoute {
        val conversation = parsed.facts.conversation ?: return ApiRoute.ReadOnly(ApiReadOnlyReason.NO_FIELD_MAPPING)
        val accounts = SlackSession.accounts(this)
        return ApiRouteResolver.resolve(conversation, parsed.routeFields, accounts::workspaces, SlackWorkspaceCheck(accounts, SlackHttp)::confirm)
    }

    private fun forward(
        sbn: StatusBarNotification,
        parsed: ParsedNotification,
        capability: Capability.Eligible?,
        readOnly: Capability.ReadOnly? = null,
        replacesKey: String? = null,
        allowApiLookup: Boolean = false,
        structureChanged: Boolean = false,
        sameNewestMessage: Boolean = false,
    ) {
        val action = capability?.let { parsed.actionIntents[it.actionIndex] }
        val original = parsed.facts.conversation ?: return
        val isThreadReply = capability?.let { CapabilityCheck.isThreadReply(parsed.facts.replyActions[it.actionIndex]) } == true
        val conversation = if (isThreadReply && original.title?.contains("thread", ignoreCase = true) != true) {
            original.copy(title = "${CapabilityCheck.contextLabel(original)} · thread")
        } else original
        val newestFromSelf = conversation.messages.last().fromSelf
        when (val result = BridgeState.registry.observe(sbn.key, parsed.fingerprint, action, capability?.resultKey, newestFromSelf,
            parsed.actionIntents.filterNotNull(), apiLookupPending = readOnly != null && allowApiLookup,
            replacesKey = replacesKey, forceGeneration = structureChanged, sameNewestMessage = sameNewestMessage,
            sameMessage = sameMessageKeys(sbn.key, describeSource(parsed)))) {
            is Observation.Publish -> {
                refreshBindings(result.replyDisabled)
                val sharedAction = action != null && !result.binding.canReply
                val replyProblem = when {
                    sharedAction -> RejectReason.SHARED_REPLY_ACTION
                    allowApiLookup -> null
                    else -> readOnly?.reason
                }
                val posted = publish(result.binding, conversation, result.alert, replyProblem, capability?.conversationReply == true)
                CarNotificationPublisher.cancel(this, result.evicted)
                val updateAttempt = if (sbn.packageName == SLACK_PACKAGE) {
                    val replyOffered = result.binding.canReply.takeUnless { allowApiLookup }
                    val id = result.binding.carNotificationId
                    when {
                        replacesKey != null -> BridgeState.updateHealth.recordReplacement(this, id, posted, replyOffered)
                        result.alert -> BridgeState.updateHealth.recordAttempt(this, posted, replyOffered,
                            summaryCopyId = id.takeIf { parsed.facts.isGroupSummary })
                        else -> null
                    }
                } else null
                if (posted) {
                    if (sharedAction) BridgeState.countAmbiguous()
                    if (!allowApiLookup) replyProblem?.let(BridgeState::countReadOnly)
                    val source = describeSource(parsed)
                    rememberMessage(groupMessage(source, parsed), sbn.key)
                    // Withdraw a summary only after a readable child has taken its place.
                    if (!source.summary && source.group != null) {
                        sources.filter { (key, other) -> key != replacesKey && other.summary && coversSummary(source, other) }
                            .forEach { (key, other) ->
                                refreshBindings(BridgeState.registry.recordOther(key, other.actions, sameMessageKeys(key, other)))
                            }
                    }
                }
                if (posted && readOnly != null && allowApiLookup) {
                    resolveBeforePublishing(sbn, parsed, readOnly, result, updateAttempt)
                }
            }
            is Observation.Unchanged, is Observation.Baseline -> Unit
        }
    }

    /** Keep the registry generation while the read-only lookup runs; removal/pause/update invalidates it. */
    private fun resolveBeforePublishing(
        sbn: StatusBarNotification,
        parsed: ParsedNotification,
        readOnly: Capability.ReadOnly,
        pending: Observation.Publish<PendingIntent>,
        updateAttempt: PlatformUpdateHealth.Attempt?,
    ) {
        fun complete(route: ApiRoute) {
            if (!BridgeState.registry.isCurrent(pending.binding) || !BridgeState.shouldForward(this)) return
            val conversation = parsed.facts.conversation ?: return
            when (route) {
                is ApiRoute.Certain -> {
                    val updated = BridgeState.registry.observe(sbn.key, parsed.fingerprint, null, null,
                        conversation.messages.last().fromSelf, parsed.actionIntents.filterNotNull(), route.destination)
                    if (updated is Observation.Publish) {
                        BridgeState.countApiRouted()
                        // Readout was already offered. Add the confirmed reply quietly.
                        val offered = publish(updated.binding, conversation, alert = false)
                        BridgeState.updateHealth.recordApiReply(this, updateAttempt, offered)
                    }
                }
                is ApiRoute.ReadOnly -> {
                    BridgeState.countReadOnly(readOnly.reason)
                    BridgeState.countApiReadOnly(route.reason)
                    publish(pending.binding, conversation, alert = false, replyProblem = readOnly.reason)
                    BridgeState.updateHealth.recordApiReply(this, updateAttempt, offered = false)
                }
            }
        }
        apiWorker.execute {
            if (BridgeState.registry.isCurrent(pending.binding)) {
                val route = resolveApi(parsed)
                main.post { complete(route) }
            }
        }
    }

    private fun publish(
        binding: Binding<PendingIntent>, conversation: Conversation, alert: Boolean, replyProblem: RejectReason? = null,
        conversationReply: Boolean = false,
    ): Boolean {
        val posted = CarNotificationPublisher.post(this, binding, CarCopyContent(conversation, replyProblem, conversationReply), alert)
        if (posted && alert) BridgeState.countForwarded()
        return posted
    }

    private fun refreshBindings(bindings: Collection<Binding<android.app.PendingIntent>>) {
        bindings.forEach { binding ->
            if (BridgeState.registry.isCurrent(binding)) {
                BridgeState.copyContent(binding.carNotificationId)?.let { copy ->
                    val reason = RejectReason.SHARED_REPLY_ACTION
                    if (CarNotificationPublisher.post(this, binding, copy.copy(replyProblem = reason), alert = false)) {
                        BridgeState.countReadOnly(reason)
                    }
                }
                BridgeState.countAmbiguous()
            } else {
                CarNotificationPublisher.cancel(this, listOf(binding))
            }
        }
    }

    /** Shapes only, for the sign-in route's field mapping (docs/SLACK_SIGN_IN.md). */
    private fun recordRouteFields(parsed: ParsedNotification, capability: Capability) {
        val route = when (capability) {
            is Capability.Eligible -> "ELIGIBLE"
            is Capability.ReadOnly -> "READ_ONLY(${capability.reason.name})"
            is Capability.Rejected -> return
        }
        val conversation = parsed.facts.conversation
        val newest = conversation?.messages?.lastOrNull { !it.fromSelf }
        BridgeState.routeFieldProbe.record(
            route,
            conversation?.isGroup,
            parsed.routeFields,
            ProbeContext(conversation?.title, newest?.senderName, newest?.timestampMillis),
        )
    }

    private fun isOwnProfileSource(sbn: StatusBarNotification) =
        sbn.packageName in BridgeState.sourcePackages && sbn.user == Process.myUserHandle()

    private companion object {
        val API_LOOKUP_WORKER: Executor = Executors.newSingleThreadExecutor()
    }
}
