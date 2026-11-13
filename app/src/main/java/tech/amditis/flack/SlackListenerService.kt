package tech.amditis.flack

import android.content.ComponentName
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import tech.amditis.flack.core.Capability
import tech.amditis.flack.core.Binding
import tech.amditis.flack.core.CapabilityCheck
import tech.amditis.flack.core.Observation
import tech.amditis.flack.core.ProbeContext

/**
 * Watches Slack notifications and keeps the car copies in step with them.
 *
 * Other apps' notifications are dropped before anything is read. This app's own
 * notifications come from another package, so they can never loop back.
 */
class SlackListenerService : NotificationListenerService() {

    private var carSession: CarSession? = null

    override fun onListenerConnected() {
        BridgeState.listenerConnected = true
        carSession = CarSession(this, ::onCarConnectionChanged).also { it.start() }
        // After a restart the registry is empty, so any car copy still showing holds a
        // dead token. Remove them rather than leave replies that can only be refused.
        if (BridgeState.registry.bindingCount() == 0) NotificationManagerCompat.from(this).cancelAll()
        // Messages already in the shade are a baseline, not new arrivals. Every Slack
        // notification's actions are recorded so no forwarded reply can share them.
        activeNotifications.orEmpty().filter(::isOwnProfileSource).forEach { sbn ->
            recordWithoutForwarding(sbn, SlackNotificationParser.parse(sbn))
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
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isOwnProfileSource(sbn)) return
        val parsed = SlackNotificationParser.parse(sbn)
        // Capture multi-action facts even when the selector can safely choose one,
        // so the driveway test still reveals Slack's actual action shape.
        if (parsed.facts.replyActions.count { it.freeFormResultKeys.isNotEmpty() } > 1) {
            BridgeState.replyActionDiagnostics.record(parsed.facts.replyActions)
        }
        if (!BridgeState.shouldForward(this)) {
            // Forward nothing, but keep the baseline and every action on record, so turning
            // the switch on or reaching the car neither replays old messages nor hides a
            // shared action.
            recordRouteFields(parsed, CapabilityCheck.evaluate(parsed.facts, BridgeState.sourcePackages))
            recordWithoutForwarding(sbn, parsed)
            return
        }
        val capability = CapabilityCheck.evaluate(parsed.facts, BridgeState.sourcePackages)
        recordRouteFields(parsed, capability)
        when (capability) {
            is Capability.Rejected -> {
                BridgeState.countRejection(capability.reason)
                refreshBindings(BridgeState.registry.recordOther(sbn.key, parsed.actionIntents.filterNotNull()))
            }
            is Capability.ReadOnly -> {
                BridgeState.countReadOnly(capability.reason)
                forward(sbn, parsed, null)
            }
            is Capability.Eligible -> forward(sbn, parsed, capability)
        }
    }

    /**
     * Leaving the car withdraws every copy and its token (SPEC.md section 7). What
     * was forwarded becomes the baseline, so reconnecting does not replay it.
     */
    fun onCarConnectionChanged(connected: Boolean) {
        BridgeState.carConnected = connected
        if (!connected || !BridgeState.shouldForward(this)) CarNotificationPublisher.cancel(this, BridgeState.registry.withdrawAll())
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isOwnProfileSource(sbn)) return
        BridgeState.registry.remove(sbn.key)?.let { CarNotificationPublisher.cancel(this, listOf(it)) }
    }

    private fun forward(sbn: StatusBarNotification, parsed: ParsedNotification, capability: Capability.Eligible?) {
        val action = capability?.let { parsed.actionIntents[it.actionIndex] }
        val original = parsed.facts.conversation ?: return
        val selectedTitle = capability?.let { parsed.facts.replyActions[it.actionIndex].title }
        val isThreadReply = selectedTitle?.contains("thread", ignoreCase = true) == true
        val conversation = if (isThreadReply && original.title?.contains("thread", ignoreCase = true) != true) {
            original.copy(title = "${CapabilityCheck.contextLabel(original)} · thread")
        } else original
        val newestFromSelf = conversation.messages.last().fromSelf
        when (val result = BridgeState.registry.observe(sbn.key, parsed.fingerprint, action, capability?.resultKey, newestFromSelf, parsed.actionIntents.filterNotNull())) {
            is Observation.Publish -> {
                CarNotificationPublisher.cancel(this, result.evicted)
                refreshBindings(result.replyDisabled)
                val posted = CarNotificationPublisher.post(this, result.binding, conversation, result.alert)
                if (posted && result.alert) BridgeState.countForwarded()
            }
            is Observation.Unchanged, is Observation.Baseline -> Unit
        }
    }

    private fun refreshBindings(bindings: Collection<Binding<android.app.PendingIntent>>) {
        bindings.forEach { binding ->
            if (BridgeState.registry.isCurrent(binding)) {
                BridgeState.conversation(binding.carNotificationId)?.let {
                    CarNotificationPublisher.post(this, binding, it, alert = false)
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
}
