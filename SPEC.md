# Slack car bridge specification

Version 0.1 · November 11, 2026

Build a small Android companion that makes supported incoming Slack messages available through Android Auto and returns a confirmed voice reply to the original Slack conversation. The first targets are a Samsung Galaxy S24 Ultra (primary) and a Samsung Galaxy S21 (second tester), each using Android Auto on a car's display.

The first engineering milestone is a real-device reply experiment. Product implementation proceeds only after that experiment demonstrates correct routing. Android documents the required interfaces, but the installed Slack app and head unit still determine whether the complete workflow is usable.

## 1. Product outcome

A driver receives a new Slack message, hears who sent it and enough conversation context to identify it, listens through Android Auto, dictates a reply, reviews the host's readback, and explicitly confirms or cancels. A confirmed reply reaches the same Slack destination selected at the start of the interaction.

The user should not need to unlock or touch the phone during this flow. Setup, troubleshooting, and compatibility inspection happen on the phone while parked.

### Initial environment

| Item | Target or decision |
| --- | --- |
| Phones | Samsung Galaxy S24 Ultra (primary) and Samsung Galaxy S21 (second tester); record the exact model and installed Android/One UI versions for each at test time. Both are kept current, but the S21 can stop at an older Android version than the S24 Ultra, so do not assume they match |
| Car integration | Android Auto projected from the phone to the car display |
| Connection | Record whether wired or wireless; validate the connection actually used |
| Slack | Current installed official Android app; record version and profile |
| Assistant | Record Google/Gemini and Android Auto versions; do not assume phone and car capabilities are identical |
| Repository | Proposed public repository jamditis/slack-car-bridge |
| Implementation | Kotlin, AndroidX notification support, small phone setup interface |
| Minimum SDK | Provisional API 30; confirm against the test phone and chosen dependencies |
| Build versions | Pin supported stable versions when implementation starts; verify current SDK and distribution requirements then |

## 2. Native route and evidence

Google documents notification reading and replies in the Gemini mobile app. Its separate Android Auto instructions describe compatible messaging apps. Neither establishes Slack compatibility on this particular phone and car. The native path therefore has a short device test before custom development. [S1, S2]

If G0 succeeds on the actual car, use the native route and stop custom development unless the maintainer requests additional work. Repository publication and implementation remain conditional on a native failure after setup checks. An inconclusive result keeps that decision pending; it is not a failed gate.

Android Auto supports messaging notifications with reply actions, and Android provides RemoteInput for passing text through another app's PendingIntent. Slack's Android release notes document notification quick replies and a thread-reply option. The proposed bridge combines these documented capabilities; its Slack-specific behavior remains an experiment. [S3–S6]

The sources and their precise limits are in docs/SOURCES.md. Requirements below are proposed project behavior unless explicitly attributed to a source.

## 3. Scope

### First supported release

- New, eligible Slack text notifications received during an active Android Auto session.
- Direct messages first. Group DMs, channel mentions, and thread replies each require separate evidence before being enabled.
- Readout and reply through Android Auto's native messaging flow.
- Clear sender and destination context when the notification exposes it reliably.
- A phone-side enable/pause control, setup status, supported-message list, and content-free diagnostics.
- Session cleanup and an explicit recovery path when notification access, the source action, or the connection is unavailable.

If only direct messages pass the tests, the first release supports direct messages. That is an acceptable scope reduction.

### Later work requiring a separate design

Starting a new conversation by contact name, reading a complete channel backlog, searching Slack, composing arbitrary channel posts, audio/video messages, huddles, files, reactions, and reliable Slack read-state synchronization are outside the first release.

A Slack API client would require a different authentication, permissions, distribution, and delivery design. A notification-based experiment must not silently evolve into token collection, screen scraping, or a cloud relay.

## 4. Feasibility gates

| Gate | Evidence required | Consequence if it fails |
| --- | --- | --- |
| G0 Native test | Fresh Slack DM read and replied to through the actual car, including a locked-phone test | Continue to G1 after checking setup; record whether phone-only behavior differs |
| G1 Source capability | Real Slack notification contains usable text and an unambiguous inline reply action | Exclude that message type; stop this architecture if no useful type qualifies |
| G2 One DM round trip | Car readout, dictation, review, confirmation, and one reply in the intended DM | Fix the feasibility experiment before building product UI |
| G3 Concurrent routing | Correct destination under updates, two conversations, identical names, and thread cases | Any misroute blocks a pilot |
| G4 Host interaction | Actual car flow provides intelligible context, confirmation/cancel, and acceptable failure behavior | Redesign or stop; do not manufacture a reassuring status |
| G5 Recovery and privacy | Lifecycle tests pass and content remains absent from persistent diagnostics | Correct before distributing the pilot |

An unavailable notification caused by disabled permissions or Slack timing is a setup problem. Redaction or managed-profile policy is a compatibility boundary. A bridge must respect it. Android documents notification-listener restrictions for work profiles. [S7]

## 5. Architecture

~~~mermaid
flowchart TD
  A["Slack notification"] --> B["Source and capability checks"]
  B -->|Supported| C["Session registry"]
  B -->|Unsupported| D["Phone status"]
  C --> E["Android Auto message"]
  E --> F["Readout and confirmed dictation"]
  F --> G["Validate and consume reply token"]
  G -->|Valid| H["Original Slack reply action"]
  G -->|Expired or ambiguous| D
  H --> I["Local handoff status"]
~~~

| Component | Responsibility |
| --- | --- |
| SlackNotificationListener | Validate source package/profile, receive notification changes, and expose listener health |
| SlackNotificationParser | Interpret supported structures and identify genuine text-reply capabilities |
| SessionRegistry | Store bounded message snapshots, original action handles, and unique reply tokens in memory |
| CarConnectionMonitor | Establish projection/session boundaries using a documented mechanism validated on the target device |
| CarNotificationPublisher | Publish, update, and remove Android Auto-compatible message notifications |
| ReplyReceiver | Validate the selected binding, accept confirmed text, and invoke the original reply capability |
| LocalAcknowledgementReceiver | Track car-side acknowledgement without claiming Slack read synchronization |
| Setup and diagnostics UI | Permissions, pause, compatibility, concise failure explanations, and explicit export of safe diagnostics |

Use the notification-based Android Auto integration for the first release. Evaluate a full Car App Library conversation interface only as separate work. The app does not need a custom car dashboard, custom microphone service, or an always-listening assistant.

The wrapper's reply PendingIntent must target an explicit internal component and permit RemoteInput results through the appropriate mutable flag. Its acknowledgement PendingIntent should be immutable. Validate input and token ownership in the receiver. The original Slack capability must itself accept the reply results; an incompatible immutable source action is unsupported. This mutability requirement is distinct from Slack's ability to update its own action. [S13]

## 6. Source selection and message identity

Process only the verified official Slack package in the permitted Android profile. Ignore other apps before extracting their contents, and ignore the companion's own notifications to prevent loops. Validate the original action's creator package and UID against the observed source.

Each reply binding includes the source package, Android user/profile, notification key, observed generation, original reply action, exact RemoteInput result key, and a unique random bridge token. Store any trustworthy conversation metadata supplied by Slack, but do not invent Slack IDs or derive recipients from display names.

The original action supplies the route. Names and channel titles provide context to the person listening; they are not routing keys. If the user cannot distinguish two destinations from the exposed context, withhold the ambiguous item from the two-way car stream.

Do not assume a PendingIntent is a frozen snapshot. Its creator may update its underlying operation. Equality checks are useful consistency checks, not proof of an immutable destination. [S6] Detect a reply capability observed across conflicting conversation bindings and reject it until its safety is established. Revalidate the latest observed source generation and action immediately before dispatch. The bridge cannot inspect or atomically freeze Slack's underlying operation; real-device race tests remain essential. An update during dictation must never retarget the dictated text to the most recent conversation.

Supported notification formats must be described in a versioned compatibility fixture set. Unrecognized structures fail closed. A button labelled Reply is insufficient: require a usable free-form RemoteInput and an action compatible with background execution.

## 7. Message updates and session behavior

On connection, establish a baseline of active notifications. The initial product forwards newly received eligible messages; it does not replay the entire notification shade. On recovery, reconcile current state without treating existing messages as new arrivals.

Suppress group-summary duplicates when actionable child notifications represent the same messages. Do not collapse multiple destinations into a summary that offers one ambiguous reply. Deduplicate repeated callbacks and repeated message history using source identity and in-memory fingerprints. Preserve message order using available timestamps and document a stable fallback where timestamps are missing.

A changed source snapshot creates a new generation and invalidates all outstanding reply tokens for the previous generation, including content-only changes during dictation. Equivalent duplicate callbacks do not create a new generation. The mirrored message can update, but a new reply interaction needs a new token. Later support for preserving a reply across content-only updates requires evidence of stable routing and additional race tests. Source removal, permission revocation, pause, session disconnect, or process restart clears affected content and tokens. Newly reconstructed state receives new tokens; old callbacks cannot recover an expired route.

Provisional memory limits are 50 active conversation bindings and 10 displayed messages per binding. Evict oldest inactive bindings and invalidate their actions together. These limits are product choices to validate in burst tests, not Android platform limits.

## 8. Reply transaction

1. Android Auto collects the user's response through its normal message-reply flow.
2. The host provides its normal readback and send/cancel interaction. Verify this on the target car; the companion does not control all host wording. [S2]
3. The bridge receives the confirmed reply action and resolves its opaque token.
4. It rejects absent, expired, already-consumed, or ambiguous bindings and empty or invalid input.
5. It checks that the original source and action still match the selected destination.
6. It atomically consumes the token before invoking the source action.
7. It supplies the text under the original RemoteInput key and invokes the original Slack PendingIntent once for that token. [S5]
8. It records an accurate local result without retaining the reply body.

Do not reconstruct Slack's internal intent, launch a guessed deep link, choose the first available Reply action, or silently substitute a fresh destination after validation fails. Keep a thread-reply action distinct from a channel-level action. Never silently shorten an outgoing reply; reject input that cannot be handled and explain the limit.

### Status model

| Status | Meaning |
| --- | --- |
| Ready | A supported current reply binding is available |
| Unavailable | Permission, session, source, or capability is missing |
| Consumed | This token has already entered a single dispatch attempt |
| Handed to Slack | The Android invocation completed without a known handoff error |
| Handoff failed | A definite local error prevented execution, such as a cancelled action |
| Outcome unknown | Interruption leaves the result uncertain |

Android operation completion is not Slack server confirmation. Do not translate a successful send() call, an OnFinished callback, a local echo, or a disappearing notification into Delivered. [S6]

Do not automatically retry an uncertain attempt. Consuming an in-memory token prevents repeated dispatch within its lifetime; it is not an exactly-once delivery guarantee across crashes. After restart, reject old tokens and clear orphaned bridge notifications. Any later recovery UX must preserve that distinction.

Android Auto may announce success in its own words before Slack delivery can be verified. Observe this in G4. Generic host wording after an actual handoff can be a disclosed limitation. A success announcement when the bridge rejected the action or knows the handoff failed blocks the pilot unless supported behavior corrects it. Compatibility notes alone do not resolve that failure. Never claim the companion controls host wording it cannot control.

## 9. Reading and dismissal

Android Auto's mark-as-read action is triggered when the car notification is selected. It is not a completion event for audio playback. [S3]

Use that action to update only the bridge's local car-session acknowledgement. Do not dismiss the original Slack notification or forward a source mark-as-read action merely because the mirrored notification was opened. The original action may still be needed for a reply. Phone-side wording should say acknowledged in car, not read in Slack.

Track reply delivery and read-state changes separately. Slack may change its own notification/read state during a reply; observe the actual behavior. Source removal invalidates the associated bridge action. A future read-synchronization feature requires its own source capability and tests.

## 10. Phone interface and permissions

The phone interface has three areas:

- Setup: explanation of notification access, grant/revoke instructions, and a synthetic test.
- Status: bridge enabled/paused, listener connected, Android Auto session active, supported notification types, and last content-free error.
- Preferences: a simple allowlist where stable metadata permits it, notification limits, and a clear-session control.

Use readable labels, consistent icon/button sizes, restrained motion, TalkBack labels, and visible keyboard/focus states where applicable. Status must not rely on color. Error messages should identify the corrective action, such as restoring permission or receiving a new message, without showing an internal stack trace.

Android notification access is broader than a per-Slack permission. Explain that breadth while filtering to Slack in application code. Never claim the operating system grants access to only Slack if it does not.

The companion requests only the permissions its implementation requires. Proposed exclusions are INTERNET, microphone, contacts, SMS, location, accessibility-service access, and general storage access. Android Auto handles dictation; the installed Slack app handles transmission. Check the merged manifest, including dependencies. Handle the normal permission to post notifications on Android versions that require it.

## 11. Privacy and diagnostics

Message text, sender and destination labels, action handles, and reply text remain in memory for the session. Persist only preferences and content-free counters or error categories. No analytics, ad SDK, crash-upload SDK, server, Slack credential storage, or cloud message mirror belongs in the MVP.

Production logs and diagnostic exports must exclude notification bodies, sender/channel/workspace identifiers, original notification keys, PendingIntent representations, reply text, and raw notification dumps. Use random session-local diagnostic IDs. Hashing real message text is not a substitute for omitting it from exported diagnostics.

Exports require a visible user action and preview. Public fixtures use invented content and identities. Disable backups of any state that could acquire message content and verify that crash paths do not serialize it. This storage policy governs the companion; Android notification history and Google/Slack services have their own handling. The whole experience is not offline: Slack and Google services retain their own processing and network behavior.

## 12. Lifecycle and performance

Wait for the notification listener's connected callback before accessing active notifications, and respect its disconnected state. [S7] Use events instead of continuous polling. Define connection detection before promising automatic car-only operation; plain Bluetooth pairing alone does not prove Android Auto projection.

Test screen-off operation, reconnect, reboot, app upgrade, Slack restart, permission revocation, network loss, and Samsung sleeping-app behavior on both actual phones. Force-stop requires normal user recovery; the application should not promise to defeat it. Samsung documents the effect of sleeping and deep-sleeping app settings. [S8]

A provisional processing target is a mirrored notification within two seconds of the listener receiving a supported source notification under normal test load. Measure source-to-bridge latency separately from Slack push delivery, assistant latency, or car display delay.

Run a three-hour parked or bench session with the connection, screen state, navigation/audio use, and power conditions recorded. Check for lost events, unbounded memory, duplicate replies, crashes, and unnecessary wakeups. Compare battery or energy behavior with an equivalent baseline; do not infer a battery result from a charging wired session.

## 13. Distribution and repository

Start with a development APK tested using Google's documented Android Auto development options, or Play internal distribution. The documented unknown-sources option applies to messaging-notification apps; installation and notification-access restrictions still need to be documented for the actual phone. [S9]

Public source availability is separate from Google Play acceptance. Google's car-app requirements cover working message receipt/replies, grouping/order, and appropriate messaging behavior. A Slack-specific bridge needs a distribution review; do not promise a public Play listing. [S10]

Implement original code from documented APIs. AA Notification Forwarder is useful background evidence but declares a noncommercial license for most of its code and says its reply button is nonfunctional. Do not copy that implementation into a permissively licensed project without resolving its license terms. [S11]

The public repository should include the specification, a compatibility matrix, synthetic fixtures, build instructions, permission rationale, contribution rules, and release notes. Exclude real messages, staff names, workspace IDs, account tokens, notification captures, device dumps, signing keys, and local configuration. Use ready-for-review pull requests and obtain explicit approval before merging.

## 14. Definition of a pilot

A pilot requires a passing record for each target phone and car for each advertised message type, zero observed wrong-destination replies, no duplicate dispatch in the defined repeated-action tests, a working cancel path, explicit handling of stale/unknown outcomes, recovery from supported lifecycle events, and a clean privacy inspection.

A passing finite test suite is evidence for the tested versions and configuration. It does not prove universal Slack or head-unit compatibility. Keep unsupported types disabled and publish the exact tested combination.

If the original-action route cannot meet these requirements, document the failure and evaluate a separately scoped Slack API client or accept a listening-only tool. Do not disguise a broken reply path as a complete solution.
