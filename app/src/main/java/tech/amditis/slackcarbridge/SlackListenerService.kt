package tech.amditis.slackcarbridge

import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import tech.amditis.slackcarbridge.core.Capability
import tech.amditis.slackcarbridge.core.CapabilityCheck
import tech.amditis.slackcarbridge.core.Observation
import tech.amditis.slackcarbridge.core.SLACK_PACKAGE

/**
 * Watches Slack notifications and keeps the car copies in step with them.
 *
 * Other apps' notifications are dropped before anything is read. This app's own
 * notifications come from another package, so they can never loop back.
 */
class SlackListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        BridgeState.listenerConnected = true
        // After a restart the registry is empty, so any car copy still showing holds a
        // dead token. Remove them rather than leave replies that can only be refused.
        if (BridgeState.registry.bindingCount() == 0) NotificationManagerCompat.from(this).cancelAll()
        // Messages already in the shade are a baseline, not new arrivals. Every Slack
        // notification's actions are recorded so no forwarded DM can share them.
        activeNotifications.orEmpty().filter(::isOwnProfileSlack).forEach { sbn ->
            recordWithoutForwarding(sbn, SlackNotificationParser.parse(sbn))
        }
    }

    private fun recordWithoutForwarding(sbn: StatusBarNotification, parsed: ParsedNotification) {
        val capability = CapabilityCheck.evaluate(parsed.facts)
        val action = (capability as? Capability.Eligible)?.let { parsed.actionIntents[it.actionIndex] }
        if (action != null) {
            BridgeState.registry.recordBaseline(sbn.key, parsed.fingerprint, action)
        } else {
            BridgeState.registry.recordOther(sbn.key, parsed.actionIntents.filterNotNull())
        }
    }

    override fun onListenerDisconnected() {
        BridgeState.listenerConnected = false
        CarNotificationPublisher.cancel(this, BridgeState.registry.clear())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!isOwnProfileSlack(sbn)) return
        val parsed = SlackNotificationParser.parse(sbn)
        if (!BridgeState.isEnabled(this)) {
            // Forward nothing, but keep the baseline and every action on record, so
            // turning the switch on neither replays old messages nor hides a shared action.
            recordWithoutForwarding(sbn, parsed)
            return
        }
        when (val capability = CapabilityCheck.evaluate(parsed.facts)) {
            is Capability.Rejected -> {
                BridgeState.countRejection(capability.reason)
                // Withdraws this key's car copy and any DM that shares one of its actions.
                val withdrawn = BridgeState.registry.recordOther(sbn.key, parsed.actionIntents.filterNotNull())
                CarNotificationPublisher.cancel(this, withdrawn)
            }
            is Capability.Eligible -> forward(sbn, parsed, capability)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isOwnProfileSlack(sbn)) return
        BridgeState.registry.remove(sbn.key)?.let { CarNotificationPublisher.cancel(this, listOf(it)) }
    }

    private fun forward(sbn: StatusBarNotification, parsed: ParsedNotification, capability: Capability.Eligible) {
        val action = parsed.actionIntents[capability.actionIndex]
        if (action == null) {
            CarNotificationPublisher.cancel(this, BridgeState.registry.recordOther(sbn.key, emptyList()))
            return
        }
        val conversation = parsed.facts.conversation ?: return
        val newestFromSelf = conversation.messages.last().fromSelf
        when (val result = BridgeState.registry.observe(sbn.key, parsed.fingerprint, action, capability.resultKey, newestFromSelf)) {
            is Observation.Publish -> {
                CarNotificationPublisher.cancel(this, result.evicted)
                val posted = CarNotificationPublisher.post(this, result.binding, conversation, result.alert)
                if (posted && result.alert) BridgeState.countForwarded()
            }
            is Observation.Ambiguous -> {
                CarNotificationPublisher.cancel(this, result.withdrawn)
                BridgeState.countAmbiguous()
            }
            is Observation.Unchanged, is Observation.Baseline -> Unit
        }
    }

    private fun isOwnProfileSlack(sbn: StatusBarNotification) =
        sbn.packageName == SLACK_PACKAGE && sbn.user == Process.myUserHandle()
}
