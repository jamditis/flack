# Evidence and source limits

Reviewed November 11, 2026. Documentation supports the interfaces and constraints below. Device-specific compatibility remains untested. The specification's architecture, limits, milestones, and acceptance rules are proposed design decisions.

| ID | Primary source | Supports | Does not establish |
| --- | --- | --- | --- |
| S1 | [Google Gemini Device assistance](https://support.google.com/gemini/answer/15235441?hl=en#read_messages) | Notification read/reply on Android phones, notification permission, and locked-phone setup; availability may vary | Slack support through a specific Android Auto head unit |
| S2 | [Android Auto messaging help](https://support.google.com/androidauto/answer/6348317?hl=en) | Readout, voice replies, readback/confirmation for compatible messaging apps | A native Slack integration or the exact behavior of an experimental bridge |
| S3 | [Android Auto messaging notifications](https://developer.android.com/training/cars/communication/notification-messaging) | MessagingStyle, reply and mark-as-read actions, background handling, and voice transcription into a reply action | That an arbitrary Slack notification can be safely wrapped |
| S4 | [Slack Android release notes](https://slack.com/release-notes/android) | Notification quick replies; the May 13, 2026 entry for version 26.05.20 describes an added thread-reply option | Every installed notification exposes the same action or all thread cases work |
| S5 | [Android RemoteInput](https://developer.android.com/reference/android/app/RemoteInput) | Text results can accompany the original PendingIntent using its result keys | Slack server delivery or stable destination metadata |
| S6 | [Android PendingIntent](https://developer.android.com/reference/android/app/PendingIntent) | Opaque execution capability, creator identity, cancellation, operation callbacks, and creator updates | Immutable conversation routing or server acknowledgement |
| S7 | [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService) | Listener lifecycle, notification events, and work-profile restrictions | Permission to bypass redaction or enterprise policy |
| S8 | [Samsung sleeping apps](https://www.samsung.com/us/support/answer/ANS10003442/) | Samsung background sleeping controls and their effect on app behavior | That changing a setting fixes this unbuilt application |
| S9 | [Android Auto testing](https://developer.android.com/training/cars/testing) | Development testing and the unknown-sources option for eligible messaging-notification apps | Public-store acceptance or an installation method that defeats device policy |
| S10 | [Car-app quality guidelines](https://developer.android.com/docs/quality-guidelines/car-app-quality) | Messaging requirements including receipt, replies, grouping/order, and appropriate service type | Approval of a Slack notification bridge |
| S11 | [AA Notification Forwarder](https://github.com/ztNFny/AANotificationForwarder) | Existing notification relay; its author states replies do not function and identifies its noncommercial code license | A reusable permissively licensed reply implementation |
| S12 | [Slack notification settings](https://slack.com/help/articles/201355156-Configure-your-Slack-notifications) | Mobile timing, DMs/mentions, and per-conversation notification preferences | Access to messages Slack never emits as phone notifications |
| S13 | [Android direct-reply notifications](https://developer.android.com/develop/ui/views/notifications/build-notification) | Reply input and the mutable PendingIntent used for direct reply | The installed Slack action accepts another app's forwarded result |

## Research conclusions

The strongest current evidence supports a notification-based experiment. Android Auto can receive formatted conversation notifications and invoke their reply capabilities; Slack exposes Android notification replies. Joining those mechanisms is an engineering hypothesis requiring the target device.

A matching sender name is not identity. A successful Android callback is not a Slack acknowledgement. A car-side read action is not proof that playback completed or that Slack marked the conversation read. Each distinction affects the acceptance rules.

The existing forwarder is relevant as prior art, but its author explicitly excludes working replies. This project therefore needs an original implementation and a separate feasibility result.

## Citation practice for future changes

Keep implementation claims tied to the tested versions and configuration. Update source checks when Android, Slack, assistant behavior, or distribution policy changes. Record whether evidence comes from documentation, a synthetic fixture, Desktop Head Unit, or the physical phone/car. Do not describe one as another.
